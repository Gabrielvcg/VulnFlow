package com.vulnflow.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "vulnflow.security.api-key")
public record ApiKeyProperties(@NotBlank String value) {
}
