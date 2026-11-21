package com.smartlaundromat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        register("Short Password", "short@example.com", "short").andExpect(status().isBadRequest());
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
                .andExpect(status().isUnauthorized());
        api.perform(post("/api/login").with(csrf())
                        .param("email", "nobody@example.com")
                        .param("password", PASSWORD))
                .andExpect(status().isUnauthorized());
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
}
