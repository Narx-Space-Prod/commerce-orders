package dev.boutique.orders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Accounts share the orders database; no extra microservice or credentials are needed. */
@RestController
public class AccountsController {
    private final JdbcTemplate db;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH = PasswordHash.create("invalid-password-placeholder");

    public AccountsController(JdbcTemplate db) { this.db = db; }
    record Credentials(String email, String password, String name) {}

    static String email(String input) {
        if (input == null) throw bad("invalid_email");
        String normalized = input.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw bad("invalid_email");
        return normalized;
    }

    static void password(String input) {
        if (input == null || input.length() < 15 || input.length() > 128)
            throw bad("password_length");
    }

    static String name(String input) {
        if (input == null || input.strip().isEmpty() || input.strip().length() > 80
            || input.chars().anyMatch(Character::isISOControl)) throw bad("invalid_name");
        return input.strip();
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Shared PostgreSQL counter survives replica changes. Counts successful attempts too. */
    private void throttle(String identity) { throttle(identity,10); }
    private void throttle(String identity,int limit) {
        String identityHash = hash(identity);
        Integer attempts = db.queryForObject("""
            INSERT INTO authentication_attempts(identity_hash,attempts) VALUES (?,1)
            ON CONFLICT(identity_hash) DO UPDATE SET
              attempts=CASE WHEN authentication_attempts.window_start < CURRENT_TIMESTAMP-INTERVAL '15 minutes'
                THEN 1 ELSE authentication_attempts.attempts+1 END,
              window_start=CASE WHEN authentication_attempts.window_start < CURRENT_TIMESTAMP-INTERVAL '15 minutes'
                THEN CURRENT_TIMESTAMP ELSE authentication_attempts.window_start END
            RETURNING attempts
            """, Integer.class, identityHash);
        if (attempts == null || attempts > limit)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "try_later");
    }

    private Map<String,Object> issue(UUID id, String email, String name) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        db.update("DELETE FROM customer_sessions WHERE expires_at < CURRENT_TIMESTAMP");
        db.update("DELETE FROM authentication_attempts WHERE window_start < CURRENT_TIMESTAMP-INTERVAL '1 day'");
        db.update("DELETE FROM customer_sessions WHERE token_hash IN (SELECT token_hash FROM customer_sessions WHERE customer_id=? ORDER BY created_at DESC OFFSET 9)",id);
        db.update("INSERT INTO customer_sessions(token_hash,customer_id,expires_at) VALUES (?,?,CURRENT_TIMESTAMP+INTERVAL '7 days')",
            hash(token), id);
        return Map.of("user", Map.of("id",id,"email",email,"name",name,"role",db.queryForObject("SELECT role FROM customers WHERE id=?",String.class,id)), "token",token);
    }

    @PostMapping("/auth/register")
    public Map<String,Object> register(@RequestBody Credentials input) {
        String email = email(input.email());
        password(input.password());
        String name = name(input.name());
        throttle("global-auth",300);
        throttle("register:" + email);
        UUID id = UUID.randomUUID();
        String encoded = PasswordHash.create(input.password());
        try {
            db.update("INSERT INTO customers(id,email,name,password_hash) VALUES (?,?,?,?)",id,email,name,encoded);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"account_unavailable");
        }
        return issue(id,email,name);
    }

    @PostMapping("/auth/login")
    public Map<String,Object> login(@RequestBody Credentials input) {
        String email = email(input.email());
        if (input.password() == null || input.password().length() > 128) throw bad("invalid_credentials");
        throttle("global-auth",300);
        throttle("login:" + email);
        var rows = db.queryForList("SELECT id,email,name,password_hash FROM customers WHERE email=?",email);
        String encoded = rows.isEmpty() ? DUMMY_HASH : rows.getFirst().get("password_hash").toString();
        boolean valid = PasswordHash.verify(input.password(),encoded);
        if (!valid || rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"invalid_credentials");
        var user = rows.getFirst();
        return issue((UUID)user.get("id"),user.get("email").toString(),user.get("name").toString());
    }

    UUID customer(String token) {
        var user = resolve(token);
        return (UUID)user.get("id");
    }

    UUID admin(String token) {
        var user = resolve(token);
        if (!"admin".equals(user.get("role")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"admin_required");
        return (UUID)user.get("id");
    }

    private Map<String,Object> resolve(String token) {
        if (token == null || !token.matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"sign_in_required");
        List<Map<String,Object>> rows = db.queryForList("""
            SELECT c.id,c.email,c.name,c.role FROM customer_sessions s JOIN customers c ON c.id=s.customer_id
            WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP
            """,hash(token));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"sign_in_required");
        return rows.getFirst();
    }

    @GetMapping("/auth/session")
    public Map<String,Object> current(@RequestHeader(value="X-Account-Token",required=false) String token) {
        return Map.of("user",resolve(token));
    }

    @PostMapping("/auth/logout")
    public Map<String,String> logout(@RequestHeader(value="X-Account-Token",required=false) String token) {
        if (token != null && token.matches("[a-f0-9]{64}"))
            db.update("DELETE FROM customer_sessions WHERE token_hash=?",hash(token));
        return Map.of("status","signed_out");
    }
}
