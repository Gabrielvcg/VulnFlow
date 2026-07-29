package com.vulnflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI vulnFlowOpenApi() {
        return new OpenAPI().info(new Info()
                .title("VulnFlow API")
                .version("0.1.0")
                .description("Local-first API for ingesting and querying vulnerability scan results."));
    }
}

