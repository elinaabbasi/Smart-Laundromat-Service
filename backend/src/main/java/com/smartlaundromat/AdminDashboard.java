package com.smartlaundromat;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboard {
    private final JdbcClient database;
    private final StripeTestPayments stripe;

    AdminDashboard(JdbcClient database, StripeTestPayments stripe) {
        this.database = database;
        this.stripe = stripe;
    }

    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Dashboard dashboard(Principal account) {
        String name = database.sql("SELECT name FROM customers WHERE email = :email")
                .param("email", account.getName())
                .query(String.class)
                .single();

        List<AdminMachine> machines = database.sql("""
                SELECT id, name, type, status, active
                FROM machines
                ORDER BY id
                """)
                .query(AdminMachine.class)
                .list();

        List<AdminReservation> reservations = database.sql("""
                SELECT r.id, r.machine, r.starts_at, r.ends_at, r.status,
                       c.name AS customer_name, c.email AS customer_email
                FROM reservations r
                JOIN customers c ON c.id = r.customer_id
                ORDER BY r.starts_at DESC, r.id DESC
                """)
                .query(AdminReservation.class)
                .list();

        List<AdminPayment> payments = database.sql("""
                SELECT id, reservation_id, amount_cents, currency, status, attempted_at
                FROM reservation_payments
                ORDER BY attempted_at DESC, id DESC
                """)
                .query(AdminPayment.class)
                .list();

        return new Dashboard(name, machines, reservations, payments);
    }

    @GetMapping("/summary")
    public Summary summary() {
        return database.sql("""
                SELECT (SELECT COUNT(*) FROM machines) AS machines,
                       (SELECT COUNT(*) FROM reservations
                        WHERE status <> 'Payment Failed') AS reservations,
                       (SELECT COUNT(*) FROM reservation_payments
                        WHERE status = 'SUCCEEDED') AS completed_payments
                """)
                .query(Summary.class)
                .single();
    }

    @PostMapping("/machines")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminMachine addMachine(@Valid @RequestBody MachineDetails details) {
        return database.sql("""
                INSERT INTO machines (name, type, status)
                VALUES (:name, :type, :status)
                RETURNING id, name, type, status, active
                """)
                .param("name", details.name().strip())
                .param("type", details.type())
                .param("status", details.status())
                .query(AdminMachine.class)
                .single();
    }

    @PutMapping("/machines/{id}")
    public AdminMachine updateMachine(@PathVariable long id, @Valid @RequestBody MachineDetails details) {
        return database.sql("""
                UPDATE machines SET name = :name, type = :type, status = :status
                WHERE id = :id
                RETURNING id, name, type, status, active
                """)
                .param("id", id)
                .param("name", details.name().strip())
                .param("type", details.type())
                .param("status", details.status())
                .query(AdminMachine.class)
                .optional()
                .orElseThrow(AdminDashboard::machineNotFound);
    }

    // UC8: Deactivate a machine, then cancel and refund its reservations that haven't started
    // and notify each customer. If a Stripe refund fails, nothing is saved and the admin can try again.
    @PostMapping("/machines/{id}/deactivate")
    @Transactional
    public AdminMachine deactivateMachine(@PathVariable long id) {
        AdminMachine machine = setMachineActive(id, false);

        List<CancelledReservation> cancelled = database.sql("""
                UPDATE reservations SET status = 'Cancelled'
                WHERE machine_id = :id
                  AND status = 'Confirmed'
                  AND starts_at > CURRENT_TIMESTAMP
                RETURNING id, customer_id, machine
                """)
                .param("id", id)
                .query(CancelledReservation.class)
                .list();

        for (CancelledReservation reservation : cancelled) {
            String message = "Reservation cancelled: " + reservation.machine()
                    + " (reservation #" + reservation.id() + ") because the machine is no longer available.";

            PaidPayment paid = database.sql("""
                    SELECT id, amount_cents, currency, stripe_payment_intent_id
                    FROM reservation_payments
                    WHERE reservation_id = :reservationId AND status = 'SUCCEEDED'
                    """)
                    .param("reservationId", reservation.id())
                    .query(PaidPayment.class)
                    .optional()
                    .orElse(null);

            if (paid != null) {
                // The key stops Stripe from refunding the same payment twice if the admin retries.
                stripe.refund(paid.stripePaymentIntentId(), "refund-" + paid.stripePaymentIntentId());
                database.sql("UPDATE reservation_payments SET status = 'REFUNDED' WHERE id = :id")
                        .param("id", paid.id())
                        .update();
                message += " Your payment of $%d.%02d %s has been refunded."
                        .formatted(paid.amountCents() / 100, paid.amountCents() % 100, paid.currency());
            }

            // UC6: Notify the customer, the same way confirmed reservations are notified.
            database.sql("""
                    INSERT INTO notifications (customer_id, message, created_at)
                    VALUES (:customerId, :message, NOW())
                    """)
                    .param("customerId", reservation.customerId())
                    .param("message", message)
                    .update();
        }
        return machine;
    }

    @PostMapping("/machines/{id}/reactivate")
    public AdminMachine reactivateMachine(@PathVariable long id) {
        return setMachineActive(id, true);
    }

    private AdminMachine setMachineActive(long id, boolean active) {
        return database.sql("""
                UPDATE machines SET active = :active
                WHERE id = :id
                RETURNING id, name, type, status, active
                """)
                .param("id", id)
                .param("active", active)
                .query(AdminMachine.class)
                .optional()
                .orElseThrow(AdminDashboard::machineNotFound);
    }

    private static ResponseStatusException machineNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Machine not found.");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail duplicateMachineName() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A machine already uses that name.");
    }

    public record MachineDetails(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "WASHER|DRYER", message = "Choose WASHER or DRYER.") String type,
            @NotNull @Pattern(regexp = "AVAILABLE|RESERVED|IN_USE|OUT_OF_SERVICE",
                    message = "Choose AVAILABLE, RESERVED, IN_USE, or OUT_OF_SERVICE.") String status) {}

    private record CancelledReservation(long id, long customerId, String machine) {}

    private record PaidPayment(long id, int amountCents, String currency, String stripePaymentIntentId) {}

    public record AdminMachine(long id, String name, String type, String status, boolean active) {}

    public record AdminReservation(long id, String machine, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                   String status, String customerName, String customerEmail) {}

    public record AdminPayment(long id, long reservationId, int amountCents, String currency,
                               String status, OffsetDateTime attemptedAt) {}

    public record Summary(long machines, long reservations, long completedPayments) {}

    public record Dashboard(String name, List<AdminMachine> machines, List<AdminReservation> reservations,
                            List<AdminPayment> payments) {}
}
