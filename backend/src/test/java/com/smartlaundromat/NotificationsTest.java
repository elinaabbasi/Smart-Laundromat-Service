package com.smartlaundromat;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UC6: Notifications")
class NotificationsTest extends ApiTest {
    static final String CUSTOMER = "notified@example.com";

    long washer;

    @BeforeEach
    void setUp() {
        addCustomer(CUSTOMER);
        washer = addMachine("Test Washer Notify", "WASHER", "AVAILABLE");
    }

    @Test
    @DisplayName("UC6-01: A paid and confirmed reservation creates a confirmation notification")
    void confirmationNotification() throws Exception {
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        api.perform(get("/api/notifications").with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].message", startsWith("Reservation confirmed: Test Washer Notify")))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    @DisplayName("UC6-02: A declined payment doesn't create a notification")
    void noNotificationForDeclinedPayment() throws Exception {
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES), DECLINED_CARD)
                .andExpect(status().isPaymentRequired());

        api.perform(get("/api/notifications").with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("UC6-03: Customers only see their own notifications")
    void customersOnlySeeTheirOwnNotifications() throws Exception {
        String other = "not.notified@example.com";
        addCustomer(other);
        Instant start = tomorrow();
        reserve(other, washer, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        api.perform(get("/api/notifications").with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].message", not(hasItem(startsWith("Reservation confirmed: Test Washer Notify")))));
    }

    @Test
    @DisplayName("UC6-04: Notifications return 401 when signed out and 403 for admins")
    void notificationAccess() throws Exception {
        api.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        api.perform(get("/api/notifications").with(user("admin@example.com").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }
}
