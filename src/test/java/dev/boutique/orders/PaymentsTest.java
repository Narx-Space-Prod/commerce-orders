package dev.boutique.orders;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaymentsTest {
    @Test void signatureVerificationUsesExactRawBytesAndRejectsTampering() throws Exception {
        byte[] body="{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
        var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("a-test-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String signature=HexFormat.of().formatHex(mac.doFinal(body));
        assertDoesNotThrow(()->RazorpayClient.verify(body,signature,"a-test-secret"));
        assertThrows(RuntimeException.class,()->RazorpayClient.verify("{}".getBytes(StandardCharsets.UTF_8),signature,"a-test-secret"));
        assertThrows(RuntimeException.class,()->RazorpayClient.verify(body,signature,"different-key"));
        assertThrows(RuntimeException.class,()->RazorpayClient.verify(body,null,"a-test-secret"));
    }
    @Test void providerIdentifiersCannotEscapeAnApiPath() {
        assertEquals("pay_123Abc",RazorpayClient.identifier("pay_123Abc","pay_"));
        assertThrows(RuntimeException.class,()->RazorpayClient.identifier("pay_../refund","pay_"));
        assertThrows(RuntimeException.class,()->RazorpayClient.identifier("order_123","pay_"));
    }
    @Test void productValidationRejectsInvalidInventoryAndPrices() {
        assertDoesNotThrow(()->CatalogueController.validate(new CatalogueController.Product("new-product","New product","Description","◈",100,2,true,0)));
        assertThrows(RuntimeException.class,()->CatalogueController.validate(new CatalogueController.Product("../bad","Product","Description","◈",100,2,true,0)));
        assertThrows(RuntimeException.class,()->CatalogueController.validate(new CatalogueController.Product("product","Product","Description","◈",-100,2,true,0)));
        assertThrows(RuntimeException.class,()->CatalogueController.validate(new CatalogueController.Product("product","Product","Description","◈",100,-2,true,0)));
    }
}
