package space.narx.commerce.orders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Domain fixtures; SQL constraints/real commits are exercised by the business suite. */
final class TestData {

  static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  static final String TOKEN = "a".repeat(64);
  static final String PASSPHRASE = "a long test passphrase";
  static final ObjectMapper JSON = new ObjectMapper();

  static JdbcTemplate database() {
    JdbcTemplate db = mock(JdbcTemplate.class);
    when(db.update(anyString(), any(Object[].class))).thenReturn(1);
    return db;
  }

  static PlatformTransactionManager manager() {
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    return manager;
  }

  static TransactionTemplate transaction() {
    return new TransactionTemplate(manager());
  }

  static AccountsController administrator() {
    var accounts = mock(AccountsController.class);
    when(accounts.admin(any())).thenReturn(ID);
    when(accounts.customer(any())).thenReturn(ID);
    return accounts;
  }

  static Map<String, Object> order() {
    var row = new HashMap<String, Object>();
    row.put("id", ID);
    row.put("total", 100);
    row.put("currency", "INR");
    row.put("items", "[{\"productId\":\"mug\",\"name\":\"Mug\",\"quantity\":1,\"price\":100}]");
    row.put("delivery", "{\"city\":\"Kolkata\"}");
    row.put("status", "payment_pending");
    row.put("payment_status", "unpaid");
    row.put("stock_reserved", true);
    return row;
  }

  static CatalogueController.Product product(int version) {
    return new CatalogueController.Product("mug", " Mug ", "Stoneware", "◈", 100, 8, true, version);
  }

  static Map<String, String> paymentConfiguration() {
    return Map.of(
      "RAZORPAY_KEY_ID",
      "rzp_test_fixture",
      "RAZORPAY_KEY_SECRET",
      "unit-test-secret",
      "RAZORPAY_WEBHOOK_SECRET",
      "unit-webhook-secret",
      "ALLOW_PAYMENT_TEST_FIXTURE",
      "true"
    );
  }
}
