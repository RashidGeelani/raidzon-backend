package com.raidzon.match.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.raidzon.match.dto.MatchJsonCodec;
import com.raidzon.match.service.MatchService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("postgres")
public class PostgresConfiguration {
    @Bean(destroyMethod = "close")
    HikariDataSource dataSource(
        @Value("${raidzon.database.url}") String url,
        @Value("${raidzon.database.username}") String username,
        @Value("${raidzon.database.password}") String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(60_000);
        config.setMaxLifetime(300_000);
        return new HikariDataSource(config);
    }
    @Bean(initMethod = "migrate") Flyway flyway(HikariDataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(true).load();
    }
    @Bean MatchJsonCodec matchJsonCodec(ObjectMapper json) { return new MatchJsonCodec(json); }
    @Bean @DependsOn("flyway")
    MatchRepository matchRepository(HikariDataSource dataSource, MatchJsonCodec codec) {
        return new MatchRepository(new JdbcTemplate(dataSource), codec);
    }
    @Bean MatchService matchService(MatchRepository repository, MatchJsonCodec codec, HikariDataSource dataSource) {
        return new MatchService(repository, codec, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }
    @Bean com.raidzon.match.service.GuestClaimService guestClaimService(MatchService service,HikariDataSource data){
        return new com.raidzon.match.service.GuestClaimService(new JdbcTemplate(data),new TransactionTemplate(new DataSourceTransactionManager(data)),service);
    }
}
