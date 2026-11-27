package com.smartlaundromat.business;

import java.util.Locale;
import java.util.Optional;

import com.smartlaundromat.business.ApplicationException.Reason;
import com.smartlaundromat.model.AccountCredentials;
import com.smartlaundromat.dataaccess.AccountRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;

    public AccountService(AccountRepository accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public void register(String name, String email, String password) {
        boolean created = accounts.createCustomer(name.strip(), normalizeEmail(email), passwords.encode(password));
        if (!created) {
            throw new ApplicationException(Reason.CONFLICT, "An account with that email already exists.");
        }
    }

    public Optional<AccountCredentials> findCredentials(String email) {
        return accounts.findCredentials(normalizeEmail(email));
    }

    private static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
