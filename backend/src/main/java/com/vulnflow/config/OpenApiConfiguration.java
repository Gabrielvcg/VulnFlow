package com.vulnflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI vulnFlowOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        "apiKey",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"))
                .info(new Info()
                        .title("VulnFlow API")
                        .version("0.4.2")
                        .description("Local-first API with persistent asynchronous vulnerability report ingestion."));
    }
}
