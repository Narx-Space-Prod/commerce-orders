package dev.boutique.orders;
import tools.jackson.databind.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;import java.net.http.*;import java.time.Duration;import java.util.*;
@RestController
public class OrdersController {
 private final JdbcTemplate db;private final ObjectMapper mapper;private final AccountsController accounts;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 private final String cart=System.getenv().getOrDefault("CART_URL","http://localhost:8082");
 private final Optional<software.amazon.awssdk.services.sqs.SqsClient> queue;
 private final String queueUrl;
 public OrdersController(JdbcTemplate db,ObjectMapper mapper,AccountsController accounts,
  Optional<software.amazon.awssdk.services.sqs.SqsClient> queue,
  @org.springframework.beans.factory.annotation.Value("${delivery.queue-url}")String queueUrl){this.db=db;this.mapper=mapper;this.accounts=accounts;this.queue=queue;this.queueUrl=queueUrl;}
 static void validateSession(String s){if(s==null||!s.matches("[a-f0-9]{64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_session");}
 static void validateKey(String s){if(s==null||!s.matches("[A-Za-z0-9_-]{8,64}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_key");}
 private JsonNode get(String url,String rid){try{var res=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).header("X-Request-ID",rid).GET().build(),HttpResponse.BodyHandlers.ofString());if(res.statusCode()!=200)throw new IllegalStateException();return mapper.readTree(res.body());}catch(InterruptedException e){Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);}}
 private Map<String,Object> decode(Map<String,Object> row){try{row.put("items",mapper.readTree(row.get("items").toString()));if(row.get("delivery")!=null)row.put("delivery",mapper.readTree(row.get("delivery").toString()));return row;}catch(Exception e){throw new IllegalStateException(e);}}
 private List<Map<String,Object>> find(String session,String key,UUID customer){return db.queryForList("SELECT id,total,currency,items,created_at,delivery,status,payment_status,provider_order_id,provider_payment_id,refund_id FROM orders WHERE session_id=? AND idempotency_key=? AND customer_id IS NOT DISTINCT FROM ?",session,key,customer);}
 private UUID identity(String token){return token==null?null:accounts.customer(token);}
 record Delivery(String name,String address,String city,String postalCode,String country) {}
 static void validateDelivery(Delivery delivery){
  if(delivery==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_delivery");
  for(String value:new String[]{delivery.name(),delivery.address(),delivery.city(),delivery.postalCode(),delivery.country()})
   if(value==null||value.strip().isEmpty()||value.length()>200||value.chars().anyMatch(Character::isISOControl))
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_delivery");
 }
 @GetMapping("/orders") public Map<String,Object> list(@RequestHeader("X-Session-ID")String session,@RequestHeader(value="X-Account-Token",required=false)String token,@RequestParam(defaultValue="0")int page){
  validateSession(session);if(page<0||page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_page");
  UUID customer=identity(token);
  var rows=customer==null?db.queryForList("SELECT id,total,currency,items,created_at,delivery,status,payment_status,provider_order_id,provider_payment_id,refund_id FROM orders WHERE session_id=? AND customer_id IS NULL ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?",session,page*20):db.queryForList("SELECT id,total,currency,items,created_at,delivery,status,payment_status,provider_order_id,provider_payment_id,refund_id FROM orders WHERE customer_id=? ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?",customer,page*20);
  boolean more=rows.size()>20;
  return Map.of("orders",rows.stream().limit(20).map(this::decode).toList(),"page",page,"hasMore",more);
 }
 @GetMapping("/health/live") public Map<String,String> live(){return Map.of("status","ok");}
 @GetMapping("/health/ready") public Map<String,String> ready(){
  try{db.queryForObject("SELECT 1",Integer.class);queue.ifPresent(client->client.getQueueAttributes(r->r.queueUrl(queueUrl)));return Map.of("status","ok");}
  catch(RuntimeException unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"dependency_unavailable");}
 }
 @PostMapping("/orders") @Transactional(timeout=15)
 public Map<String,Object> create(@RequestHeader("X-Session-ID")String session,@RequestHeader("Idempotency-Key")String key,@RequestHeader(value="X-Request-ID",defaultValue="unknown")String rid,@RequestHeader(value="X-Account-Token",required=false)String token,@RequestBody(required=false)Delivery delivery){
  validateSession(session);validateKey(key);UUID customer=accounts.customer(token);validateDelivery(delivery);db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",session+":"+key);
  var old=find(session,key,customer);if(!old.isEmpty())return decode(old.getFirst());
  var requested=get(cart+"/carts/"+session,rid).path("items");if(!requested.isArray()||requested.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"empty_cart");
  var prices=new HashMap<String,JsonNode>();
  for(var product:db.queryForList("SELECT id,name,price,currency,stock,active FROM products ORDER BY id FOR UPDATE")){
   if(Boolean.TRUE.equals(product.get("active")))prices.put(product.get("id").toString(),mapper.valueToTree(product));
  }
  String currency=null;
  var items=new ArrayList<Map<String,Object>>();int total=0;
  for(var i:requested){String id=i.path("productId").asText();int q=i.path("quantity").asInt();var p=prices.get(id);if(p==null||q<1||q>20)throw new ResponseStatusException(HttpStatus.CONFLICT,"invalid_cart");int price=p.path("price").asInt();if(price<=0||currency!=null&&!p.path("currency").asText().equals(currency))throw new ResponseStatusException(HttpStatus.CONFLICT);currency=p.path("currency").asText();
  if(db.update("UPDATE products SET stock=stock-?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND active=TRUE AND stock>=?",q,id,q)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"out_of_stock");
  total=Math.addExact(total,Math.multiplyExact(price,q));items.add(Map.of("productId",id,"name",p.path("name").asText(),"quantity",q,"price",price,"currency",currency));}
  try{db.update("INSERT INTO orders(id,session_id,idempotency_key,total,currency,items,customer_id,delivery,status,payment_status,stock_reserved) VALUES (?,?,?,?,?,?::jsonb,?,?::jsonb,?,?,?)",UUID.randomUUID(),session,key,total,currency,mapper.writeValueAsString(items),customer,delivery==null?null:mapper.writeValueAsString(delivery),"payment_pending","unpaid",true);}catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency_key_in_use");}catch(tools.jackson.core.JacksonException e){throw new IllegalStateException(e);}
  return decode(find(session,key,customer).getFirst());
 }
 @GetMapping("/orders/{id}") public Map<String,Object> read(@PathVariable UUID id,@RequestHeader("X-Session-ID")String session,@RequestHeader(value="X-Account-Token",required=false)String token){
  validateSession(session);UUID customer=identity(token);
  var rows=customer==null?db.queryForList("SELECT id,total,currency,items,created_at,delivery,status,payment_status,provider_order_id,provider_payment_id,refund_id FROM orders WHERE id=? AND session_id=? AND customer_id IS NULL",id,session):db.queryForList("SELECT id,total,currency,items,created_at,delivery,status,payment_status,provider_order_id,provider_payment_id,refund_id FROM orders WHERE id=? AND customer_id=?",id,customer);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  var result=decode(rows.getFirst());
  var receipts=db.queryForList("SELECT id,amount,currency,created_at FROM payment_receipts WHERE order_id=?",id);
  result.put("receipt",receipts.isEmpty()?null:receipts.getFirst());
  return result;
 }
}
