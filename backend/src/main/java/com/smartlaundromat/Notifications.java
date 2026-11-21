package com.smartlaundromat;

import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/notifications")
public class Notifications {
    private final JdbcClient database;

    Notifications(JdbcClient database) {
        this.database = database;
    }

    // UC6: signed-in customer's notifications displayed on customer dashboard, newest first
    @GetMapping
    public List<Notification> getNotifications(Principal customer) {
        return database.sql("""
                SELECT n.id, n.message, n.created_at
                FROM notifications n
                JOIN customers c ON c.id = n.customer_id
                WHERE c.email = :email
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT 20
                """)
                .param("email", customer.getName())
                .query(Notification.class)
                .list();
    }

    public record Notification(long id, String message, OffsetDateTime createdAt) {}

}
