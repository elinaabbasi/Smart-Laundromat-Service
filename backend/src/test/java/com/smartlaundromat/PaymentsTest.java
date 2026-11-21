package com.smartlaundromat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("UC4: Payment")
class PaymentsTest extends ApiTest {
    static final String CUSTOMER = "payer@example.com";

    long washer;
    long dryer;

    @BeforeEach
    void setUp() {
        addCustomer(CUSTOMER);
        washer = addMachine("Test Washer Pay", "WASHER", "AVAILABLE");
        dryer = addMachine("Test Dryer Pay", "DRYER", "AVAILABLE");
    }

    Map<String, Object> latestPayment(long machineId) {
        return database.sql("""
                SELECT p.amount_cents, p.currency, p.status, p.stripe_payment_intent_id, r.status AS reservation_status
                FROM reservation_payments p
                JOIN reservations r ON r.id = p.reservation_id
                WHERE r.machine_id = :machineId
                ORDER BY p.id DESC
                LIMIT 1
                """)
                .param("machineId", machineId)
                .query()
                .singleRow();
    }

    @Test
    @DisplayName("UC4-01: A successful payment is recorded as SUCCEEDED with its amount and Stripe reference")
    void successfulPaymentIsRecorded() throws Exception {
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(90, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        Map<String, Object> payment = latestPayment(washer);
        assertThat(payment.get("amount_cents")).isEqualTo(1500);
        assertThat(payment.get("currency")).isEqualTo("CAD");
        assertThat(payment.get("status")).isEqualTo("SUCCEEDED");
        assertThat(payment.get("stripe_payment_intent_id")).isEqualTo("pi_test_paid");
        assertThat(payment.get("reservation_status")).isEqualTo("Confirmed");
    }

    @Test
    @DisplayName("UC4-02: The price depends on the machine type: washers cost $5.00 and dryers $3.00 per block")
    void priceDependsOnMachineType() throws Exception {
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(jsonPath("$.payment.amountCents").value(500));
        reserve(CUSTOMER, dryer, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(jsonPath("$.payment.amountCents").value(300));
    }

    @Test
    @DisplayName("UC4-03: A declined card returns 402 and is recorded as FAILED")
    void declinedPaymentIsRecorded() throws Exception {
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES), DECLINED_CARD)
                .andExpect(status().isPaymentRequired());

        Map<String, Object> payment = latestPayment(washer);
        assertThat(payment.get("status")).isEqualTo("FAILED");
        assertThat(payment.get("amount_cents")).isEqualTo(500);
        assertThat(payment.get("reservation_status")).isEqualTo("Payment Failed");
    }

    @Test
    @DisplayName("UC4-04: After a declined card, the customer can retry and book the same time slot")
    void retryAfterDecline() throws Exception {
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES), DECLINED_CARD)
                .andExpect(status().isPaymentRequired());

        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("UC4-05: A declined reservation doesn't appear in the customer's reservations")
    void declinedReservationIsHidden() throws Exception {
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES), DECLINED_CARD);

        api.perform(get("/api/reservations").with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations[*].machine", not(hasItem("Test Washer Pay"))));
    }

    @Test
    @DisplayName("UC4-06: If Stripe can't be reached, the customer gets an error instead of a booking")
    void stripeUnavailable() throws Exception {
        when(stripe.pay(eq("pm_card_visa"), anyInt(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY));
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @DisplayName("UC4-07: Customers only see their own reservations and payments")
    void customersOnlySeeTheirOwnReservations() throws Exception {
        String other = "someone.else@example.com";
        addCustomer(other);
        Instant start = tomorrow();
        reserve(other, washer, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isCreated());
        reserve(CUSTOMER, dryer, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        api.perform(get("/api/reservations").with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations[*].machine", hasItem("Test Dryer Pay")))
                .andExpect(jsonPath("$.reservations[*].machine", not(hasItem("Test Washer Pay"))));
    }
}
