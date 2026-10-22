package dev.boutique.orders;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

@RestController
class PaymentsController {
    private final JdbcTemplate db;
    private final AccountsController accounts;
    private final RazorpayClient provider;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    PaymentsController(JdbcTemplate db,AccountsController accounts,RazorpayClient provider,TransactionTemplate tx,ObjectMapper mapper){
        this.db=db;this.accounts=accounts;this.provider=provider;this.tx=tx;this.mapper=mapper;
    }
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private Map<String,Object> owned(UUID id,String token){
        var customer=accounts.customer(token);
        var rows=db.queryForList("SELECT * FROM orders WHERE id=? AND customer_id=?",id,customer);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"order_not_found");
        return rows.getFirst();
    }
    @GetMapping("/payments/config") Object config(){return Map.of("enabled",provider.enabled(),"mode",provider.fixtureEnabled()?"fixture":"test");}

    @PostMapping("/orders/{id}/payment")
    Object begin(@PathVariable UUID id,@RequestHeader(value="X-Account-Token",required=false)String token){
        provider.requireEnabled();
        var row=owned(id,token);
        String old=(String)row.get("provider_order_id");
        if(old!=null)return checkout(row,old);
        if(!"payment_pending".equals(row.get("status")))throw conflict("order_not_payable");
        // Persist the in-flight state BEFORE the external request. An uncertain timeout cannot silently create another chargeable order.
        int reserved=db.update("UPDATE orders SET payment_status='initializing',updated_at=CURRENT_TIMESTAMP WHERE id=? AND payment_status='unpaid' AND provider_order_id IS NULL",id);
        if(reserved!=1)throw conflict("payment_initialization_needs_reconciliation");
        try {
            var remote=provider.post("/orders",Map.of("amount",row.get("total"),"currency",row.get("currency"),"receipt",id.toString()));
            String remoteId=RazorpayClient.identifier(remote.path("id").asText(),"order_");
            validateRemoteOrder(row,remote);
            db.update("UPDATE orders SET provider_order_id=?,payment_status='awaiting_payment',updated_at=CURRENT_TIMESTAMP WHERE id=?",remoteId,id);
            return checkout(row,remoteId);
        } catch(RuntimeException e){
            db.update("UPDATE orders SET payment_status='initialization_unknown',updated_at=CURRENT_TIMESTAMP WHERE id=? AND provider_order_id IS NULL",id);
            throw e;
        }
    }
    private Object checkout(Map<String,Object> row,String remoteId){
        if(!"payment_pending".equals(row.get("status")))throw conflict("order_not_payable");
        return Map.of("keyId",provider.keyId,"providerOrderId",remoteId,"amount",row.get("total"),"currency",row.get("currency"),"mode",provider.fixtureEnabled()?"fixture":"test");
    }
    @PostMapping("/orders/{id}/payment/fixture-capture")
    Object fixtureCapture(@PathVariable UUID id,@RequestHeader(value="X-Account-Token",required=false)String token){
        if(!provider.fixtureEnabled())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"not_found");
        var row=owned(id,token);
        if(!"payment_pending".equals(row.get("status"))||!"awaiting_payment".equals(row.get("payment_status")))throw conflict("order_not_payable");
        String remote=(String)row.get("provider_order_id");
        if(remote==null)throw conflict("payment_not_initialized");
        return provider.fixtureCapture(remote);
    }
    private void validateRemoteOrder(Map<String,Object> row,JsonNode remote){
        if(remote.path("amount").asInt()!=((Number)row.get("total")).intValue()
            ||!remote.path("currency").asText().equals(row.get("currency"))
            ||!remote.path("receipt").asText().equals(row.get("id").toString()))throw conflict("payment_identity_mismatch");
    }
    record Verification(String razorpay_payment_id,String razorpay_order_id,String razorpay_signature){}
    @PostMapping("/orders/{id}/payment/verify")
    Object verify(@PathVariable UUID id,@RequestBody Verification proof,@RequestHeader(value="X-Account-Token",required=false)String token){
        var row=owned(id,token);
        String remote=(String)row.get("provider_order_id");
        if(remote==null||!remote.equals(proof.razorpay_order_id()))throw conflict("payment_identity_mismatch");
        String payment=RazorpayClient.identifier(proof.razorpay_payment_id(),"pay_");
        provider.verifyCheckout(remote,payment,proof.razorpay_signature());
        capture(provider.get("/payments/"+payment));
        return Map.of("status","paid");
    }
    private void capture(JsonNode payment){
        String remote=RazorpayClient.identifier(payment.path("order_id").asText(),"order_");
        String paymentId=RazorpayClient.identifier(payment.path("id").asText(),"pay_");
        if(!"captured".equals(payment.path("status").asText())||!payment.path("captured").asBoolean())throw conflict("payment_not_captured");
        tx.executeWithoutResult(status->{
            var rows=db.queryForList("SELECT * FROM orders WHERE provider_order_id=? FOR UPDATE",remote);
            if(rows.isEmpty())throw conflict("payment_order_unknown");
            var row=rows.getFirst();
            if(payment.path("amount").asInt()!=((Number)row.get("total")).intValue()
                ||!payment.path("currency").asText().equals(row.get("currency")))throw conflict("payment_identity_mismatch");
            if(row.get("provider_payment_id")!=null&&!paymentId.equals(row.get("provider_payment_id")))throw conflict("payment_identity_mismatch");
            if(Set.of("paid","refunding","refunded").contains(row.get("payment_status")))return;
            if(!"payment_pending".equals(row.get("status")))throw conflict("order_not_payable");
            db.update("UPDATE orders SET provider_payment_id=?,payment_status='paid',status='confirmed',updated_at=CURRENT_TIMESTAMP WHERE id=?",paymentId,row.get("id"));
            // The payment update and its event commit together, even when the queue is down.
            var event = new PaidOrderEvent(UUID.randomUUID(), "order.paid", 1,
                (UUID) row.get("id"), ((Number) row.get("total")).intValue(),
                row.get("currency").toString(), paymentId);
            db.update("INSERT INTO order_outbox(id,order_id,payload) VALUES (?,?,?::jsonb)",
                event.eventId(), event.orderId(), mapper.writeValueAsString(event));
        });
    }

    @PostMapping("/payments/webhook")
    Object webhook(@RequestBody byte[] raw,@RequestHeader(value="X-Razorpay-Signature",required=false)String signature,
        @RequestHeader(value="X-Razorpay-Event-Id",required=false)String eventId){
        if(raw.length>262144)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"body_too_large");
        provider.verifyWebhook(raw,signature);
        if(eventId==null||!eventId.matches("[A-Za-z0-9_-]{1,100}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_event_id");
        if(!db.queryForList("SELECT event_id FROM payment_events WHERE event_id=?",eventId).isEmpty())return Map.of("status","already_received");
        try {
            var event=mapper.readTree(raw);String type=event.path("event").asText();
            if(type.equals("payment.captured")||type.equals("order.paid")){
                String paymentId=RazorpayClient.identifier(event.path("payload").path("payment").path("entity").path("id").asText(),"pay_");
                capture(provider.get("/payments/"+paymentId));
            } else if(type.equals("refund.processed")||type.equals("refund.failed")){
                String refundId=RazorpayClient.identifier(event.path("payload").path("refund").path("entity").path("id").asText(),"rfnd_");
                settleRefund(provider.get("/refunds/"+refundId));
            }
            db.update("INSERT INTO payment_events(event_id) VALUES (?) ON CONFLICT DO NOTHING",eventId);
            return Map.of("status","received");
        }catch(tools.jackson.core.JacksonException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_json");}
    }

    record Reconcile(String providerOrderId){}
    @PostMapping("/admin/orders/{id}/payment/reconcile")
    Object reconcile(@PathVariable UUID id,@RequestBody Reconcile input,@RequestHeader(value="X-Account-Token",required=false)String token){
        var actor=accounts.admin(token);
        String remoteId=RazorpayClient.identifier(input.providerOrderId(),"order_");
        var row=db.queryForMap("SELECT * FROM orders WHERE id=?",id);
        validateRemoteOrder(row,provider.get("/orders/"+remoteId));
        int updated=db.update("UPDATE orders SET provider_order_id=?,payment_status='awaiting_payment',updated_at=CURRENT_TIMESTAMP WHERE id=? AND provider_order_id IS NULL AND status='payment_pending' AND payment_status IN ('initializing','initialization_unknown')",remoteId,id);
        if(updated!=1)throw conflict("order_not_reconcilable");
        db.update("INSERT INTO admin_audit(actor_id,action,resource_id) VALUES (?, 'payment_reconciled', ?)",actor,id.toString());
        return Map.of("status","reconciled");
    }

    record Refund(String reason){}
    @PostMapping("/admin/orders/{id}/refund")
    Object refund(@PathVariable UUID id,@RequestBody Refund input,@RequestHeader(value="X-Account-Token",required=false)String token){
        var actor=accounts.admin(token);provider.requireEnabled();
        if(input.reason()==null||input.reason().isBlank()||input.reason().length()>300)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_refund_reason");
        Map<String,Object> row=tx.execute(status->{
            var order=db.queryForMap("SELECT * FROM orders WHERE id=? FOR UPDATE",id);
            if(!Set.of("paid","refunding","refunded").contains(order.get("payment_status")))throw conflict("order_not_refundable");
            if("paid".equals(order.get("payment_status"))){
                db.update("UPDATE orders SET payment_status='refunding',refund_key=?,refund_reason=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",UUID.randomUUID(),input.reason().strip(),id);
                db.update("INSERT INTO admin_audit(actor_id,action,resource_id) VALUES (?, 'refund_requested', ?)",actor,id.toString());
            }
            return db.queryForMap("SELECT * FROM orders WHERE id=?",id);
        });
        if(row==null)throw new IllegalStateException();
        if("refunded".equals(row.get("payment_status")))return Map.of("status","refunded");
        JsonNode remote;
        if(row.get("refund_id")!=null)remote=provider.get("/refunds/"+RazorpayClient.identifier(row.get("refund_id").toString(),"rfnd_"));
        else remote=provider.request("POST","/payments/"+RazorpayClient.identifier(row.get("provider_payment_id").toString(),"pay_")+"/refund",
            Map.of("amount",row.get("total"),"speed","normal","receipt",id.toString()),row.get("refund_key").toString());
        String remoteId=RazorpayClient.identifier(remote.path("id").asText(),"rfnd_");
        db.update("UPDATE orders SET refund_id=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND refund_id IS NULL",remoteId,id);
        settleRefund(remote);
        return Map.of("status",remote.path("status").asText(),"refundId",remoteId);
    }
    private void settleRefund(JsonNode refund){
        String refundId=RazorpayClient.identifier(refund.path("id").asText(),"rfnd_");
        String paymentId=RazorpayClient.identifier(refund.path("payment_id").asText(),"pay_");
        tx.executeWithoutResult(status->{
            var rows=db.queryForList("SELECT * FROM orders WHERE provider_payment_id=? FOR UPDATE",paymentId);
            if(rows.isEmpty())throw conflict("refund_order_unknown");
            var row=rows.getFirst();
            if(refund.path("amount").asInt()!=((Number)row.get("total")).intValue()
                ||!refundId.equals(row.get("refund_id")))throw conflict("refund_identity_mismatch");
            if("failed".equals(refund.path("status").asText())){
                db.update("UPDATE orders SET payment_status='paid',refund_id=NULL,refund_key=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=? AND payment_status='refunding'",row.get("id"));
                return;
            }
            if(!"processed".equals(refund.path("status").asText()))return;
            if("refunded".equals(row.get("payment_status")))return;
            if(Boolean.TRUE.equals(row.get("stock_reserved"))&&!Set.of("shipped","delivered").contains(row.get("status")))restock(row);
            db.update("UPDATE orders SET payment_status='refunded',status='refunded',stock_reserved=FALSE,updated_at=CURRENT_TIMESTAMP WHERE id=?",row.get("id"));
        });
    }
    void restock(Map<String,Object> row){
        try{
            for(var item:mapper.readTree(row.get("items").toString()))
                db.update("UPDATE products SET stock=stock+?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",item.path("quantity").asInt(),item.path("productId").asText());
        }catch(tools.jackson.core.JacksonException e){throw new IllegalStateException(e);}
    }
}
