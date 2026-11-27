package com.smartlaundromat;

import java.io.IOException;
import java.net.URI;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/styles.css", "/app.js").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/register", "/api/login").permitAll()
                        .requestMatchers("/api/reservations").hasRole("CUSTOMER")
                        .requestMatchers("/api/notifications").hasRole("CUSTOMER")
                        .requestMatchers("/api/admin", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> writeProblem(
                                request, response, json, HttpStatus.UNAUTHORIZED, "Sign in to continue."))
                        .accessDeniedHandler((request, response, error) -> {
                            String detail = error instanceof CsrfException
                                    ? "Refresh the security token and try again."
                                    : "Your account does not have permission to do this.";
                            writeProblem(request, response, json, HttpStatus.FORBIDDEN, detail);
                        }))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login
                        .loginProcessingUrl("/api/login")
                        .usernameParameter("email")
                        .successHandler((request, response, auth) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((request, response, error) -> writeProblem(
                                request, response, json, HttpStatus.UNAUTHORIZED,
                                "The email or password is incorrect.")))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler((request, response, auth) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .build();
    }

    private static void writeProblem(HttpServletRequest request, HttpServletResponse response,
            ObjectMapper json, HttpStatus status, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }

    @Bean
    UserDetailsService customers(JdbcClient database) {
        return email -> {
            String normalizedEmail = CustomerAccounts.normalizeEmail(email);
            return database.sql("SELECT password_hash, role FROM customers WHERE email = :email")
                    .param("email", normalizedEmail)
                    .query((row, index) -> User.withUsername(normalizedEmail)
                            .password(row.getString("password_hash"))
                            .roles(row.getString("role"))
                            .build())
                    .optional()
                    .orElseThrow(() -> new UsernameNotFoundException("Customer not found"));
        };
    }

    @Bean
    PasswordEncoder passwords() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
