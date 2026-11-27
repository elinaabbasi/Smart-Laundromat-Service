package com.smartlaundromat.dataaccess;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.smartlaundromat.model.Reservation;
import com.smartlaundromat.model.Reservation.Payment;
import com.smartlaundromat.model.Reservation.PaymentStatus;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
    private final JdbcClient database;

    public ReservationRepository(JdbcClient database) {
        this.database = database;
    }

    public List<Reservation> findForCustomer(String email, Payment unpaidPayment) {
        return database.sql("""
                SELECT r.id, r.machine, r.starts_at, r.ends_at, r.status,
                       p.amount_cents, p.currency, p.status AS payment_status
                FROM reservations r
                JOIN customers c ON r.customer_id = c.id
                LEFT JOIN LATERAL (
                    SELECT amount_cents, currency, status
                    FROM reservation_payments
                    WHERE reservation_id = r.id
                    ORDER BY id DESC
                    LIMIT 1
                ) p ON TRUE
                WHERE c.email = :email AND r.status <> 'Payment Failed'
                ORDER BY r.starts_at DESC, r.id DESC
                """)
                .param("email", email)
                .query((row, index) -> {
                    String paymentStatus = row.getString("payment_status");
                    Payment payment = paymentStatus == null
                            ? unpaidPayment
                            : new Payment(row.getInt("amount_cents"), row.getString("currency"),
                                    PaymentStatus.valueOf(paymentStatus));
                    return new Reservation(
                            row.getLong("id"),
                            row.getString("machine"),
                            row.getObject("starts_at", OffsetDateTime.class),
                            row.getObject("ends_at", OffsetDateTime.class),
                            row.getString("status"),
                            payment);
                })
                .list();
    }

    public Optional<ReservedSlot> claimSlot(long customerId, long machineId, String machineName,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return database.sql("""
                INSERT INTO reservations
                    (customer_id, machine, machine_id, starts_at, ends_at, status)
                VALUES
                    (:customerId, :machine, :machineId, :startsAt, :endsAt, 'Confirmed')
                ON CONFLICT ON CONSTRAINT reservations_no_overlap DO NOTHING
                RETURNING id, machine, starts_at, ends_at, status
                """)
                .param("customerId", customerId)
                .param("machine", machineName)
                .param("machineId", machineId)
                .param("startsAt", startsAt)
                .param("endsAt", endsAt)
                .query(ReservedSlot.class)
                .optional();
    }

    public void recordPayment(long reservationId, Payment payment, String stripeIntentId) {
        database.sql("""
                INSERT INTO reservation_payments
                    (reservation_id, amount_cents, currency, status, stripe_payment_intent_id)
                VALUES (:reservationId, :amountCents, :currency, :status, :stripeIntentId)
                """)
                .param("reservationId", reservationId)
                .param("amountCents", payment.amountCents())
                .param("currency", payment.currency())
                .param("status", payment.status().name())
                .param("stripeIntentId", stripeIntentId, Types.VARCHAR)
                .update();
    }

    public void markPaymentFailed(long reservationId) {
        database.sql("UPDATE reservations SET status = 'Payment Failed' WHERE id = :id")
                .param("id", reservationId)
                .update();
    }

    public List<CancelledReservation> cancelUpcomingForMachine(long machineId) {
        return database.sql("""
                UPDATE reservations SET status = 'Cancelled'
                WHERE machine_id = :id
                  AND status = 'Confirmed'
                  AND starts_at > CURRENT_TIMESTAMP
                RETURNING id, customer_id, machine
                """)
                .param("id", machineId)
                .query(CancelledReservation.class)
                .list();
    }

    public Optional<PaidPayment> findSuccessfulPayment(long reservationId) {
        return database.sql("""
                SELECT id, amount_cents, currency, stripe_payment_intent_id
                FROM reservation_payments
                WHERE reservation_id = :reservationId AND status = 'SUCCEEDED'
                """)
                .param("reservationId", reservationId)
                .query(PaidPayment.class)
                .optional();
    }

    public void markPaymentRefunded(long paymentId) {
        database.sql("UPDATE reservation_payments SET status = 'REFUNDED' WHERE id = :id")
                .param("id", paymentId)
                .update();
    }

    public record ReservedSlot(long id, String machine, OffsetDateTime startsAt, OffsetDateTime endsAt,
                               String status) {}

    public record CancelledReservation(long id, long customerId, String machine) {}

    public record PaidPayment(long id, int amountCents, String currency, String stripePaymentIntentId) {}
}
