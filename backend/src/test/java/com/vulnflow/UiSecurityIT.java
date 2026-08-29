package com.vulnflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.ui.auth.UiRole;
import com.vulnflow.ui.auth.UiUser;
import com.vulnflow.ui.auth.UiUserRepository;
import com.vulnflow.ui.audit.UiAuditRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties={"vulnflow.security.api-key.value=test-api-key","vulnflow.worker.enabled=false","vulnflow.ui.enabled=true","vulnflow.ui.scans-enabled=false","server.servlet.session.cookie.secure=false"})
@AutoConfigureMockMvc @Testcontainers
class UiSecurityIT {
    private static final String PASSWORD="TemporaryPassword1A";
    @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16.4-alpine");
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired UiUserRepository users; @Autowired UiAuditRepository audit; @Autowired PasswordEncoder encoder;
    @BeforeEach void prepare(){audit.deleteAll();users.deleteAll();users.save(new UiUser("operator",encoder.encode(PASSWORD),UiRole.OPERATOR,false));}

    @Test void requiresCsrfForLogin() throws Exception {mvc.perform(post("/api/ui/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"operator\",\"password\":\""+PASSWORD+"\"}")).andExpect(status().isForbidden());}
    @Test void persistsAccountLockAfterFiveFailedLogins() throws Exception {SessionMaterial material=csrf();for(int attempt=0;attempt<5;attempt++){mvc.perform(post("/api/ui/v1/auth/login").cookie(material.cookie()).header("X-XSRF-TOKEN",material.token()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"operator\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());}assertThat(users.findByUsernameIgnoreCase("operator").orElseThrow().isLocked(java.time.Instant.now())).isTrue();}
    @Test void createsSessionAndSeparatesOperatorFromAdmin() throws Exception {
        SessionMaterial material = csrf();
        MvcResult login = mvc.perform(post("/api/ui/v1/auth/login")
                        .cookie(material.cookie())
                        .header("X-XSRF-TOKEN", material.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(java.util.Map.of(
                                "username", "operator", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andReturn();
        Cookie sessionCookie = login.getResponse().getCookie("VULNFLOW_SESSION");
        assertThat(sessionCookie).isNotNull();
        mvc.perform(get("/api/ui/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("operator"));
        mvc.perform(get("/api/ui/v1/admin/users").cookie(sessionCookie))
                .andExpect(status().isForbidden());
    }
    @Test void passwordChangeRefreshesTheExistingSessionPrincipal() throws Exception {
        users.deleteAll();
        users.save(new UiUser("first-access", encoder.encode(PASSWORD), UiRole.OPERATOR, true));
        SessionMaterial material = csrf();
        MvcResult login = mvc.perform(post("/api/ui/v1/auth/login")
                        .cookie(material.cookie())
                        .header("X-XSRF-TOKEN", material.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(java.util.Map.of(
                                "username", "first-access", "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true))
                .andReturn();
        Cookie sessionCookie = login.getResponse().getCookie("VULNFLOW_SESSION");
        assertThat(sessionCookie).isNotNull();

        mvc.perform(post("/api/ui/v1/auth/change-password")
                        .cookie(material.cookie(), sessionCookie)
                        .header("X-XSRF-TOKEN", material.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(java.util.Map.of(
                                "currentPassword", PASSWORD,
                                "newPassword", "PermanentPassword2B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordChangeRequired").value(false));

        mvc.perform(get("/api/ui/v1/targets").cookie(sessionCookie))
                .andExpect(status().isOk());
    }
    private SessionMaterial csrf() throws Exception {MvcResult result=mvc.perform(get("/api/ui/v1/auth/csrf")).andExpect(status().isOk()).andReturn();return new SessionMaterial(mapper.readTree(result.getResponse().getContentAsByteArray()).path("token").asText(),result.getResponse().getCookie("XSRF-TOKEN"));}
    private record SessionMaterial(String token,Cookie cookie){}
}
