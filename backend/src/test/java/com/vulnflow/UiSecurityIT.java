package com.vulnflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.asset.Asset;
import com.vulnflow.asset.AssetRepository;
import com.vulnflow.asset.AssetType;
import com.vulnflow.finding.Finding;
import com.vulnflow.finding.FindingRepository;
import com.vulnflow.finding.FindingSeverity;
import com.vulnflow.scan.Scan;
import com.vulnflow.scan.ScanRepository;
import com.vulnflow.scan.ScannerType;
import com.vulnflow.ui.auth.UiRole;
import com.vulnflow.ui.auth.UiUser;
import com.vulnflow.ui.auth.UiUserRepository;
import com.vulnflow.ui.audit.UiAuditRepository;
import com.vulnflow.ui.scan.UiScanRequest;
import com.vulnflow.ui.scan.UiScanRequestRepository;
import com.vulnflow.ui.target.UiTarget;
import com.vulnflow.ui.target.UiTargetRepository;
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
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired UiUserRepository users; @Autowired UiAuditRepository audit; @Autowired UiScanRequestRepository requests; @Autowired UiTargetRepository targets; @Autowired PasswordEncoder encoder;
    @Autowired AssetRepository assets; @Autowired ScanRepository scans; @Autowired FindingRepository findings;
    @BeforeEach void prepare(){requests.deleteAll();targets.deleteAll();findings.deleteAll();scans.deleteAll();assets.deleteAll();audit.deleteAll();users.deleteAll();users.save(new UiUser("operator",encoder.encode(PASSWORD),UiRole.OPERATOR,false));}

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
    @Test void listsResultsWithTheirLazyAssetAfterTheRepositoryCallCompletes() throws Exception {
        Asset asset = assets.save(new Asset("public-nginx", AssetType.CONTAINER_IMAGE, "nginx:stable"));
        Scan scan = new Scan(asset, ScannerType.TRIVY, "nginx.json", "a".repeat(64));
        scan.markCompleted("test");
        scans.save(scan);

        mvc.perform(get("/api/ui/v1/results").cookie(login("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(scan.getId().toString()))
                .andExpect(jsonPath("$.content[0].assetName").value("public-nginx"))
                .andExpect(jsonPath("$.content[0].reference").value("nginx:stable"));
    }
    @Test void registeringAndChangingAnImageKeepsTheTargetLinkedToTheMatchingAsset() throws Exception {
        users.save(new UiUser("admin", encoder.encode(PASSWORD), UiRole.ADMIN, false));
        Cookie admin = login("admin");
        SessionMaterial material = csrf();
        MvcResult created = mvc.perform(post("/api/ui/v1/admin/targets")
                        .cookie(admin, material.cookie()).header("X-XSRF-TOKEN", material.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alpine\",\"reference\":\"alpine:3.20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").isNotEmpty())
                .andReturn();
        var targetId = mapper.readTree(created.getResponse().getContentAsByteArray()).path("id").asText();
        var firstAssetId = mapper.readTree(created.getResponse().getContentAsByteArray()).path("assetId").asText();

        MvcResult changed = mvc.perform(patch("/api/ui/v1/admin/targets")
                        .cookie(admin, material.cookie()).header("X-XSRF-TOKEN", material.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\""+targetId+"\",\"name\":\"Alpine current\",\"reference\":\"alpine:3.21\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").isNotEmpty())
                .andExpect(jsonPath("$.assetId").value(org.hamcrest.Matchers.not(firstAssetId)))
                .andReturn();

        UiTarget updated = targets.findById(java.util.UUID.fromString(targetId)).orElseThrow();
        assertThat(updated.getAsset()).isNotNull();
        var changedAssetId = mapper.readTree(changed.getResponse().getContentAsByteArray()).path("assetId").asText();
        assertThat(assets.findById(java.util.UUID.fromString(changedAssetId)).orElseThrow().getExternalReference())
                .isEqualTo("alpine:3.21");
    }

    @Test void searchesLocalFindingsInTheDatabaseInsteadOfLoadingTheWholeResult() throws Exception {
        Asset asset = assets.save(new Asset("alpine", AssetType.CONTAINER_IMAGE, "alpine:3.20"));
        Scan scan = scans.save(new Scan(asset, ScannerType.TRIVY, "alpine.json", "b".repeat(64)));
        findings.save(new Finding(scan, asset, "CVE-2026-1234", "openssl", "1", "2", FindingSeverity.HIGH,
                "Matching title", "description", false, 80));
        findings.save(new Finding(scan, asset, "CVE-2026-9999", "zlib", "1", null, FindingSeverity.LOW,
                "Other title", "description", false, 20));

        mvc.perform(get("/api/ui/v1/results/{id}/findings", scan.getId())
                        .queryParam("query", "openssl").queryParam("severity", "HIGH").cookie(login("operator")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalExact").value(true))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].vulnerabilityId").value("CVE-2026-1234"));
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
    @Test void operatorHistoryIsScopedWhileAdminRetainsGlobalVisibility() throws Exception {
        UiUser operator = users.findByUsernameIgnoreCase("operator").orElseThrow();
        UiUser other = users.save(new UiUser("other",encoder.encode(PASSWORD),UiRole.OPERATOR,false));
        UiUser admin = users.save(new UiUser("admin",encoder.encode(PASSWORD),UiRole.ADMIN,false));
        Asset targetAsset = assets.save(new Asset("Alpine", AssetType.CONTAINER_IMAGE, "alpine:3.20"));
        UiTarget target = targets.save(new UiTarget("Alpine", "alpine:3.20", targetAsset, admin));
        requests.save(new UiScanRequest(target, operator));
        requests.save(new UiScanRequest(target, other));

        Cookie operatorSession = login("operator");
        mvc.perform(get("/api/ui/v1/scan-requests").cookie(operatorSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].requestedBy").value("operator"));

        Cookie adminSession = login("admin");
        mvc.perform(get("/api/ui/v1/scan-requests").cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }
    @Test void disablingAnAccountRevokesAllExistingSessions() throws Exception {
        assertAdminChangeRevokesSessions(false, false);
    }

    @Test void rotatingCredentialsRevokesAllExistingSessions() throws Exception {
        assertAdminChangeRevokesSessions(true, true);
    }

    private void assertAdminChangeRevokesSessions(boolean enabled, boolean rotate) throws Exception {
        users.save(new UiUser("admin", encoder.encode(PASSWORD), UiRole.ADMIN, false));
        Cookie first = login("operator");
        Cookie second = login("operator");
        Cookie admin = login("admin");
        SessionMaterial material = csrf();
        mvc.perform(patch("/api/ui/v1/admin/users").cookie(admin, material.cookie())
                        .header("X-XSRF-TOKEN", material.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(java.util.Map.of(
                                "id", users.findByUsernameIgnoreCase("operator").orElseThrow().getId(),
                                "enabled", enabled, "rotatePassword", rotate))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ui/v1/auth/me").cookie(first)).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/ui/v1/auth/me").cookie(second)).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/ui/v1/admin/users").cookie(admin)).andExpect(status().isOk());
    }

    @Test void changingPasswordRevokesOtherSessionsButKeepsCurrentSession() throws Exception {
        Cookie current = login("operator");
        Cookie other = login("operator");
        SessionMaterial material = csrf();
        mvc.perform(post("/api/ui/v1/auth/change-password").cookie(current, material.cookie())
                        .header("X-XSRF-TOKEN", material.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(java.util.Map.of(
                                "currentPassword", PASSWORD, "newPassword", "PermanentPassword2B"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ui/v1/auth/me").cookie(current)).andExpect(status().isOk());
        mvc.perform(get("/api/ui/v1/auth/me").cookie(other)).andExpect(status().is4xxClientError());
    }
    private SessionMaterial csrf() throws Exception {MvcResult result=mvc.perform(get("/api/ui/v1/auth/csrf")).andExpect(status().isOk()).andReturn();return new SessionMaterial(mapper.readTree(result.getResponse().getContentAsByteArray()).path("token").asText(),result.getResponse().getCookie("XSRF-TOKEN"));}
    private Cookie login(String username) throws Exception {SessionMaterial material=csrf();return mvc.perform(post("/api/ui/v1/auth/login").cookie(material.cookie()).header("X-XSRF-TOKEN",material.token()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(java.util.Map.of("username",username,"password",PASSWORD)))).andExpect(status().isOk()).andReturn().getResponse().getCookie("VULNFLOW_SESSION");}
    private record SessionMaterial(String token,Cookie cookie){}
}
