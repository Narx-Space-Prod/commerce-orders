package dev.boutique.orders;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
class AdminController {
    private final JdbcTemplate db;
    private final AccountsController accounts;
    private final ObjectMapper mapper;
    private final PaymentsController payments;
    AdminController(JdbcTemplate db,AccountsController accounts,ObjectMapper mapper,PaymentsController payments){
        this.db=db;this.accounts=accounts;this.mapper=mapper;this.payments=payments;
    }
    @GetMapping("/admin/dashboard")
    Object dashboard(@RequestHeader(value="X-Account-Token",required=false)String token){
        accounts.admin(token);
        return Map.of(
            "counts",db.queryForMap("SELECT (SELECT COUNT(*) FROM customers) AS customers,(SELECT COUNT(*) FROM orders WHERE customer_id IS NOT NULL) AS orders,(SELECT COUNT(*) FROM products WHERE active=TRUE) AS products,(SELECT COUNT(*) FROM orders WHERE status='payment_pending') AS pending"),
            "sales",db.queryForList("SELECT currency,COUNT(*) AS paid_orders,COALESCE(SUM(total),0) AS gross,COALESCE(SUM(CASE WHEN payment_status='refunded' THEN total ELSE 0 END),0) AS refunds,COALESCE(SUM(CASE WHEN payment_status IN ('paid','refunding') THEN total ELSE 0 END),0) AS net FROM orders WHERE payment_status IN ('paid','refunding','refunded') GROUP BY currency"),
            "daily",db.queryForList("SELECT created_at::date AS day,currency,COUNT(*) AS orders,SUM(total) AS gross FROM orders WHERE payment_status IN ('paid','refunding','refunded') AND created_at>CURRENT_TIMESTAMP-INTERVAL '30 days' GROUP BY created_at::date,currency ORDER BY day"),
            "lowStock",db.queryForList("SELECT id,name,stock FROM products WHERE active=TRUE AND stock<10 ORDER BY stock,name"),
            "recent",db.queryForList("SELECT id,total,currency,status,payment_status,created_at FROM orders WHERE customer_id IS NOT NULL ORDER BY created_at DESC LIMIT 8")
        );
    }
    @GetMapping("/admin/orders")
    Object orders(@RequestHeader(value="X-Account-Token",required=false)String token,@RequestParam(defaultValue="0")int page){
        accounts.admin(token);
        if(page<0||page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_page");
        var rows=db.queryForList("SELECT o.id,o.total,o.currency,o.items,o.delivery,o.status,o.payment_status,o.provider_order_id,o.provider_payment_id,o.refund_id,o.refund_reason,o.created_at,c.email,c.name AS customer_name FROM orders o LEFT JOIN customers c ON c.id=o.customer_id ORDER BY o.created_at DESC,o.id DESC LIMIT 21 OFFSET ?",page*20);
        boolean more=rows.size()>20;
        for(var row:rows){
            try{
                row.put("items",mapper.readTree(row.get("items").toString()));
                if(row.get("delivery")!=null)row.put("delivery",mapper.readTree(row.get("delivery").toString()));
            }catch(tools.jackson.core.JacksonException e){throw new IllegalStateException(e);}
        }
        return Map.of("orders",rows.stream().limit(20).toList(),"hasMore",more,"page",page);
    }
    record Status(String status){}
    @PostMapping("/admin/orders/{id}/status") @Transactional
    Object update(@PathVariable UUID id,@RequestBody Status input,@RequestHeader(value="X-Account-Token",required=false)String token){
        var actor=accounts.admin(token);
        var rows=db.queryForList("SELECT * FROM orders WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"order_not_found");
        var row=rows.getFirst();String current=row.get("status").toString(),next=input.status();
        if("cancelled".equals(next)&&"payment_pending".equals(current)&&row.get("provider_order_id")==null
            &&"unpaid".equals(row.get("payment_status"))){
            if(Boolean.TRUE.equals(row.get("stock_reserved")))payments.restock(row);
            db.update("UPDATE orders SET status='cancelled',payment_status='cancelled',stock_reserved=FALSE,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);
        }else{
            var transitions=Map.of("confirmed","processing","processing","shipped","shipped","delivered");
            if(!"paid".equals(row.get("payment_status"))||next==null||!next.equals(transitions.get(current)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"invalid_order_transition");
            db.update("UPDATE orders SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",next,id);
        }
        db.update("INSERT INTO admin_audit(actor_id,action,resource_id) VALUES (?,?,?)",actor,"order_"+next,id.toString());
        return Map.of("status",next);
    }
    @GetMapping("/admin/audit")
    Object audit(@RequestHeader(value="X-Account-Token",required=false)String token){
        accounts.admin(token);
        return db.queryForList("SELECT a.action,a.resource_id,a.created_at,c.name AS actor FROM admin_audit a JOIN customers c ON c.id=a.actor_id ORDER BY a.id DESC LIMIT 100");
    }
}
