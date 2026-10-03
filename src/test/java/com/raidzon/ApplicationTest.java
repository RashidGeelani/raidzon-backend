package com.raidzon;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// application.yml activates the postgres profile for deployments; this smoke test runs without a database.
@SpringBootTest(properties = "spring.profiles.active=default")
@AutoConfigureMockMvc
class ApplicationTest {
    @Autowired private MockMvc client;

    @Test
    void exposesHealthWithoutInventingSyncEndpoint() throws Exception {
        client.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        client.perform(post("/api/matches/example/events")).andExpect(status().isNotFound());
    }
}
