package com.smartlaundromat.business;

public interface PaymentGateway {
    String createCustomer(String name, String email, String idempotencyKey);

    Result pay(String method, int amountCents, String currency, String idempotencyKey, String customerId);

    void refund(String paymentIntentId, String idempotencyKey);

    record Result(boolean paid, String intentId) {}
}
