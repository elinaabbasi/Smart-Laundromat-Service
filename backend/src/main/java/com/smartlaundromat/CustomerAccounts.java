package com.smartlaundromat;

import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class CustomerAccounts {
    private final JdbcClient database;
    private final PasswordEncoder passwords;

    CustomerAccounts(JdbcClient database, PasswordEncoder passwords) {
        this.database = database;
        this.passwords = passwords;
    }

    @GetMapping("/csrf")
    public CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @GetMapping("/session")
    public AccountRole session(HttpServletRequest request) {
        return new AccountRole(request.isUserInRole("ADMIN") ? "ADMIN" : "CUSTOMER");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody Registration registration) {
        int inserted = database.sql("""
                INSERT INTO customers (name, email, password_hash, role)
                VALUES (:name, :email, :password, 'CUSTOMER')
                ON CONFLICT (email) DO NOTHING
                """)
                .param("name", registration.name().strip())
                .param("email", normalizeEmail(registration.email()))
                .param("password", passwords.encode(registration.password()))
                .update();

        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with that email already exists.");
        }
    }

    static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void invalidRegistration() {
        // Validation exceptions can contain the submitted password.
    }

    public record Registration(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 128) String password
    ) {}

    public record AccountRole(String role) {}
}
