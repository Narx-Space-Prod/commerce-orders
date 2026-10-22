package dev.boutique.orders;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryTest {
    @Test void eventVersionIdentityAndCurrencyAreRequired() {
        UUID event = UUID.randomUUID(), order = UUID.randomUUID();
        assertDoesNotThrow(() -> new PaidOrderEvent(event,"order.paid",1,order,100,"INR","pay_123").validate());
        assertThrows(IllegalArgumentException.class, () -> new PaidOrderEvent(event,"order.paid",2,order,100,"INR","pay_123").validate());
        assertThrows(IllegalArgumentException.class, () -> new PaidOrderEvent(null,"order.paid",1,order,100,"INR","pay_123").validate());
        assertThrows(IllegalArgumentException.class, () -> new PaidOrderEvent(event,"order.paid",1,order,-1,"INR","pay_123").validate());
        assertThrows(IllegalArgumentException.class, () -> new PaidOrderEvent(event,"order.paid",1,order,100,"USD","pay_123").validate());
    }

    @Test void localDummyCredentialsCannotBeUsedAgainstAnAwsEndpoint() {
        assertDoesNotThrow(() -> DeliveryConfiguration.validateEndpoint("http://elasticmq:9324/000000000000/order-paid", "http://elasticmq:9324", true));
        assertDoesNotThrow(() -> DeliveryConfiguration.validateEndpoint("https://sqs.ap-south-1.amazonaws.com/123456789012/order-paid", "", false));
        assertThrows(IllegalArgumentException.class, () -> DeliveryConfiguration.validateEndpoint("https://sqs.ap-south-1.amazonaws.com/123456789012/order-paid", "https://sqs.ap-south-1.amazonaws.com", true));
        assertThrows(IllegalArgumentException.class, () -> DeliveryConfiguration.validateEndpoint("http://elasticmq:9324/queue", "http://elasticmq:9324", false));
        assertThrows(IllegalArgumentException.class, () -> DeliveryConfiguration.validateEndpoint("http://different:9324/queue", "http://elasticmq:9324", true));
        assertThrows(IllegalArgumentException.class, () -> DeliveryConfiguration.validateEndpoint("", "", false));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryConfiguration("wroker"));
    }
}
