package com.vulnflow.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vulnflow.security.ApiKeyAuthenticationEntryPoint;
import com.vulnflow.security.ApiKeyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(
        controllers = DisabledApiDocsWebTest.TestController.class,
        properties = {
            "springdoc.api-docs.enabled=false",
            "vulnflow.security.api-key.value=test-api-key"
        })
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, ApiKeyAuthenticationEntryPoint.class})
@EnableConfigurationProperties(ApiKeyProperties.class)
class DisabledApiDocsWebTest {

    @Autowired MockMvc mockMvc;

    @Test
    void disabledApiDocsReturnsControlledNotFoundResponse() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/v3/api-docs"));
    }

    @RestController
    static class TestController {

        @GetMapping("/__test")
        String testEndpoint() {
            return "ok";
        }
    }
}
