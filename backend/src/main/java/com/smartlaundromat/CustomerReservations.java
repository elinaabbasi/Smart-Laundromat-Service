
package com.smartlaundromat;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class CustomerReservations {

    private final JdbcClient database;

    CustomerReservations(JdbcClient database) {
        this.database = database;
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
                SELECT r.id, r.machine, r.starts_at, r.ends_at, r.status
                FROM reservations r
                JOIN customers c ON r.customer_id = c.id
                WHERE c.email = :email
                ORDER BY r.starts_at DESC, r.id DESC
                """)
                .param("email", email)
                .query((row, index) -> new Reservation(
                        row.getLong("id"),
                        row.getString("machine"),
                        row.getObject("starts_at", OffsetDateTime.class),
                        row.getObject("ends_at", OffsetDateTime.class),
                        row.getString("status")))
                .list();

        return new ReservationsPage(name, reservations);
    }

    // UC3: Create a new machine reservation.
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public Reservation createReservation(
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

        // Find the signed-in customer.
        Long customerId = database.sql("""
                SELECT id FROM customers WHERE email = :email
                """)
                .param("email", customer.getName())
                .query(Long.class)
                .single();

        // Find the selected machine.
        Machine machine = database.sql("""
                SELECT id, name, status
                FROM machines
                WHERE id = :machineId
                """)
                .param("machineId", request.machineId())
                .query((row, index) -> new Machine(
                        row.getLong("id"),
                        row.getString("name"),
                        row.getString("status")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Machine not found."));

        // Only AVAILABLE machines can be reserved.
        if (!machine.status().equals("AVAILABLE")) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This machine is currently unavailable.");
        }

        // Save the reservation.
        // PostgreSQL's exclusion constraint prevents overlapping bookings.
        try {
            return database.sql("""
                    INSERT INTO reservations
                        (customer_id, machine, machine_id,
                         starts_at, ends_at, status)
                    VALUES
                        (:customerId, :machine, :machineId,
                         :startsAt, :endsAt, 'Confirmed')
                    RETURNING id, machine, starts_at, ends_at, status
                    """)
                    .param("customerId", customerId)
                    .param("machine", machine.name())
                    .param("machineId", machine.id())
                    .param("startsAt", request.startsAt().atOffset(java.time.ZoneOffset.UTC))
                    .param("endsAt", request.endsAt().atOffset(java.time.ZoneOffset.UTC))
                    .query((row, index) -> new Reservation(
                            row.getLong("id"),
                            row.getString("machine"),
                            row.getObject("starts_at", OffsetDateTime.class),
                            row.getObject("ends_at", OffsetDateTime.class),
                            row.getString("status")))
                    .single();

        } catch (DataIntegrityViolationException exception) {
            // An overlapping reservation violates the database constraint.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This machine is already booked for the selected time.",
                    exception);
        }
    }

    public record ReservationRequest(
            Long machineId,
            Instant startsAt,
            Instant endsAt) {}

    public record Machine(
            long id,
            String name,
            String status) {}

    public record ReservationsPage(
            String name,
            List<Reservation> reservations) {}

    public record Reservation(
            long id,
            String machine,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String status) {}
}
