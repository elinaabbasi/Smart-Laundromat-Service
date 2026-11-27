package com.smartlaundromat.dataaccess;

import java.util.List;

import com.smartlaundromat.model.AdminDashboardView.AdminPayment;
import com.smartlaundromat.model.AdminDashboardView.AdminReservation;
import com.smartlaundromat.model.AdminDashboardView.Summary;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AdminRepository {
    private final JdbcClient database;

    public AdminRepository(JdbcClient database) {
        this.database = database;
    }

    public List<AdminReservation> findReservations() {
        return database.sql("""
                SELECT r.id, r.machine, r.starts_at, r.ends_at, r.status,
                       c.name AS customer_name, c.email AS customer_email
                FROM reservations r
                JOIN customers c ON r.customer_id = c.id
                ORDER BY r.starts_at DESC, r.id DESC
                """)
                .query(AdminReservation.class)
                .list();
    }

    public List<AdminPayment> findPayments() {
        return database.sql("""
                SELECT id, reservation_id, amount_cents, currency, status, attempted_at
                FROM reservation_payments
                ORDER BY attempted_at DESC, id DESC
                """)
                .query(AdminPayment.class)
                .list();
    }

    public Summary getSummary() {
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
}
