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

    @Test
    @DisplayName("UC2-08: Filtering by type and availability returns only matching machines")
    void combinedTypeAndAvailabilityFilters() throws Exception {
        addMachine("UC2 Available Washer", "WASHER", "AVAILABLE");
        addMachine("UC2 Broken Washer", "WASHER", "OUT_OF_SERVICE");
        addMachine("UC2 Available Dryer", "DRYER", "AVAILABLE");

        long inactive = addMachine(
                "UC2 Inactive Washer", "WASHER", "AVAILABLE");

        database.sql("UPDATE machines SET active = FALSE WHERE id = :id")
                .param("id", inactive)
                .update();

        api.perform(get("/api/machines")
                        .param("type", "WASHER")
                        .param("availableOnly", "true")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", everyItem(is("WASHER"))))
                .andExpect(jsonPath("$[*].status", everyItem(is("AVAILABLE"))))
                .andExpect(jsonPath("$[*].name", hasItem("UC2 Available Washer")))
                .andExpect(jsonPath("$[*].name", not(hasItem("UC2 Broken Washer"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("UC2 Available Dryer"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("UC2 Inactive Washer"))));
    }

    @Test
    @DisplayName("UC2-09: Availability filtering excludes all unavailable machine states")
    void filterAcrossAllMachineStatuses() throws Exception {
        addMachine("UC2 Free Machine", "WASHER", "AVAILABLE");
        addMachine("UC2 Reserved Machine", "WASHER", "RESERVED");
        addMachine("UC2 Busy Machine", "DRYER", "IN_USE");
        addMachine("UC2 Broken Machine", "DRYER", "OUT_OF_SERVICE");

        long inactive = addMachine(
                "UC2 Disabled Machine", "WASHER", "AVAILABLE");

        database.sql("UPDATE machines SET active = FALSE WHERE id = :id")
                .param("id", inactive)
                .update();

        api.perform(get("/api/machines")
                        .param("availableOnly", "true")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status", everyItem(is("AVAILABLE"))))
                .andExpect(jsonPath("$[*].name", hasItem("UC2 Free Machine")))
                .andExpect(jsonPath("$[*].name", not(hasItem("UC2 Reserved Machine"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("UC2 Busy Machine"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("UC2 Broken Machine"))))
                .andExpect(jsonPath("$[*].name", not(hasItem("UC2 Disabled Machine"))));
    }

    @Test
    @DisplayName("UC2-10: Browsing without availability filtering displays all machine statuses")
    void listDisplaysAllMachineStatuses() throws Exception {
        long available = addMachine(
                "UC2 List Available", "WASHER", "AVAILABLE");

        long reserved = addMachine(
                "UC2 List Reserved", "WASHER", "RESERVED");

        long inUse = addMachine(
                "UC2 List In Use", "DRYER", "IN_USE");

        long broken = addMachine(
                "UC2 List Broken", "DRYER", "OUT_OF_SERVICE");

        long inactive = addMachine(
                "UC2 List Inactive", "WASHER", "AVAILABLE");

        database.sql("UPDATE machines SET active = FALSE WHERE id = :id")
                .param("id", inactive)
                .update();

        api.perform(get("/api/machines")
                        .param("availableOnly", "false")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)].status"
                        .formatted(available)).value("AVAILABLE"))
                .andExpect(jsonPath("$[?(@.id == %d)].status"
                        .formatted(reserved)).value("RESERVED"))
                .andExpect(jsonPath("$[?(@.id == %d)].status"
                        .formatted(inUse)).value("IN_USE"))
                .andExpect(jsonPath("$[?(@.id == %d)].status"
                        .formatted(broken)).value("OUT_OF_SERVICE"))
                .andExpect(jsonPath("$[?(@.id == %d)].status"
                        .formatted(inactive)).value("UNAVAILABLE"));
    }

    @Test
    @DisplayName("UC2-11: Searching with no matching machines returns an empty list")
    void noMatchingMachinesReturnsEmptyList() throws Exception {
        database.sql("""
                UPDATE machines
                SET active = FALSE
                WHERE type = 'DRYER'
                """).update();

        addMachine("UC2 Unavailable Dryer", "DRYER", "OUT_OF_SERVICE");
        addMachine("UC2 Available Washer Only", "WASHER", "AVAILABLE");

        api.perform(get("/api/machines")
                        .param("type", "DRYER")
                        .param("availableOnly", "true")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("UC2-12: Machine type filtering ignores capitalization and surrounding spaces")
    void machineTypeInputNormalization() throws Exception {
        addMachine("UC2 Normalized Washer", "WASHER", "AVAILABLE");
        addMachine("UC2 Normalized Dryer", "DRYER", "AVAILABLE");

        String[] washerInputs = {
                "WASHER", "washer", "WaShEr", " washer "
        };

        for (String input : washerInputs) {
            api.perform(get("/api/machines")
                            .param("type", input)
                            .with(user("browser@example.com").roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].type", everyItem(is("WASHER"))))
                    .andExpect(jsonPath("$[*].name",
                            hasItem("UC2 Normalized Washer")));
        }

        String[] dryerInputs = {
                "DRYER", "dryer", "DrYeR", " Dryer "
        };

        for (String input : dryerInputs) {
            api.perform(get("/api/machines")
                            .param("type", input)
                            .with(user("browser@example.com").roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].type", everyItem(is("DRYER"))))
                    .andExpect(jsonPath("$[*].name",
                            hasItem("UC2 Normalized Dryer")));
        }
    }

    @Test
    @DisplayName("UC2-13: Missing or blank machine type returns all machine types")
    void blankMachineTypeReturnsAllTypes() throws Exception {
        addMachine("UC2 Blank Filter Washer", "WASHER", "AVAILABLE");
        addMachine("UC2 Blank Filter Dryer", "DRYER", "AVAILABLE");

        api.perform(get("/api/machines")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name",
                        hasItem("UC2 Blank Filter Washer")))
                .andExpect(jsonPath("$[*].name",
                        hasItem("UC2 Blank Filter Dryer")));

        String[] blankInputs = {"", " ", "   "};

        for (String input : blankInputs) {
            api.perform(get("/api/machines")
                            .param("type", input)
                            .with(user("browser@example.com").roles("CUSTOMER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].name",
                            hasItem("UC2 Blank Filter Washer")))
                    .andExpect(jsonPath("$[*].name",
                            hasItem("UC2 Blank Filter Dryer")));
        }
    }

    @Test
    @DisplayName("UC2-14: Selecting an individual machine requires authentication")
    void selectingMachineRequiresAuthentication() throws Exception {
        long id = addMachine(
                "UC2 Protected Washer", "WASHER", "AVAILABLE");

        api.perform(get("/api/machines/" + id))
                .andExpect(status().isUnauthorized());

        api.perform(get("/api/machines/" + id)
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.name").value("UC2 Protected Washer"))
                .andExpect(jsonPath("$.type").value("WASHER"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }
    @Test
    @DisplayName("UC2-15: Invalid query parameters and machine identifiers are rejected")
    void invalidParameterFormats() throws Exception {
        api.perform(get("/api/machines")
                        .param("availableOnly", "invalid")
                        .with(user("browser@example.com").roles("CUSTOMER")))
                .andExpect(status().isBadRequest());

        String[] malformedIds = {"abc", "1.5", "999999999999999999999"};

        for (String id : malformedIds) {
            api.perform(get("/api/machines/" + id)
                            .with(user("browser@example.com").roles("CUSTOMER")))
                    .andExpect(status().isBadRequest());
        }

        String[] nonexistentIds = {"-1", "0"};

        for (String id : nonexistentIds) {
            api.perform(get("/api/machines/" + id)
                            .with(user("browser@example.com").roles("CUSTOMER")))
                    .andExpect(status().isNotFound());
        }
    }
}
