package space.narx.commerce.orders;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.*;

/** Test-mode only. Private API credentials never leave this backend. */
@Component
class RazorpayClient {

  final String keyId;
  private final String secret;
  private final String webhookSecret;
  private final String base;
  private final boolean allowFixture;
  private final HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(2))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build();
  private final ObjectMapper mapper;

  @Autowired
  RazorpayClient(ObjectMapper mapper) {
    this(mapper, System.getenv());
  }

  // Explicit immutable configuration also lets tests use an isolated loopback
  // provider without changing process-wide environment variables.
  RazorpayClient(ObjectMapper mapper, Map<String, String> configuration) {
    this.mapper = mapper;
    this.keyId = configuration.getOrDefault("RAZORPAY_KEY_ID", "");
    this.secret = configuration.getOrDefault("RAZORPAY_KEY_SECRET", "");
    this.webhookSecret = configuration.getOrDefault("RAZORPAY_WEBHOOK_SECRET", "");
    this.base = configuration.getOrDefault("RAZORPAY_API_BASE", "https://api.razorpay.com/v1");
    this.allowFixture = "true".equals(configuration.get("ALLOW_PAYMENT_TEST_FIXTURE"));
    if (!keyId.isEmpty() && !keyId.startsWith("rzp_test_")) throw new IllegalStateException(
      "Only Razorpay test keys are supported"
    );
    var uri = URI.create(base);
    if (
      !base.equals("https://api.razorpay.com/v1") &&
      !(allowFixture &&
        keyId.equals("rzp_test_fixture") &&
        Set.of("127.0.0.1", "localhost", "razorpay-fixture").contains(uri.getHost()) &&
        "http".equals(uri.getScheme()))
    ) throw new IllegalStateException("Unapproved payment API endpoint");
  }

  boolean enabled() {
    return keyId.startsWith("rzp_test_") && !secret.isEmpty() && !webhookSecret.isEmpty();
  }

  boolean fixtureEnabled() {
    return (keyId.equals("rzp_test_fixture") && allowFixture);
  }

  JsonNode fixtureCapture(String providerOrderId) {
    if (!fixtureEnabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not_found");
    String id = identifier(providerOrderId, "order_");
    try {
      var uri = URI.create(base.substring(0, base.length() - 3) + "/_capture/" + id);
      var response = client.send(
        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
        HttpResponse.BodyHandlers.ofString()
      );
      if (
        response.statusCode() < 200 || response.statusCode() >= 300
      ) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payment_provider_rejected");
      return mapper.readTree(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "payment_provider_unavailable"
      );
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "payment_provider_unavailable"
      );
    }
  }

  void requireEnabled() {
    if (!enabled()) throw new ResponseStatusException(
      HttpStatus.SERVICE_UNAVAILABLE,
      "payments_not_configured"
    );
  }

  JsonNode request(String method, String path, Object payload, String refundKey) {
    requireEnabled();
    try {
      var request = HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(3))
        .header(
          "Authorization",
          "Basic " +
          Base64.getEncoder().encodeToString(
            (keyId + ":" + secret).getBytes(StandardCharsets.UTF_8)
          )
        )
        .header("Content-Type", "application/json");
      if (refundKey != null) request.header("X-Refund-Idempotency", refundKey);
      request.method(
        method,
        payload == null
          ? HttpRequest.BodyPublishers.noBody()
          : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))
      );
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (
        response.statusCode() < 200 || response.statusCode() >= 300
      ) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payment_provider_rejected");
      return mapper.readTree(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "payment_provider_unavailable"
      );
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "payment_provider_unavailable"
      );
    }
  }

  JsonNode get(String path) {
    return request("GET", path, null, null);
  }

  JsonNode post(String path, Object body) {
    return request("POST", path, body, null);
  }

  void verifyCheckout(String orderId, String paymentId, String signature) {
    requireEnabled();
    verify((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), signature, secret);
  }

  void verifyWebhook(byte[] body, String signature) {
    requireEnabled();
    verify(body, signature, webhookSecret);
  }

  static void verify(byte[] payload, String signature, String key) {
    if (signature == null || !signature.matches("[a-f0-9]{64}")) throw new ResponseStatusException(
      HttpStatus.BAD_REQUEST,
      "invalid_payment_signature"
    );
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      if (
        !MessageDigest.isEqual(mac.doFinal(payload), HexFormat.of().parseHex(signature))
      ) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_payment_signature");
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String identifier(String value, String prefix) {
    if (
      value == null || !value.matches(prefix + "[A-Za-z0-9]{1,64}")
    ) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_payment_id");
    return value;
  }
}
