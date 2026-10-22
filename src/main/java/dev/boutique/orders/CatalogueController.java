package dev.boutique.orders;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** The commerce database owns inventory so reservation and order insertion are atomic. */
@RestController
class CatalogueController {
    private final JdbcTemplate db;
    private final AccountsController accounts;
    CatalogueController(JdbcTemplate db, AccountsController accounts) { this.db=db; this.accounts=accounts; }

    @GetMapping("/catalogue/products")
    Object products() {
        return db.queryForList("SELECT id,name,description,emoji,price,currency,stock FROM products WHERE active=TRUE ORDER BY id");
    }

    @GetMapping("/admin/products")
    Object all(@RequestHeader(value="X-Account-Token",required=false)String token) {
        accounts.admin(token);
        return db.queryForList("SELECT * FROM products ORDER BY id");
    }

    record Product(String id,String name,String description,String emoji,Integer price,Integer stock,Boolean active,Integer version) {}
    static void validate(Product p) {
        if(p.id()==null||!p.id().matches("[a-z0-9-]{1,64}")||p.name()==null||p.name().isBlank()||p.name().length()>120
            ||p.description()==null||p.description().length()>1000||p.emoji()==null||p.emoji().length()>16
            ||p.price()==null||p.price()<1||p.price()>100_000_000||p.stock()==null||p.stock()<0||p.stock()>1_000_000||p.active()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_product");
    }

    @PutMapping("/admin/products/{id}") @Transactional
    Object save(@PathVariable String id,@RequestBody Product product,@RequestHeader(value="X-Account-Token",required=false)String token) {
        var actor=accounts.admin(token);validate(product);
        if(!id.equals(product.id()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"invalid_product");
        // Advisory lock also serializes two attempts to create the same previously absent ID.
        db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","product:"+id);
        var old=db.queryForList("SELECT version,currency FROM products WHERE id=? FOR UPDATE",id);
        if(old.isEmpty()) {
            if(product.version()!=null&&product.version()!=0)throw new ResponseStatusException(HttpStatus.CONFLICT,"product_changed");
            String currency=db.queryForObject("SELECT currency FROM products ORDER BY id LIMIT 1",String.class);
            db.update("INSERT INTO products(id,name,description,emoji,price,currency,stock,active) VALUES (?,?,?,?,?,?,?,?)",id,product.name().strip(),product.description(),product.emoji(),product.price(),currency,product.stock(),product.active());
        } else {
            if(product.version()==null||!product.version().equals(old.getFirst().get("version")))throw new ResponseStatusException(HttpStatus.CONFLICT,"product_changed");
            db.update("UPDATE products SET name=?,description=?,emoji=?,price=?,stock=?,active=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",product.name().strip(),product.description(),product.emoji(),product.price(),product.stock(),product.active(),id);
        }
        db.update("INSERT INTO admin_audit(actor_id,action,resource_id) VALUES (?, 'product_saved', ?)",actor,id);
        return db.queryForMap("SELECT * FROM products WHERE id=?",id);
    }
}
