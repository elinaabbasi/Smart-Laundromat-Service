package com.smartlaundromat.dataaccess;

import java.util.Optional;

import com.smartlaundromat.model.AccountCredentials;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcClient database;

    public AccountRepository(JdbcClient database) {
        this.database = database;
    }

    public boolean createCustomer(String name, String email, String passwordHash) {
        return database.sql("""
                INSERT INTO customers (name, email, password_hash, role)
                VALUES (:name, :email, :password, 'CUSTOMER')
                ON CONFLICT (email) DO NOTHING
                """)
                .param("name", name)
                .param("email", email)
                .param("password", passwordHash)
                .update() > 0;
    }

    public Optional<AccountCredentials> findCredentials(String email) {
        return database.sql("SELECT email, password_hash, role FROM customers WHERE email = :email")
                .param("email", email)
                .query(AccountCredentials.class)
                .optional();
    }

    public String getName(String email) {
        return database.sql("SELECT name FROM customers WHERE email = :email")
                .param("email", email)
                .query(String.class)
                .single();
    }

    public CheckoutCustomer lockForCheckout(String email) {
        return database.sql("""
                SELECT id, name, email, stripe_test_customer_id
                FROM customers
                WHERE email = :email
                FOR NO KEY UPDATE
                """)
                .param("email", email)
                .query(CheckoutCustomer.class)
                .single();
    }

    public void saveStripeCustomerId(long customerId, String stripeId) {
        database.sql("UPDATE customers SET stripe_test_customer_id = :stripeId WHERE id = :id")
                .param("stripeId", stripeId)
                .param("id", customerId)
                .update();
    }

    public record CheckoutCustomer(long id, String name, String email, String stripeTestCustomerId) {}
}
