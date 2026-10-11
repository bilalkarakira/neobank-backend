package com.bilal.neobank.config;

import com.bilal.neobank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The demo profile (Docker stack, run-locally guide) also accepts http://127.0.0.1:4200, the address the docs tell people to open. A browser on that address got a 403 on login before. */
@SpringBootTest(properties = "app.demo.seed-on-startup=false")
@ActiveProfiles("demo")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoCorsTest {

    @Autowired
    MockMvc mockMvc;

    /** A login with wrong credentials: a browser sends the Origin header, and CORS is decided before the credentials are looked at. */
    private ResultActions loginFrom(String origin) throws Exception {
        return mockMvc.perform(post("/api/auth/login").header("Origin", origin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"WrongPassw0rd1\"}"));
    }

    private ResultActions preflightFrom(String origin) throws Exception {
        return mockMvc.perform(options("/api/auth/login").header("Origin", origin).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"));
    }

    @Test
    void bothSpellingsOfTheLocalWebAppAreAllowed() throws Exception {
        for (String origin : new String[] {"http://localhost:4200", "http://127.0.0.1:4200"}) {
            loginFrom(origin).andExpect(status().isUnauthorized()); // wrong credentials, but not blocked
            preflightFrom(origin).andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }
    }

    @Test
    void anythingElseIsStillBlocked() throws Exception {
        for (String origin : new String[] {"http://127.0.0.1:4201", "http://127.0.0.1", "https://127.0.0.1:4200", "http://evil.example", "http://localhost.evil.example:4200", "null"}) {
            loginFrom(origin).andExpect(status().isForbidden());
            preflightFrom(origin).andExpect(status().isForbidden());
        }
    }
}
