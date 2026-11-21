package com.smartlaundromat;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UC2: Search and select a machine")
class MachinesTest extends ApiTest {

    @Test
    @DisplayName("UC2-01: Browsing machines requires signing in: 401 when signed out, 200 when signed in")
    void browsingRequiresSignIn() throws Exception {
        addMachine("Test Washer Signed In", "WASHER", "AVAILABLE");

        api.perform(get("/api/machines")).andExpect(status().isUnauthorized());
        api.perform(get("/api/machines").with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("Test Washer Signed In")));
    }

    @Test
    @DisplayName("UC2-02: The machine list shows each machine's name, type and status")
    void listShowsMachineDetails() throws Exception {
        long id = addMachine("Test Dryer Details", "DRYER", "AVAILABLE");

        api.perform(get("/api/machines").with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].name".formatted(id)).value("Test Dryer Details"))
                .andExpect(jsonPath("$[?(@.id == %d)].type".formatted(id)).value("DRYER"))
                .andExpect(jsonPath("$[?(@.id == %d)].status".formatted(id)).value("AVAILABLE"));
    }

    @Test
    @DisplayName("UC2-03: Filtering by type only returns machines of that type")
    void filterByType() throws Exception {
        addMachine("Test Washer Filter", "WASHER", "AVAILABLE");
        addMachine("Test Dryer Filter", "DRYER", "AVAILABLE");

        api.perform(get("/api/machines").param("type", "dryer")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", everyItem(is("DRYER"))))
                .andExpect(jsonPath("$[*].name", hasItem("Test Dryer Filter")));
    }

    @Test
    @DisplayName("UC2-04: Filtering by availability hides machines that are out of service or deactivated")
    void filterByAvailability() throws Exception {
        addMachine("Test Washer Free", "WASHER", "AVAILABLE");
        addMachine("Test Washer Broken", "WASHER", "OUT_OF_SERVICE");
        long inactive = addMachine("Test Washer Inactive", "WASHER", "AVAILABLE");
        database.sql("UPDATE machines SET active = FALSE WHERE id = :id").param("id", inactive).update();

        api.perform(get("/api/machines").param("availableOnly", "true")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", everyItem(is("AVAILABLE"))))
                .andExpect(jsonPath("$[*].name", hasItem("Test Washer Free")))
                .andExpect(jsonPath("$[*].name", not(hasItem("Test Washer Broken"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("Test Washer Inactive"))));
    }

    @Test
    @DisplayName("UC2-05: Filtering by a type that doesn't exist returns 400")
    void invalidTypeIsRejected() throws Exception {
        api.perform(get("/api/machines").param("type", "oven")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("UC2-06: Selecting a machine returns its details, and a deactivated machine shows as unavailable")
    void machineDetails() throws Exception {
        long id = addMachine("Test Washer One", "WASHER", "AVAILABLE");

        api.perform(get("/api/machines/" + id).with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Washer One"))
                .andExpect(jsonPath("$.type").value("WASHER"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));

        database.sql("UPDATE machines SET active = FALSE WHERE id = :id").param("id", id).update();

        api.perform(get("/api/machines/" + id).with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"));
    }

    @Test
    @DisplayName("UC2-07: Selecting a machine that doesn't exist returns 404")
    void unknownMachine() throws Exception {
        api.perform(get("/api/machines/999999999").with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isNotFound());
    }
}
