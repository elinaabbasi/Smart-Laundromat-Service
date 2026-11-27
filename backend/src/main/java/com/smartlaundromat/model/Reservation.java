package com.smartlaundromat.model;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record Reservation(long id, String machine, OffsetDateTime startsAt, OffsetDateTime endsAt,
                          String status, Payment payment) {
    public record Request(Long machineId, Instant startsAt, Instant endsAt, String testPaymentMethod) {}

    public record Page(String name, List<Reservation> reservations, Pricing pricing) {}

    public record Pricing(String currency, int blockMinutes, Map<String, Integer> blockRatesCents, int maxBlocks) {}

    public enum PaymentStatus { UNPAID, SUCCEEDED, FAILED, REFUNDED }

    public record Payment(int amountCents, String currency, PaymentStatus status) {}
}
