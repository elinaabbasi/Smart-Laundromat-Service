
package com.smartlaundromat;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class CustomerReservations {

    private static final Pricing PRICING = new Pricing(
            "CAD", 30, Map.of("WASHER", 500, "DRYER", 300), 8);

    private final JdbcClient database;
    private final StripeTestPayments stripe;

    CustomerReservations(JdbcClient database, StripeTestPayments stripe) {
        this.database = database;
        this.stripe = stripe;
    }

    // Existing UC1 functionality: display customer reservations.
    @GetMapping("/reservations")
    public ReservationsPage reservations(Principal customer) {
        String email = customer.getName();

        String name = database.sql(
                "SELECT name FROM customers WHERE email = :email")
                .param("email", email)
                .query(String.class)
                .single();

        List<Reservation> reservations = database.sql("""
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
                            ? unpaidPayment()
                            : new Payment(
                                    row.getInt("amount_cents"),
                                    row.getString("currency"),
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

        return new ReservationsPage(name, reservations, PRICING);
    }

    // UC3: Create a new machine reservation.
    @PostMapping("/reservations")
    @Transactional
    public ResponseEntity<?> createReservation(
            @RequestBody ReservationRequest request,
            Principal customer) {

        // Validate the submitted reservation.
        if (request.machineId() == null ||
                request.startsAt() == null ||
                request.endsAt() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Machine and reservation times are required.");
        }

        if (!request.startsAt().isAfter(Instant.now()) ||
                !request.endsAt().isAfter(request.startsAt())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Choose a future start time and a valid end time.");
        }

        long blockSeconds = PRICING.blockMinutes() * 60L;
        Duration duration = Duration.between(request.startsAt(), request.endsAt());
        if (duration.getNano() != 0 || duration.getSeconds() % blockSeconds != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a duration in 30-minute increments.");
        }
        long blocks = duration.getSeconds() / blockSeconds;
        if (blocks > PRICING.maxBlocks()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reservations can be up to four hours.");
        }

        StripeTestPayments.validateMethod(request.testPaymentMethod());

        // Lock this account so simultaneous checkouts share one Stripe Customer.
        var account = database.sql("""
                SELECT id, name, email, stripe_test_customer_id
                FROM customers
                WHERE email = :email
                FOR NO KEY UPDATE
                """)
                .param("email", customer.getName())
                .query((row, index) -> new CheckoutCustomer(
                        row.getLong("id"), row.getString("name"),
                        row.getString("email"), row.getString("stripe_test_customer_id")))
                .single();

        // Keep availability and pricing stable until checkout finishes.
        Machine machine = database.sql("""
                SELECT id, name, type, status, active
                FROM machines
                WHERE id = :machineId
                FOR SHARE
                """)
                .param("machineId", request.machineId())
                .query(Machine.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Machine not found."));

        if (!machine.active() || !machine.status().equals("AVAILABLE")) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This machine is currently unavailable.");
        }

        int amountCents = (int) blocks * PRICING.blockRatesCents().get(machine.type());
        String currency = PRICING.currency();

        // Claim the time slot inside the transaction before calling Stripe.
        // Other customers only see a confirmed booking after the payment commits.
        Reservation reservation = database.sql("""
                INSERT INTO reservations
                    (customer_id, machine, machine_id,
                     starts_at, ends_at, status)
                VALUES
                    (:customerId, :machine, :machineId,
                     :startsAt, :endsAt, 'Confirmed')
                ON CONFLICT ON CONSTRAINT reservations_no_overlap DO NOTHING
                RETURNING id, machine, starts_at, ends_at, status
                """)
                .param("customerId", account.id())
                .param("machine", machine.name())
                .param("machineId", machine.id())
                .param("startsAt", request.startsAt().atOffset(java.time.ZoneOffset.UTC))
                .param("endsAt", request.endsAt().atOffset(java.time.ZoneOffset.UTC))
                .query((row, index) -> new Reservation(
                        row.getLong("id"),
                        row.getString("machine"),
                        row.getObject("starts_at", OffsetDateTime.class),
                        row.getObject("ends_at", OffsetDateTime.class),
                        row.getString("status"),
                        unpaidPayment()))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This machine is already booked for the selected time."));

        String stripeCustomerId = getStripeCustomerId(account);

        // Retrying the same checkout after a timeout reuses the same Stripe request.
        String checkout = "laundromat:" + account.id() + ":" + machine.id() + ":"
                + reservation.startsAt().toInstant() + ":" + reservation.endsAt().toInstant() + ":"
                + amountCents + ":" + currency + ":" + request.testPaymentMethod();
        String idempotencyKey = UUID.nameUUIDFromBytes(checkout.getBytes(StandardCharsets.UTF_8)).toString();
        StripeTestPayments.Result result = stripe.pay(
                request.testPaymentMethod(), amountCents, currency, idempotencyKey, stripeCustomerId);
        Payment payment = new Payment(amountCents, currency,
                result.paid() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED);
        recordPayment(reservation.id(), payment, result.intentId());
        if (!result.paid()) {
            database.sql("UPDATE reservations SET status = 'Payment Failed' WHERE id = :id")
                    .param("id", reservation.id())
                    .update();
            // Return normally so the transaction commits the failed payment attempt.
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.PAYMENT_REQUIRED, "Payment was declined. Try another payment method.");
            return ResponseEntity.of(problem).build();
        }

        // UC6: Send notification confirming reservation after successful payment
        saveNotification(account.id(), "Reservation confirmed: " + reservation.machine()
                + " (reservation #" + reservation.id() + ").");

        return ResponseEntity.status(HttpStatus.CREATED).body(new Reservation(
                reservation.id(), reservation.machine(), reservation.startsAt(),
                reservation.endsAt(), reservation.status(), payment));
    }

    private String getStripeCustomerId(CheckoutCustomer account) {
        if (account.stripeCustomerId() != null) {
            return account.stripeCustomerId();
        }

        String customerKey = "laundromat-customer:" + account.id() + ":" + account.email();
        String idempotencyKey = UUID.nameUUIDFromBytes(customerKey.getBytes(StandardCharsets.UTF_8)).toString();
        String stripeCustomerId = stripe.createCustomer(account.name(), account.email(), idempotencyKey);
        database.sql("UPDATE customers SET stripe_test_customer_id = :stripeId WHERE id = :id")
                .param("stripeId", stripeCustomerId)
                .param("id", account.id())
                .update();
        return stripeCustomerId;
    }

    private void recordPayment(long reservationId, Payment payment, String stripeIntentId) {
        database.sql("""
                INSERT INTO reservation_payments
                    (reservation_id, amount_cents, currency, status, stripe_payment_intent_id)
                VALUES (:reservationId, :amountCents, :currency, :status, :stripeIntentId)
                """)
                .param("reservationId", reservationId)
                .param("amountCents", payment.amountCents())
                .param("currency", payment.currency())
                .param("status", payment.status().name())
                .param("stripeIntentId", stripeIntentId, java.sql.Types.VARCHAR)
                .update();
    }

    private static Payment unpaidPayment() {
        return new Payment(0, PRICING.currency(), PaymentStatus.UNPAID);
    }

    public record ReservationRequest(
            Long machineId,
            Instant startsAt,
            Instant endsAt,
            String testPaymentMethod) {}

    private record CheckoutCustomer(long id, String name, String email, String stripeCustomerId) {}

    public record Machine(
            long id,
            String name,
            String type,
            String status,
            boolean active) {}

    public record ReservationsPage(
            String name,
            List<Reservation> reservations,
            Pricing pricing) {}

    public record Pricing(String currency, int blockMinutes, Map<String, Integer> blockRatesCents, int maxBlocks) {}

    public enum PaymentStatus { UNPAID, SUCCEEDED, FAILED, REFUNDED }

    public record Payment(int amountCents, String currency, PaymentStatus status) {}

    public record Reservation(
            long id,
            String machine,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String status,
            Payment payment) {}

// UC6: helper method to save one notification row
    private void saveNotification(long customerId, String message) {
        database.sql("""
                INSERT INTO notifications (customer_id, message, created_at)
                VALUES (:customerId, :message, NOW())
                """)
                .param("customerId", customerId)
                .param("message", message)
                .update();
    }


}
