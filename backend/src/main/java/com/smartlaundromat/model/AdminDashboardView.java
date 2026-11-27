package com.smartlaundromat.model;

import java.time.OffsetDateTime;
import java.util.List;

import com.smartlaundromat.model.Machine.Details;

public record AdminDashboardView(String name, List<Details> machines, List<AdminReservation> reservations,
                                 List<AdminPayment> payments) {
    public record AdminReservation(long id, String machine, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                   String status, String customerName, String customerEmail) {}

    public record AdminPayment(long id, long reservationId, int amountCents, String currency,
                               String status, OffsetDateTime attemptedAt) {}

    public record Summary(long machines, long reservations, long completedPayments) {}
}
