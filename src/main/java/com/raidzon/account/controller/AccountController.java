package com.raidzon.account.controller;

import com.raidzon.account.dto.AccountDashboard;
import com.raidzon.account.service.AccountService;
import com.raidzon.identity.dto.AuthIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("postgres") @RequestMapping("/api/v1/account")
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts=accounts; }
    @GetMapping("/dashboard")
    public AccountDashboard dashboard(@RequestAttribute("identity") AuthIdentity identity) {
        return accounts.dashboard(identity);
    }
    @PostMapping("/player-profile")
    public AccountDashboard edit(@RequestAttribute("identity") AuthIdentity identity,
                                 @RequestAttribute("authToken") String token,
                                 @RequestBody com.raidzon.account.dto.EditPlayerProfile input) {
        accounts.edit(identity, token, input);
        return accounts.dashboard(identity);
    }
}
