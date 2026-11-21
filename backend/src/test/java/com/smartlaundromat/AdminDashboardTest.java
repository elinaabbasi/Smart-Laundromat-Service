package com.smartlaundromat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

@DisplayName("UC8: Administration")
class AdminDashboardTest extends ApiTest {
    static final String ADMIN = "admin.tester@example.com";
    static final String CUSTOMER = "plain.customer@example.com";

    @BeforeEach
    void setUp() {
        addAccount("Admin Tester", ADMIN, "ADMIN");
        addCustomer(CUSTOMER);
    }

    ResultActions saveMachine(String path, String method, String json) throws Exception {
        var request = method.equals("PUT") ? put(path) : post(path);
        return api.perform(request.with(user(ADMIN).roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    @Test
    @DisplayName("UC8-01: The admin dashboard shows machines, reservations and payments")
    void dashboardShowsEverything() throws Exception {
        long washer = addMachine("Test Washer Admin", "WASHER", "AVAILABLE");
        Instant start = tomorrow();
        reserve(CUSTOMER, washer, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        api.perform(get("/api/admin").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.machines[*].name", hasItem("Test Washer Admin")))
                .andExpect(jsonPath("$.reservations[*].customerEmail", hasItem(CUSTOMER)))
                .andExpect(jsonPath("$.payments[*].status", hasItem("SUCCEEDED")));
    }

    @Test
    @DisplayName("UC8-02: Customers get 403 on admin pages, and signed-out users get 401")
    void customersCannotUseAdminPages() throws Exception {
        api.perform(get("/api/admin").with(user(CUSTOMER).roles("CUSTOMER"))).andExpect(status().isForbidden());
        api.perform(post("/api/admin/machines").with(user(CUSTOMER).roles("CUSTOMER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Sneaky Washer\", \"type\": \"WASHER\", \"status\": \"AVAILABLE\"}"))
                .andExpect(status().isForbidden());
        api.perform(get("/api/admin")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("UC8-03: An admin can add a machine")
    void addMachine() throws Exception {
        saveMachine("/api/admin/machines", "POST",
                        "{\"name\": \"Test Dryer New\", \"type\": \"DRYER\", \"status\": \"AVAILABLE\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Test Dryer New"))
                .andExpect(jsonPath("$.type").value("DRYER"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("UC8-04: Adding a machine with a name that is already used returns 409")
    void duplicateMachineName() throws Exception {
        addMachine("Test Washer Taken", "WASHER", "AVAILABLE");

        saveMachine("/api/admin/machines", "POST",
                        "{\"name\": \"Test Washer Taken\", \"type\": \"WASHER\", \"status\": \"AVAILABLE\"}")
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("UC8-05: Adding a machine with a blank name, unknown type or unknown status returns 400")
    void invalidMachine() throws Exception {
        saveMachine("/api/admin/machines", "POST", "{\"name\": \"\", \"type\": \"WASHER\", \"status\": \"AVAILABLE\"}")
                .andExpect(status().isBadRequest());
        saveMachine("/api/admin/machines", "POST", "{\"name\": \"Test Oven\", \"type\": \"OVEN\", \"status\": \"AVAILABLE\"}")
                .andExpect(status().isBadRequest());
        saveMachine("/api/admin/machines", "POST", "{\"name\": \"Test Washer\", \"type\": \"WASHER\", \"status\": \"BROKEN\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("UC8-06: An admin can rename a machine and change its status")
    void updateMachine() throws Exception {
        long id = addMachine("Test Washer Old Name", "WASHER", "AVAILABLE");

        saveMachine("/api/admin/machines/" + id, "PUT",
                        "{\"name\": \"Test Washer New Name\", \"type\": \"WASHER\", \"status\": \"OUT_OF_SERVICE\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Washer New Name"))
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
    }

    @Test
    @DisplayName("UC8-07: Updating a machine that doesn't exist returns 404, and renaming to a used name returns 409")
    void updateErrors() throws Exception {
        addMachine("Test Washer Existing", "WASHER", "AVAILABLE");
        long id = addMachine("Test Washer Rename", "WASHER", "AVAILABLE");

        saveMachine("/api/admin/machines/999999999", "PUT",
                        "{\"name\": \"Test Ghost\", \"type\": \"WASHER\", \"status\": \"AVAILABLE\"}")
                .andExpect(status().isNotFound());
        saveMachine("/api/admin/machines/" + id, "PUT",
                        "{\"name\": \"Test Washer Existing\", \"type\": \"WASHER\", \"status\": \"AVAILABLE\"}")
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("UC8-08: A deactivated machine can't be booked, and reactivating it makes it bookable again")
    void deactivateAndReactivate() throws Exception {
        long id = addMachine("Test Washer Toggle", "WASHER", "AVAILABLE");
        Instant start = tomorrow();

        api.perform(post("/api/admin/machines/" + id + "/deactivate").with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        reserve(CUSTOMER, id, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isConflict());

        api.perform(post("/api/admin/machines/" + id + "/reactivate").with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        reserve(CUSTOMER, id, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("UC8-09: Deactivating a machine cancels and refunds its future reservations and notifies the customer")
    void deactivateCancelsReservations() throws Exception {
        long id = addMachine("Test Washer Cancel", "WASHER", "AVAILABLE");
        Instant start = tomorrow();
        reserve(CUSTOMER, id, start, start.plus(30, ChronoUnit.MINUTES)).andExpect(status().isCreated());

        api.perform(post("/api/admin/machines/" + id + "/deactivate").with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());

        String reservationStatus = database.sql("SELECT status FROM reservations WHERE machine_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(reservationStatus).isEqualTo("Cancelled");

        String paymentStatus = database.sql("""
                SELECT p.status FROM reservation_payments p
                JOIN reservations r ON r.id = p.reservation_id
                WHERE r.machine_id = :id
                """)
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(paymentStatus).isEqualTo("REFUNDED");
        verify(stripe).refund("pi_test_paid", "refund-pi_test_paid");

        api.perform(get("/api/notifications").with(user(CUSTOMER).roles("CUSTOMER")))
                .andExpect(jsonPath("$[0].message", startsWith("Reservation cancelled: Test Washer Cancel")))
                .andExpect(jsonPath("$[0].message", containsString("$5.00 CAD has been refunded")));
    }

    @Test
    @DisplayName("UC8-10: Deactivating a machine keeps reservations that have already started")
    void deactivateKeepsStartedReservations() throws Exception {
        long id = addMachine("Test Washer In Use", "WASHER", "AVAILABLE");
        long customerId = database.sql("SELECT id FROM customers WHERE email = :email")
                .param("email", CUSTOMER)
                .query(Long.class)
                .single();
        database.sql("""
                INSERT INTO reservations (customer_id, machine, machine_id, starts_at, ends_at, status)
                VALUES (:customerId, 'Test Washer In Use', :id,
                        CURRENT_TIMESTAMP - INTERVAL '10 minutes', CURRENT_TIMESTAMP + INTERVAL '20 minutes', 'Confirmed')
                """)
                .param("customerId", customerId)
                .param("id", id)
                .update();

        api.perform(post("/api/admin/machines/" + id + "/deactivate").with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());

        String reservationStatus = database.sql("SELECT status FROM reservations WHERE machine_id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(reservationStatus).isEqualTo("Confirmed");
        verify(stripe, never()).refund(any(), any());
    }
}
