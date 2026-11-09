package space.narx.commerce.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static space.narx.commerce.orders.TestData.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AccountAccessTest {

  @Test
  void registrationIssuesOpaqueTokenButStoresOnlyItsHash() {
    var db = database();
    var accounts = new AccountsController(db);
    when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    when(db.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn(
      "customer"
    );
    var result = accounts.register(
      new AccountsController.Credentials(" Person@Example.test ", PASSPHRASE, " Person ")
    );
    String token = result.get("token").toString();
    assertTrue(token.matches("[a-f0-9]{64}"));
    assertEquals("person@example.test", ((Map<?, ?>) result.get("user")).get("email"));
    var arguments = ArgumentCaptor.forClass(Object[].class);
    verify(db).update(startsWith("INSERT INTO customer_sessions"), arguments.capture());
    assertEquals(AccountsController.hash(token), arguments.getValue()[0]);
    assertNotEquals(token, arguments.getValue()[0]);
    verify(db).update(
      startsWith("INSERT INTO customers"),
      any(),
      eq("person@example.test"),
      eq("Person"),
      argThat(hash -> hash instanceof String && PasswordHash.verify(PASSPHRASE, hash.toString()))
    );
  }

  @Test
  void duplicateAndThrottledRegistrationDoNotIssueSessions() {
    var db = database();
    var accounts = new AccountsController(db);
    when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    doThrow(new DuplicateKeyException("duplicate"))
      .when(db)
      .update(startsWith("INSERT INTO customers"), any(Object[].class));
    assertEquals(
      HttpStatus.CONFLICT,
      assertThrows(ResponseStatusException.class, () ->
        accounts.register(new AccountsController.Credentials("a@example.test", PASSPHRASE, "A"))
      ).getStatusCode()
    );
    verify(db, never()).update(startsWith("INSERT INTO customer_sessions"), any(Object[].class));
    when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(301);
    assertEquals(
      HttpStatus.TOO_MANY_REQUESTS,
      assertThrows(ResponseStatusException.class, () ->
        accounts.login(new AccountsController.Credentials("a@example.test", PASSPHRASE, null))
      ).getStatusCode()
    );
  }

  @Test
  void loginRequiresMatchingPasswordAndAnExistingAccount() {
    var db = database();
    var accounts = new AccountsController(db);
    when(db.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    when(db.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn(
      "customer"
    );
    var user = Map.<String, Object>of(
      "id",
      ID,
      "email",
      "a@example.test",
      "name",
      "A",
      "password_hash",
      PasswordHash.create(PASSPHRASE)
    );
    when(
      db.queryForList(startsWith("SELECT id,email,name,password_hash"), any(Object[].class))
    ).thenReturn(List.of(user));
    assertTrue(
      accounts
        .login(new AccountsController.Credentials("a@example.test", PASSPHRASE, null))
        .containsKey("token")
    );
    assertEquals(
      HttpStatus.UNAUTHORIZED,
      assertThrows(ResponseStatusException.class, () ->
        accounts.login(new AccountsController.Credentials("a@example.test", "wrong", null))
      ).getStatusCode()
    );
    when(
      db.queryForList(startsWith("SELECT id,email,name,password_hash"), any(Object[].class))
    ).thenReturn(List.of());
    assertThrows(ResponseStatusException.class, () ->
      accounts.login(new AccountsController.Credentials("missing@example.test", PASSPHRASE, null))
    );
    assertThrows(ResponseStatusException.class, () ->
      accounts.login(new AccountsController.Credentials("a@example.test", null, null))
    );
  }

  @Test
  void sessionResolutionAndAdminAuthorizationNeverTrustTheRawToken() {
    var db = database();
    var accounts = new AccountsController(db);
    var user = new HashMap<String, Object>(
      Map.of("id", ID, "email", "a@example.test", "name", "A", "role", "customer")
    );
    when(db.queryForList(contains("FROM customer_sessions"), any(Object[].class))).thenReturn(
      List.of(user)
    );
    assertEquals(ID, accounts.customer(TOKEN));
    assertEquals(user, accounts.current(TOKEN).get("user"));
    assertEquals(
      HttpStatus.FORBIDDEN,
      assertThrows(ResponseStatusException.class, () -> accounts.admin(TOKEN)).getStatusCode()
    );
    user.put("role", "admin");
    assertEquals(ID, accounts.admin(TOKEN));
    accounts.logout(TOKEN);
    accounts.logout(null);
    assertEquals("signed_out", accounts.logout("bad").get("status"));
    verify(db).update(
      startsWith("DELETE FROM customer_sessions WHERE token_hash=?"),
      eq(AccountsController.hash(TOKEN))
    );
    assertThrows(ResponseStatusException.class, () -> accounts.customer(null));
    when(db.queryForList(contains("FROM customer_sessions"), any(Object[].class))).thenReturn(
      List.of()
    );
    assertThrows(ResponseStatusException.class, () -> accounts.customer(TOKEN));
  }

  @Test
  void validationAndApiErrorsRejectMalformedRequestsWithoutSensitiveDetails() {
    for (String value : Arrays.asList(
      null,
      "@host.test",
      "a@@host.test",
      "a@host.",
      "a @host.test",
      "x".repeat(255)
    ))
      assertThrows(ResponseStatusException.class, () -> AccountsController.email(value));
    for (String value : Arrays.asList(null, "", "x".repeat(81), "bad\nname"))
      assertThrows(ResponseStatusException.class, () -> AccountsController.name(value));
    assertThrows(ResponseStatusException.class, () -> AccountsController.password(null));
    var errors = new ApiErrors();
    var limited = errors.rejected(
      new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "try_later")
    );
    assertEquals("900", limited.getHeaders().getFirst("Retry-After"));
    assertEquals(
      "request_rejected",
      errors.rejected(new ResponseStatusException(HttpStatus.FORBIDDEN)).getBody().get("error")
    );
    assertEquals("invalid_json", errors.malformed().getBody().get("error"));
  }
}
