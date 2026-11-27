package com.smartlaundromat;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@DisplayName("UC3: Schedule and reserve a machine")
class ReservationsTest extends ApiTest {
    static final String CUSTOMER = "booker@example.com";

    long washer;

    @BeforeEach
    void setUp() {
        addCustomer(CUSTOMER);
        washer = addMachine("Test Washer Booking", "WASHER", "AVAILABLE");
    }

    @Test
    @DisplayName("UC3-01: A valid one-hour reservation is confirmed and priced at two 30-minute blocks")
    void validReservation() throws Exception {
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start, start.plus(60, ChronoUnit.MINUTES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.machine").value("Test Washer Booking"))
                .andExpect(jsonPath("$.status").value("Confirmed"))
                .andExpect(jsonPath("$.payment.amountCents").value(1000))
                .andExpect(jsonPath("$.payment.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("UC3-02: A reservation that starts in the past returns 400")
    void pastReservation() throws Exception {
        Instant start = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);

        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("UC3-03: A reservation can be made more than 14 days ahead")
    void farAheadIsAllowed() throws Exception {
        Instant start = tomorrow().plus(15, ChronoUnit.DAYS);

        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("UC3-04: A reservation whose length isn't a multiple of 30 minutes returns 400")
    void notInBlocks() throws Exception {
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start, start.plus(45, ChronoUnit.MINUTES))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("UC3-05: A reservation can start at any minute (e.g. 10:10), as long as its length is in 30-minute blocks")
    void anyStartMinuteIsAllowed() throws Exception {
        Instant start = tomorrow().plus(10, ChronoUnit.MINUTES);

        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("UC3-06: A reservation longer than four hours returns 400, and exactly four hours is allowed")
    void maximumLength() throws Exception {
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start, start.plus(270, ChronoUnit.MINUTES))
                .andExpect(status().isBadRequest());
        reserve(CUSTOMER, washer, start, start.plus(240, ChronoUnit.MINUTES))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("UC3-07: A reservation that ends before it starts, or is missing fields, returns 400")
    void invalidTimes() throws Exception {
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start, start.minus(30, ChronoUnit.MINUTES))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Choose a future start time and a valid end time."));
        api.perform(post("/api/reservations")
                        .with(user(CUSTOMER).roles("CUSTOMER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testPaymentMethod\": \"pm_card_visa\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Machine and reservation times are required."));
    }

    @Test
    void malformedReservationJsonReturnsProblemDetail() throws Exception {
        api.perform(post("/api/reservations")
                        .with(user(CUSTOMER).roles("CUSTOMER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Send a valid JSON request body with the expected field types."));
    }

    @Test
    @DisplayName("UC3-08: Booking a time that overlaps another reservation on the same machine returns 409")
    void overlappingReservation() throws Exception {
        String other = "other.booker@example.com";
        addCustomer(other);
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(60, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        reserve(other, washer, start.plus(30, ChronoUnit.MINUTES), start.plus(90, ChronoUnit.MINUTES))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("This machine is already booked for the selected time."));
    }

    @Test
    @DisplayName("UC3-09: Booking right after another reservation ends, or the same time on another machine, is allowed")
    void adjacentAndOtherMachine() throws Exception {
        long secondWasher = addMachine("Test Washer Second", "WASHER", "AVAILABLE");
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(60, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        reserve(CUSTOMER, washer, start.plus(60, ChronoUnit.MINUTES), start.plus(90, ChronoUnit.MINUTES))
                .andExpect(status().isCreated());
        reserve(CUSTOMER, secondWasher, start, start.plus(60, ChronoUnit.MINUTES))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("UC3-10: Booking a machine that is out of service or deactivated returns 409")
    void unavailableMachine() throws Exception {
        long broken = addMachine("Test Washer Out", "WASHER", "OUT_OF_SERVICE");
        long inactive = addMachine("Test Washer Off", "WASHER", "AVAILABLE");
        database.sql("UPDATE machines SET active = FALSE WHERE id = :id").param("id", inactive).update();
        Instant start = tomorrow();

        reserve(CUSTOMER, broken, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isConflict());
        reserve(CUSTOMER, inactive, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("UC3-11: Booking a machine that doesn't exist returns 404")
    void unknownMachine() throws Exception {
        Instant start = tomorrow();

        reserve(CUSTOMER, 999999999, start, start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("UC3-12: Only signed-in customers can reserve: 401 when signed out, 403 for admins")
    void onlyCustomersCanReserve() throws Exception {
        Instant start = tomorrow();
        String body = """
                {"machineId": %d, "startsAt": "%s", "endsAt": "%s", "testPaymentMethod": "pm_card_visa"}
                """.formatted(washer, start, start.plus(30, ChronoUnit.MINUTES));

        api.perform(post("/api/reservations").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        api.perform(post("/api/reservations").with(user("admin@example.com").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
