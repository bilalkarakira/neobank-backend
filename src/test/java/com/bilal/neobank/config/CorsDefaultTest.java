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

/** Which web app origins may call the API with the default settings: only the Angular dev server, at http://localhost:4200. */
@SpringBootTest(properties = "app.demo.seed-on-startup=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CorsDefaultTest {

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
    void theAngularDevServerOriginIsAllowed() throws Exception {
        loginFrom("http://localhost:4200").andExpect(status().isUnauthorized()); // wrong credentials, but not blocked
        preflightFrom("http://localhost:4200").andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void anyOtherOriginIsBlockedEvenTheLoopbackAddress() throws Exception {
        for (String origin : new String[] {"http://127.0.0.1:4200", "http://localhost:4201", "https://localhost:4200", "http://evil.example", "null"}) {
            loginFrom(origin).andExpect(status().isForbidden());
            preflightFrom(origin).andExpect(status().isForbidden());
        }
    }

    @Test
    void aRequestWithoutAnOriginIsNotAffected() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"WrongPassw0rd1\"}")).andExpect(status().isUnauthorized());
    }
}
