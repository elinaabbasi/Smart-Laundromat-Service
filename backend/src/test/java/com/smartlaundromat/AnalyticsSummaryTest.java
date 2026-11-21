package com.smartlaundromat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@DisplayName("UC9: Analytics")
class AnalyticsSummaryTest extends ApiTest {
    static final String ADMIN = "summary.admin@example.com";

    JsonNode summary() throws Exception {
        String json = api.perform(get("/api/admin/summary").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new ObjectMapper().readTree(json);
    }

    @Test
    @DisplayName("UC9-01: The summary counts machines, reservations and completed payments")
    void summaryCountsActivity() throws Exception {
        JsonNode before = summary();
        String customer = "summary.customer@example.com";
        addCustomer(customer);
        long washer = addMachine("Test Washer Summary", "WASHER", "AVAILABLE");
        Instant start = tomorrow();
        reserve(customer, washer, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        JsonNode after = summary();
        assertThat(after.get("machines").asLong()).isEqualTo(before.get("machines").asLong() + 1);
        assertThat(after.get("reservations").asLong()).isEqualTo(before.get("reservations").asLong() + 1);
        assertThat(after.get("completedPayments").asLong()).isEqualTo(before.get("completedPayments").asLong() + 1);
    }

    @Test
    @DisplayName("UC9-02: A declined payment isn't counted as a reservation or a completed payment")
    void declinedPaymentsAreNotCounted() throws Exception {
        String customer = "declined.summary@example.com";
        addCustomer(customer);
        long washer = addMachine("Test Washer Declined", "WASHER", "AVAILABLE");
        JsonNode before = summary();
        Instant start = tomorrow();
        reserve(customer, washer, start, start.plus(30, ChronoUnit.MINUTES), DECLINED_CARD)
                .andExpect(status().isPaymentRequired());

        JsonNode after = summary();
        assertThat(after.get("reservations").asLong()).isEqualTo(before.get("reservations").asLong());
        assertThat(after.get("completedPayments").asLong()).isEqualTo(before.get("completedPayments").asLong());
    }

    @Test
    @DisplayName("UC9-03: With no data, every count is 0")
    void emptySummary() throws Exception {
        // These deletes are undone when the test ends.
        database.sql("DELETE FROM notifications").update();
        database.sql("DELETE FROM reservation_payments").update();
        database.sql("DELETE FROM reservations").update();
        database.sql("DELETE FROM machines").update();

        api.perform(get("/api/admin/summary").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines").value(0))
                .andExpect(jsonPath("$.reservations").value(0))
                .andExpect(jsonPath("$.completedPayments").value(0));
    }

    @Test
    @DisplayName("UC9-04: Customers get 403 on the summary, and signed-out users get 401")
    void summaryIsAdminOnly() throws Exception {
        api.perform(get("/api/admin/summary").with(user("summary.customer@example.com").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        api.perform(get("/api/admin/summary")).andExpect(status().isUnauthorized());
    }
}
