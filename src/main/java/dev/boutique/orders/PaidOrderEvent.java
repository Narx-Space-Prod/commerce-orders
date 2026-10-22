package dev.boutique.orders;

import java.util.UUID;

/** Versioned, small event contract. No customer or card data. */
record PaidOrderEvent(UUID eventId, String type, int version, UUID orderId,
                      int amount, String currency, String paymentId) {
    void validate() {
        if (eventId == null || orderId == null || !"order.paid".equals(type) || version != 1
            || amount <= 0 || !"INR".equals(currency) || paymentId == null
            || !paymentId.matches("pay_[A-Za-z0-9]{1,76}")) {
            throw new IllegalArgumentException("invalid_paid_order_event");
        }
    }
}
