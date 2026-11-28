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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.smartlaundromat.business.ApplicationException;
import com.smartlaundromat.business.ApplicationException.Reason;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

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
                .andExpect(status().isPaymentRequired())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(402))
                .andExpect(jsonPath("$.detail").value("Payment was declined. Try another payment method."));

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
                .thenThrow(new ApplicationException(Reason.PAYMENT_PROVIDER_ERROR,
                        "Could not complete the Stripe test payment."));
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("Could not complete the Stripe test payment."));
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

    @Test
    @DisplayName("UC4-08: Unsupported payment methods are rejected without charging the customer")
    void unsupportedPaymentMethod() throws Exception {
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start,
                start.plus(30, ChronoUnit.MINUTES),
                "pm_invalid")
                .andExpect(status().isBadRequest());

        verify(stripe, never()).pay(
                any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("UC4-09: Missing or empty payment methods return 400")
    void missingOrEmptyPaymentMethod() throws Exception {
        Instant start = tomorrow();
        Instant end = start.plus(30, ChronoUnit.MINUTES);

        String body = """
                {
                    "machineId": %d,
                    "startsAt": "%s",
                    "endsAt": "%s"
                }
                """.formatted(washer, start, end);

        api.perform(post("/api/reservations")
                        .with(user(CUSTOMER).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        reserve(CUSTOMER, washer, start, end, "")
                .andExpect(status().isBadRequest());

        reserve(CUSTOMER, washer, start, end, "   ")
                .andExpect(status().isBadRequest());

        verify(stripe, never()).pay(
                any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("UC4-10: An overlapping reservation does not trigger another payment")
    void overlappingReservationDoesNotCharge() throws Exception {
        String other = "other.payer@example.com";
        addCustomer(other);

        Instant start = tomorrow();
        Instant end = start.plus(60, ChronoUnit.MINUTES);

        reserve(CUSTOMER, washer, start, end)
                .andExpect(status().isCreated());

        reserve(other, washer, start, end)
                .andExpect(status().isConflict());

        verify(stripe, times(1)).pay(
                any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("UC4-11: Invalid reservations do not trigger payment")
    void invalidReservationDoesNotCharge() throws Exception {
        Instant start = tomorrow();

        // Invalid duration
        reserve(CUSTOMER, washer, start,
                start.plus(45, ChronoUnit.MINUTES))
                .andExpect(status().isBadRequest());

        // End time before start time
        reserve(CUSTOMER, washer, start,
                start.minus(30, ChronoUnit.MINUTES))
                .andExpect(status().isBadRequest());

        // Duration exceeds four hours
        reserve(CUSTOMER, washer, start,
                start.plus(270, ChronoUnit.MINUTES))
                .andExpect(status().isBadRequest());

        verify(stripe, never()).pay(
                any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("UC4-12: Stripe provider failure does not record a successful payment")
    void providerFailureDoesNotRecordPayment() throws Exception {
        when(stripe.pay(eq("pm_card_visa"),
                anyInt(), any(), any(), any()))
                .thenThrow(new ApplicationException(
                        Reason.PAYMENT_PROVIDER_ERROR,
                        "Could not complete the Stripe test payment."));

        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start,
                start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isBadGateway());

        long paymentCount = database.sql("""
                SELECT COUNT(*)
                FROM reservation_payments p
                JOIN reservations r ON r.id = p.reservation_id
                WHERE r.machine_id = :machineId
                """)
                .param("machineId", washer)
                .query(Long.class)
                .single();

        assertThat(paymentCount).isZero();
    }

    @Test
    @DisplayName("UC4-13: Failed and successful payment attempts are both recorded after retry")
    void retryPaymentRecords() throws Exception {
        Instant start = tomorrow();
        Instant end = start.plus(30, ChronoUnit.MINUTES);

        reserve(CUSTOMER, washer, start, end, DECLINED_CARD)
                .andExpect(status().isPaymentRequired());

        reserve(CUSTOMER, washer, start, end)
                .andExpect(status().isCreated());

        long failedCount = database.sql("""
                SELECT COUNT(*)
                FROM reservation_payments p
                JOIN reservations r ON r.id = p.reservation_id
                WHERE r.machine_id = :machineId
                AND p.status = 'FAILED'
                AND r.status = 'Payment Failed'
                """)
                .param("machineId", washer)
                .query(Long.class)
                .single();

        long successfulCount = database.sql("""
                SELECT COUNT(*)
                FROM reservation_payments p
                JOIN reservations r ON r.id = p.reservation_id
                WHERE r.machine_id = :machineId
                AND p.status = 'SUCCEEDED'
                AND r.status = 'Confirmed'
                """)
                .param("machineId", washer)
                .query(Long.class)
                .single();

        assertThat(failedCount).isEqualTo(1);
        assertThat(successfulCount).isEqualTo(1);

        verify(stripe, times(2)).pay(
                any(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("UC4-14: Maximum-duration reservations have the correct payment amounts")
    void maximumDurationPricing() throws Exception {
        Instant start = tomorrow();
        Instant end = start.plus(240, ChronoUnit.MINUTES);

        reserve(CUSTOMER, washer, start, end)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.amountCents")
                        .value(4000))
                .andExpect(jsonPath("$.payment.status")
                        .value("SUCCEEDED"));

        reserve(CUSTOMER, dryer, start, end)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.amountCents")
                        .value(2400))
                .andExpect(jsonPath("$.payment.status")
                        .value("SUCCEEDED"));

        assertThat(latestPayment(washer).get("amount_cents"))
                .isEqualTo(4000);

        assertThat(latestPayment(dryer).get("amount_cents"))
                .isEqualTo(2400);

        verify(stripe, times(1)).pay(
                eq("pm_card_visa"), eq(4000),
                eq("CAD"), any(), any());

        verify(stripe, times(1)).pay(
                eq("pm_card_visa"), eq(2400),
                eq("CAD"), any(), any());
    }
}
