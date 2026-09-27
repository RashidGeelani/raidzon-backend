package com.raidzon.identity.controller;

import com.raidzon.identity.service.AuthService;
import com.raidzon.identity.service.SmsSender;
import com.raidzon.identity.service.Msg91SmsSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.Clock;

@Configuration
@Profile("postgres")
public class AuthConfiguration {
    @Bean @ConditionalOnMissingBean(SmsSender.class)
    SmsSender smsSender(@Value("${raidzon.sms.provider:disabled}") String provider,
                        @Value("${raidzon.sms.msg91.auth-key:}") String key,
                        @Value("${raidzon.sms.msg91.template-id:}") String template,
                        ObjectMapper json) {
        if (provider.equals("msg91")) return new Msg91SmsSender(key, template, json);
        if (!provider.equals("disabled")) throw new IllegalStateException("Unknown SMS provider.");
        return new SmsSender() {
        public boolean available() { return false; }
        public void sendCode(String phone,String code) { throw new IllegalStateException("SMS provider is not configured."); }
    }; }
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean @DependsOn("flyway")
    AuthService authService(DataSource data, SmsSender sms, Clock clock, @Value("${raidzon.auth.pepper:}") String pepper) {
        return new AuthService(new JdbcTemplate(data),new TransactionTemplate(new DataSourceTransactionManager(data)),sms,clock,pepper);
    }
}
