package com.smartlaundromat.dataaccess;

import java.util.List;

import com.smartlaundromat.model.Notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private final JdbcClient database;

    public NotificationRepository(JdbcClient database) {
        this.database = database;
    }

    public List<Notification> findRecentForCustomer(String email, int limit) {
        return database.sql("""
                SELECT n.id, n.message, n.created_at
                FROM notifications n
                JOIN customers c ON c.id = n.customer_id
                WHERE c.email = :email
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT :limit
                """)
                .param("email", email)
                .param("limit", limit)
                .query(Notification.class)
                .list();
    }

    public void save(long customerId, String message) {
        database.sql("""
                INSERT INTO notifications (customer_id, message, created_at)
                VALUES (:customerId, :message, NOW())
                """)
                .param("customerId", customerId)
                .param("message", message)
                .update();
    }
}
