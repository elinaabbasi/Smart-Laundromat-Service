package com.smartlaundromat;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/styles.css", "/app.js").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/register", "/api/login").permitAll()
                        .requestMatchers("/api/reservations").hasRole("CUSTOMER")
                        .requestMatchers("/api/admin", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new HttpStatusEntryPoint(UNAUTHORIZED)))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login
                        .loginProcessingUrl("/api/login")
                        .usernameParameter("email")
                        .successHandler((request, response, auth) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((request, response, error) ->
                                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .logoutSuccessHandler((request, response, auth) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .build();
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
