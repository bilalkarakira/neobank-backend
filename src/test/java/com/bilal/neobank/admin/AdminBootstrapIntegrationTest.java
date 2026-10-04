package com.bilal.neobank.admin;

import com.bilal.neobank.TestcontainersConfiguration;
import com.bilal.neobank.user.Role;
import com.bilal.neobank.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole path from configuration to a working admin session, with the real application context. */
@SpringBootTest(properties = { "app.admin.email=Bootstrap.Admin@Example.com", "app.admin.password=AdminPassw0rd!" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminBootstrapIntegrationTest {

    @Autowired
    UserRepository users;

    @Autowired
    MockMvc mockMvc;

    @Test
    void theConfiguredAdministratorCanLogInAndUseTheAdminApi() throws Exception {
        var admin = users.findByEmail("bootstrap.admin@example.com").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);

        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"bootstrap.admin@example.com","password":"AdminPassw0rd!"}
                """)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = "Bearer " + json.replaceAll(".*\"accessToken\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/admin/overview").header("Authorization", token)).andExpect(status().isOk());
    }
}
