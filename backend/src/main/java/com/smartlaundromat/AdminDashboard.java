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

    AdminDashboard(JdbcClient database) {
        this.database = database;
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

    @PostMapping("/machines/{id}/deactivate")
    public AdminMachine deactivateMachine(@PathVariable long id) {
        return setMachineActive(id, false);
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
    @ResponseStatus(HttpStatus.CONFLICT)
    public void duplicateMachineName() {}

    public record MachineDetails(
            @NotBlank @Size(max = 100) String name,
            @NotNull @Pattern(regexp = "WASHER|DRYER") String type,
            @NotNull @Pattern(regexp = "AVAILABLE|RESERVED|IN_USE|OUT_OF_SERVICE") String status) {}

    public record AdminMachine(long id, String name, String type, String status, boolean active) {}

    public record AdminReservation(long id, String machine, OffsetDateTime startsAt, OffsetDateTime endsAt,
                                   String status, String customerName, String customerEmail) {}

    public record AdminPayment(long id, long reservationId, int amountCents, String currency,
                               String status, OffsetDateTime attemptedAt) {}

    public record Dashboard(String name, List<AdminMachine> machines, List<AdminReservation> reservations,
                            List<AdminPayment> payments) {}
}
