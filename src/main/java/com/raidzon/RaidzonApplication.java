package com.raidzon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
public class RaidzonApplication {
    public static void main(String[] args) {
        SpringApplication.run(RaidzonApplication.class, args);
        System.out.println("kabad kabad .........................");
    }
}
