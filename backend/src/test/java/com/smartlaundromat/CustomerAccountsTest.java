package com.smartlaundromat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

@DisplayName("UC1: Customer registration and sign-in")
class CustomerAccountsTest extends ApiTest {

    ResultActions register(String name, String email, String password) throws Exception {
        return api.perform(post("/api/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "email": "%s", "password": "%s"}
                        """.formatted(name, email, password)));
    }

    MockHttpSession signIn(String email, String password) throws Exception {
        return (MockHttpSession) api.perform(post("/api/login")
                        .with(csrf())
                        .param("email", email)
                        .param("password", password))
                .andExpect(status().isNoContent())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    @Test
    @DisplayName("UC1-01: Registering with valid details creates a customer account with a hashed password")
    void registerCreatesCustomer() throws Exception {
        register("Ava Test", "Ava.Test@Example.com", PASSWORD).andExpect(status().isCreated());

        var account = database.sql("SELECT role, password_hash FROM customers WHERE email = 'ava.test@example.com'")
                .query((row, index) -> new String[] {row.getString("role"), row.getString("password_hash")})
                .single();
        assertThat(account[0]).isEqualTo("CUSTOMER");
        assertThat(account[1]).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, account[1])).isTrue();
    }

    @Test
    @DisplayName("UC1-02: Registering with an email that is already used returns 409")
    void duplicateEmailIsRejected() throws Exception {
        addCustomer("taken@example.com");

        register("Someone Else", "TAKEN@example.com", PASSWORD).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("UC1-03: Registering with a blank name, invalid email or short password returns 400")
    void invalidRegistrationIsRejected() throws Exception {
        register("", "blank.name@example.com", PASSWORD).andExpect(status().isBadRequest());
        register("Bad Email", "not-an-email", PASSWORD).andExpect(status().isBadRequest());
        register("Short Password", "short@example.com", "secret7")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(content().string(not(containsString("secret7"))));
    }

    @Test
    @DisplayName("UC1-04: Signing in with the correct email and password starts a session with the customer role")
    void signInWithCorrectPassword() throws Exception {
        addCustomer("signin@example.com");

        MockHttpSession session = signIn("SignIn@example.com", PASSWORD);

        api.perform(get("/api/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    @DisplayName("UC1-05: Signing in with a wrong password or unknown email returns 401")
    void signInWithWrongDetails() throws Exception {
        addCustomer("wrongpass@example.com");

        api.perform(post("/api/login").with(csrf())
                        .param("email", "wrongpass@example.com")
                        .param("password", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The email or password is incorrect."));
        api.perform(post("/api/login").with(csrf())
                        .param("email", "nobody@example.com")
                        .param("password", PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("UC1-13: Registration without a CSRF token returns 403")
    void missingCsrfTokenReturnsProblemDetail() throws Exception {
        api.perform(post("/api/register"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Refresh the security token and try again."));
    }

    @Test
    @DisplayName("UC1-06: Signing out ends the session")
    void signOutEndsSession() throws Exception {
        addCustomer("signout@example.com");
        MockHttpSession session = signIn("signout@example.com", PASSWORD);

        api.perform(post("/api/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("UC1-07: Customer pages return 401 when nobody is signed in")
    void signedOutRequestsAreRejected() throws Exception {
        api.perform(get("/api/reservations")).andExpect(status().isUnauthorized());
        api.perform(get("/api/session")).andExpect(status().isUnauthorized());
    }

    @Test
@DisplayName("UC1-08: A signed-in customer can view their own reservations")
void customerCanViewOwnReservations() throws Exception {
    String email = "myreservations@example.com";
    addCustomer(email);

    long washer = addMachine(
            "UC1 Test Washer", "WASHER", "AVAILABLE");

    Instant start = tomorrow();

    reserve(email, washer, start,
            start.plus(30, ChronoUnit.MINUTES))
            .andExpect(status().isCreated());

    MockHttpSession session = signIn(email, PASSWORD);

    api.perform(get("/api/reservations").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reservations.length()").value(1))
            .andExpect(jsonPath("$.reservations[0].machine")
                    .value("UC1 Test Washer"))
            .andExpect(jsonPath("$.reservations[0].status")
                    .value("Confirmed"));
    }
    @Test
@DisplayName("UC1-09: Customers can only view their own reservations")
void customersCannotViewOtherReservations() throws Exception {
    String customerA = "customer.a@example.com";
    String customerB = "customer.b@example.com";

    addCustomer(customerA);
    addCustomer(customerB);

    long washer = addMachine(
            "Customer A Washer", "WASHER", "AVAILABLE");

    long dryer = addMachine(
            "Customer B Dryer", "DRYER", "AVAILABLE");

    Instant start = tomorrow();
    Instant end = start.plus(30, ChronoUnit.MINUTES);

    reserve(customerA, washer, start, end)
            .andExpect(status().isCreated());

    reserve(customerB, dryer, start, end)
            .andExpect(status().isCreated());

    MockHttpSession sessionA = signIn(customerA, PASSWORD);
    MockHttpSession sessionB = signIn(customerB, PASSWORD);

    api.perform(get("/api/reservations").session(sessionA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reservations.length()").value(1))
            .andExpect(jsonPath("$.reservations[0].machine")
                    .value("Customer A Washer"));

    api.perform(get("/api/reservations").session(sessionB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reservations.length()").value(1))
            .andExpect(jsonPath("$.reservations[0].machine")
                    .value("Customer B Dryer"));
    }

    @Test
@DisplayName("UC1-10: Protected resources cannot be accessed after logout")
void protectedResourcesAreRejectedAfterLogout() throws Exception {
    String email = "logout.access@example.com";
    addCustomer(email);

    MockHttpSession session = signIn(email, PASSWORD);

    // Verify access before signing out.
    api.perform(get("/api/reservations").session(session))
            .andExpect(status().isOk());

    api.perform(post("/api/logout")
                    .session(session)
                    .with(csrf()))
            .andExpect(status().isNoContent());

    assertThat(session.isInvalid()).isTrue();

    // Attempt to access protected resources using the old session.
    api.perform(get("/api/reservations").session(session))
            .andExpect(status().isUnauthorized());

    api.perform(get("/api/session").session(session))
            .andExpect(status().isUnauthorized());
    }

    @Test
@DisplayName("UC1-11: Registration enforces password length boundaries")
void passwordLengthBoundaries() throws Exception {
    register("Boundary Test", "boundary7@example.com",
            "a".repeat(7))
            .andExpect(status().isBadRequest());

    register("Boundary Test", "boundary8@example.com",
            "a".repeat(8))
            .andExpect(status().isCreated());

    register("Boundary Test", "boundary9@example.com",
            "a".repeat(9))
            .andExpect(status().isCreated());

    register("Boundary Test", "boundary127@example.com",
            "a".repeat(127))
            .andExpect(status().isCreated());

    register("Boundary Test", "boundary128@example.com",
            "a".repeat(128))
            .andExpect(status().isCreated());

    register("Boundary Test", "boundary129@example.com",
            "a".repeat(129))
            .andExpect(status().isBadRequest());
    }

    @Test
@DisplayName("UC1-12: A newly registered customer can immediately sign in")
void newlyRegisteredCustomerCanSignIn() throws Exception {
    String email = "newcustomer@example.com";

    register("New Customer", email, PASSWORD)
            .andExpect(status().isCreated());

    MockHttpSession session = signIn(email, PASSWORD);

    assertThat(session).isNotNull();

    api.perform(get("/api/session").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("CUSTOMER"));

    api.perform(get("/api/reservations").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("New Customer"))
            .andExpect(jsonPath("$.reservations.length()").value(0));
    }
}
