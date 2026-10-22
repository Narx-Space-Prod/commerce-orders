package dev.boutique.orders;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ValidationTest {
@Test void session(){assertDoesNotThrow(()->OrdersController.validateSession("a".repeat(64)));assertThrows(RuntimeException.class,()->OrdersController.validateSession("other-user"));}
@Test void key(){assertThrows(RuntimeException.class,()->OrdersController.validateKey(""));assertThrows(RuntimeException.class,()->OrdersController.validateKey("x".repeat(65)));assertDoesNotThrow(()->OrdersController.validateKey("checkout-1234"));}
}
