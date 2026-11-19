package com.smartlaundromat;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CustomerReservations {
    private final JdbcClient database;

    CustomerReservations(JdbcClient database) {
        this.database = database;
    }

    @GetMapping("/reservations")
    public ReservationsPage reservations(Principal customer) {
        String email = customer.getName();
        String name = database.sql("SELECT name FROM customers WHERE email = :email")
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

    public record ReservationsPage(String name, List<Reservation> reservations) {}
    public record Reservation(long id, String machine, OffsetDateTime startsAt, OffsetDateTime endsAt, String status) {}
}
