package dev.boutique.orders;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountsTest {
    @Test void passwordsUseDistinctSaltsAndRejectWrongCredentials() {
        String a = PasswordHash.create("a long unique passphrase");
        String b = PasswordHash.create("a long unique passphrase");
        assertNotEquals(a,b);
        assertTrue(PasswordHash.verify("a long unique passphrase",a));
        assertFalse(PasswordHash.verify("a different passphrase",a));
        assertFalse(PasswordHash.verify("a long unique passphrase","broken"));
        assertFalse(a.contains("a long unique passphrase"));
    }

    @Test void normalizesEmailAndRejectsInvalidOrExcessiveInputs() {
        assertEquals("person@example.com",AccountsController.email(" Person@Example.com "));
        assertThrows(RuntimeException.class,()->AccountsController.email("person"));
        assertThrows(RuntimeException.class,()->AccountsController.email("person\n@example.com"));
        assertThrows(RuntimeException.class,()->AccountsController.password("short"));
        assertThrows(RuntimeException.class,()->AccountsController.password("x".repeat(129)));
        assertDoesNotThrow(()->AccountsController.password("a long valid passphrase"));
        assertThrows(RuntimeException.class,()->AccountsController.name("\n"));
    }

    @Test void checksDeliveryBeforeStoringIt() {
        assertThrows(RuntimeException.class,()->OrdersController.validateDelivery(null));
        assertDoesNotThrow(()->OrdersController.validateDelivery(new OrdersController.Delivery("Ada","1 Demo Lane","Test City","12345","Example")));
        assertThrows(RuntimeException.class,()->OrdersController.validateDelivery(new OrdersController.Delivery("Ada","","Test City","12345","Example")));
        assertThrows(RuntimeException.class,()->OrdersController.validateDelivery(new OrdersController.Delivery("Ada","1 Demo Lane\nInjected","Test City","12345","Example")));
    }
}
