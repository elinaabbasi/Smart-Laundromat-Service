package com.smartlaundromat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.smartlaundromat.business.PaymentGateway;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

// Shared setup for every API test.
// Each test runs inside a transaction that is rolled back at the end,
// so tests never leave data behind in the database.
// Stripe is replaced with a fake so tests don't need a Stripe key or internet access.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
abstract class ApiTest {
    static final String PASSWORD = "password123";
    static final String DECLINED_CARD = "pm_card_visa_chargeDeclined";

    @Autowired
    MockMvc api;

    @Autowired
    JdbcClient database;

    @Autowired
    PasswordEncoder passwords;

    @MockitoBean
    PaymentGateway stripe;

    @BeforeEach
    void fakeStripe() {
        when(stripe.createCustomer(any(), any(), any())).thenReturn("cus_test");
        when(stripe.pay(eq("pm_card_visa"), anyInt(), any(), any(), any()))
                .thenReturn(new PaymentGateway.Result(true, "pi_test_paid"));
        when(stripe.pay(eq(DECLINED_CARD), anyInt(), any(), any(), any()))
                .thenReturn(new PaymentGateway.Result(false, "pi_test_declined"));
    }

    long addAccount(String name, String email, String role) {
        return database.sql("""
                INSERT INTO customers (name, email, password_hash, role)
                VALUES (:name, :email, :password, :role)
                RETURNING id
                """)
                .param("name", name)
                .param("email", email)
                .param("password", passwords.encode(PASSWORD))
                .param("role", role)
                .query(Long.class)
                .single();
    }

    long addCustomer(String email) {
        return addAccount("Test Customer", email, "CUSTOMER");
    }

    long addMachine(String name, String type, String status) {
        return database.sql("""
                INSERT INTO machines (name, type, status)
                VALUES (:name, :type, :status)
                RETURNING id
                """)
                .param("name", name)
                .param("type", type)
                .param("status", status)
                .query(Long.class)
                .single();
    }

    // A start time tomorrow on the hour, so it is always in the future and on a 30-minute block.
    static Instant tomorrow() {
        return Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
    }

    ResultActions reserve(String email, long machineId, Instant startsAt, Instant endsAt, String card)
            throws Exception {
        String body = """
                {"machineId": %d, "startsAt": "%s", "endsAt": "%s", "testPaymentMethod": "%s"}
                """.formatted(machineId, startsAt, endsAt, card);
        return api.perform(post("/api/reservations")
                .with(user(email).roles("CUSTOMER"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    ResultActions reserve(String email, long machineId, Instant startsAt, Instant endsAt) throws Exception {
        return reserve(email, machineId, startsAt, endsAt, "pm_card_visa");
    }
}
