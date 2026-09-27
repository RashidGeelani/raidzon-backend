package com.raidzon.account.service;

import com.raidzon.account.dto.AccountDashboard;
import com.raidzon.account.repository.AccountRepository;
import com.raidzon.identity.dto.AuthIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service @Profile("postgres")
public class AccountService {
    private final AccountRepository accounts;
    public AccountService(AccountRepository accounts) { this.accounts=accounts; }
    public AccountDashboard dashboard(AuthIdentity identity) { return accounts.dashboard(identity.accountId()); }
    public void edit(AuthIdentity identity, String token, com.raidzon.account.dto.EditPlayerProfile input) {
        String name = input.name() == null ? "" : input.name().strip();
        if (name.isBlank() || name.length() > 80 || name.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Enter a name between 1 and 80 characters without control characters.");
        if (input.verificationToken() == null || input.verificationToken().equals(token))
            throw new com.raidzon.identity.service.AuthFailure(403,"VERIFICATION_REQUIRED","Verify your phone again before saving.");
        accounts.edit(identity, name, input.verificationToken());
    }
}
