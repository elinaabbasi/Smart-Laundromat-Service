package com.smartlaundromat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

@DisplayName("UC5: Machine Status Simulation")
class MachineStatusTest extends ApiTest {

    static final String ADMIN = "status.admin@example.com";
    static final String CUSTOMER = "status.customer@example.com";
    static final String MACHINE_NAME = "Test Washer Status";

    long washer;

    @BeforeEach
    void setUp() {
        addAccount("Status Admin", ADMIN, "ADMIN");
        addCustomer(CUSTOMER);
        washer = addMachine(MACHINE_NAME, "WASHER", "AVAILABLE");
    }

    String machineBody(String machineStatus) {
        return """
                {
                    "name": "%s",
                    "type": "WASHER",
                    "status": "%s"
                }
                """.formatted(MACHINE_NAME, machineStatus);
    }

    ResultActions changeStatus(String machineStatus) throws Exception {
        return api.perform(put("/api/admin/machines/" + washer)
                .with(user(ADMIN).roles("ADMIN"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(machineBody(machineStatus)));
    }

    String storedStatus(long machineId) {
        return database.sql("""
                SELECT status FROM machines WHERE id = :id
                """)
                .param("id", machineId)
                .query(String.class)
                .single();
    }

    void verifyMachineStatus(String expectedStatus) throws Exception {
        assertThat(storedStatus(washer)).isEqualTo(expectedStatus);

        api.perform(get("/api/machines/" + washer)
                        .with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    // Machine status transitions

    @Test
    @DisplayName("UC5-01: An admin can change an available machine to reserved")
    void availableToReserved() throws Exception {
        changeStatus("RESERVED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESERVED"));

        verifyMachineStatus("RESERVED");
    }

    @Test
    @DisplayName("UC5-02: An admin can change a reserved machine to in use")
    void reservedToInUse() throws Exception {
        changeStatus("RESERVED").andExpect(status().isOk());

        changeStatus("IN_USE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_USE"));

        verifyMachineStatus("IN_USE");
    }

    @Test
    @DisplayName("UC5-03: An admin can return an in-use machine to available")
    void inUseToAvailable() throws Exception {
        changeStatus("IN_USE").andExpect(status().isOk());

        changeStatus("AVAILABLE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        verifyMachineStatus("AVAILABLE");
    }

    @Test
    @DisplayName("UC5-04: An admin can mark a machine as out of service")
    void availableToOutOfService() throws Exception {
        changeStatus("OUT_OF_SERVICE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));

        verifyMachineStatus("OUT_OF_SERVICE");
    }

    @Test
    @DisplayName("UC5-05: An admin can restore an out-of-service machine")
    void outOfServiceToAvailable() throws Exception {
        changeStatus("OUT_OF_SERVICE").andExpect(status().isOk());

        changeStatus("AVAILABLE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        verifyMachineStatus("AVAILABLE");
    }

    // Validation and authorization

    @Test
    @DisplayName("UC5-06: Invalid statuses are rejected without changing the machine")
    void invalidMachineStatus() throws Exception {
        changeStatus("BROKEN").andExpect(status().isBadRequest());
        changeStatus("").andExpect(status().isBadRequest());
        changeStatus("available").andExpect(status().isBadRequest());
        changeStatus(" ").andExpect(status().isBadRequest());

        verifyMachineStatus("AVAILABLE");
    }

    @Test
    @DisplayName("UC5-07: Customers cannot change machine status")
    void customerCannotChangeStatus() throws Exception {
        api.perform(put("/api/admin/machines/" + washer)
                        .with(user(CUSTOMER).roles("CUSTOMER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(machineBody("IN_USE")))
                .andExpect(status().isForbidden());

        verifyMachineStatus("AVAILABLE");
    }

    @Test
    @DisplayName("UC5-08: Signed-out users cannot change machine status")
    void signedOutUserCannotChangeStatus() throws Exception {
        api.perform(put("/api/admin/machines/" + washer)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(machineBody("IN_USE")))
                .andExpect(status().isUnauthorized());

        verifyMachineStatus("AVAILABLE");
    }

    // Interaction with reservations

    @Test
    @DisplayName("UC5-09: A reserved machine cannot be booked")
    void reservedMachineCannotBeBooked() throws Exception {
        changeStatus("RESERVED").andExpect(status().isOk());

        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start,
                start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isConflict());

        verify(stripe, never()).pay(any(), anyInt(), any(), any(), any());
        verifyMachineStatus("RESERVED");
    }

    @Test
    @DisplayName("UC5-10: An in-use machine cannot be booked")
    void inUseMachineCannotBeBooked() throws Exception {
        changeStatus("IN_USE").andExpect(status().isOk());

        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start,
                start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isConflict());

        verify(stripe, never()).pay(any(), anyInt(), any(), any(), any());
        verifyMachineStatus("IN_USE");
    }

    @Test
    @DisplayName("UC5-11: An out-of-service machine cannot be booked")
    void outOfServiceMachineCannotBeBooked() throws Exception {
        changeStatus("OUT_OF_SERVICE").andExpect(status().isOk());

        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start,
                start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isConflict());

        verify(stripe, never()).pay(any(), anyInt(), any(), any(), any());
        verifyMachineStatus("OUT_OF_SERVICE");
    }

    @Test
    @DisplayName("UC5-12: Restoring available status allows reservations again")
    void restoredMachineCanBeBooked() throws Exception {
        changeStatus("IN_USE").andExpect(status().isOk());

        Instant start = tomorrow();
        Instant end = start.plus(30, ChronoUnit.MINUTES);

        reserve(CUSTOMER, washer, start, end)
                .andExpect(status().isConflict());

        changeStatus("AVAILABLE").andExpect(status().isOk());

        reserve(CUSTOMER, washer, start, end)
                .andExpect(status().isCreated());

        verifyMachineStatus("AVAILABLE");
    }

    // Additional edge cases

    @Test
    @DisplayName("UC5-13: Updating a nonexistent machine returns 404")
    void nonexistentMachineStatusUpdate() throws Exception {
        api.perform(put("/api/admin/machines/999999999")
                        .with(user(ADMIN).roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(machineBody("IN_USE")))
                .andExpect(status().isNotFound());

        verifyMachineStatus("AVAILABLE");
    }

    @Test
    @DisplayName("UC5-14: Saving the same status repeatedly is allowed")
    void repeatedStatusUpdate() throws Exception {
        changeStatus("AVAILABLE").andExpect(status().isOk());
        changeStatus("AVAILABLE").andExpect(status().isOk());

        verifyMachineStatus("AVAILABLE");

        changeStatus("IN_USE").andExpect(status().isOk());
        changeStatus("IN_USE").andExpect(status().isOk());

        verifyMachineStatus("IN_USE");
    }

    @Test
    @DisplayName("UC5-15: Missing and null statuses are rejected")
    void missingOrNullStatus() throws Exception {
        String missingStatus = """
                {
                    "name": "%s",
                    "type": "WASHER"
                }
                """.formatted(MACHINE_NAME);

        String nullStatus = """
                {
                    "name": "%s",
                    "type": "WASHER",
                    "status": null
                }
                """.formatted(MACHINE_NAME);

        api.perform(put("/api/admin/machines/" + washer)
                        .with(user(ADMIN).roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(missingStatus))
                .andExpect(status().isBadRequest());

        api.perform(put("/api/admin/machines/" + washer)
                        .with(user(ADMIN).roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullStatus))
                .andExpect(status().isBadRequest());

        verifyMachineStatus("AVAILABLE");
    }

    @Test
    @DisplayName("UC5-16: Manual status changes preserve existing confirmed reservations")
    void statusChangePreservesReservation() throws Exception {
        Instant start = tomorrow();

        reserve(CUSTOMER, washer, start,
                start.plus(30, ChronoUnit.MINUTES))
                .andExpect(status().isCreated());

        changeStatus("IN_USE").andExpect(status().isOk());

        verifyMachineStatus("IN_USE");

        String reservationStatus = database.sql("""
                SELECT status FROM reservations
                WHERE machine_id = :id
                """)
                .param("id", washer)
                .query(String.class)
                .single();

        assertThat(reservationStatus).isEqualTo("Confirmed");

        String paymentStatus = database.sql("""
                SELECT p.status
                FROM reservation_payments p
                JOIN reservations r ON r.id = p.reservation_id
                WHERE r.machine_id = :id
                """)
                .param("id", washer)
                .query(String.class)
                .single();

        assertThat(paymentStatus).isEqualTo("SUCCEEDED");

        verify(stripe, never()).refund(any(), any());

        api.perform(get("/api/reservations")
                        .with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservations[0].machine")
                        .value(MACHINE_NAME))
                .andExpect(jsonPath("$.reservations[0].status")
                        .value("Confirmed"));
    }

    @Test
    @DisplayName("UC5-17: Changing one machine does not affect other machines")
    void statusChangeDoesNotAffectOtherMachines() throws Exception {
        long secondWasher = addMachine(
                "Test Washer Status Second", "WASHER", "AVAILABLE");

        long dryer = addMachine(
                "Test Dryer Status Second", "DRYER", "RESERVED");

        changeStatus("OUT_OF_SERVICE").andExpect(status().isOk());

        verifyMachineStatus("OUT_OF_SERVICE");

        assertThat(storedStatus(secondWasher)).isEqualTo("AVAILABLE");
        assertThat(storedStatus(dryer)).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("UC5-18: Availability filtering reflects machine status changes")
    void availabilityFilterReflectsStatus() throws Exception {
        api.perform(get("/api/machines")
                        .param("availableOnly", "true")
                        .with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem(MACHINE_NAME)));

        for (String unavailableStatus :
                new String[]{"RESERVED", "IN_USE", "OUT_OF_SERVICE"}) {

            changeStatus(unavailableStatus)
                    .andExpect(status().isOk());

            api.perform(get("/api/machines")
                            .param("availableOnly", "true")
                            .with(user(CUSTOMER).roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name",
                            not(hasItem(MACHINE_NAME))));

            verifyMachineStatus(unavailableStatus);
        }

        changeStatus("AVAILABLE").andExpect(status().isOk());

        api.perform(get("/api/machines")
                        .param("availableOnly", "true")
                        .with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem(MACHINE_NAME)));
    }

    @Test
    @DisplayName("UC5-19: Status updates require a CSRF token")
    void statusUpdateRequiresCsrf() throws Exception {
        api.perform(put("/api/admin/machines/" + washer)
                        .with(user(ADMIN).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(machineBody("IN_USE")))
                .andExpect(status().isForbidden());

        verifyMachineStatus("AVAILABLE");
    }

    @Test
    @DisplayName("UC5-20: Dryer status changes are supported and persisted")
    void dryerStatusUpdate() throws Exception {
        String dryerName = "Test Dryer Status";
        long dryer = addMachine(dryerName, "DRYER", "AVAILABLE");

        String body = """
                {
                    "name": "%s",
                    "type": "DRYER",
                    "status": "IN_USE"
                }
                """.formatted(dryerName);

        api.perform(put("/api/admin/machines/" + dryer)
                        .with(user(ADMIN).roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DRYER"))
                .andExpect(jsonPath("$.status").value("IN_USE"));

        assertThat(storedStatus(dryer)).isEqualTo("IN_USE");

        api.perform(get("/api/machines/" + dryer)
                        .with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_USE"));

        verifyMachineStatus("AVAILABLE");
    }
}