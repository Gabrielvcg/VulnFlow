package com.vulnflow.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigLoaderTest {

    @TempDir Path temporaryDirectory;

    @Test
    void loadsRequiredConfigurationAndTargets() throws Exception {
        Map<String, String> environment = validEnvironment(singleTarget());

        AgentConfig config = new AgentConfigLoader().load(environment);

        assertThat(config.apiUrl().toString()).isEqualTo("http://localhost:8080/");
        assertThat(config.apiKey()).isEqualTo("super-secret-value");
        assertThat(config.targets()).singleElement().satisfies(target ->
                assertThat(target.reference()).isEqualTo("alpine:3.15"));
    }

    @Test
    void rejectsMissingApiKeyAndInvalidIntervals() throws Exception {
        Map<String, String> missingKey = validEnvironment(singleTarget());
        missingKey.remove("VULNFLOW_API_KEY");
        assertThatThrownBy(() -> new AgentConfigLoader().load(missingKey))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("VULNFLOW_API_KEY");

        Map<String, String> invalidInterval = validEnvironment(singleTarget());
        invalidInterval.put("VULNFLOW_SCAN_INTERVAL", "0s");
        assertThatThrownBy(() -> new AgentConfigLoader().load(invalidInterval))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("positive duration");
    }

    @Test
    void rejectsEmptyAndDuplicateTargets() throws Exception {
        assertThatThrownBy(() -> new AgentConfigLoader().load(validEnvironment("targets: []\n")))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("At least one target");

        String duplicate = """
                targets:
                  - name: first
                    type: CONTAINER_IMAGE
                    reference: alpine:3.15
                  - name: second
                    type: CONTAINER_IMAGE
                    reference: alpine:3.15
                """;
        assertThatThrownBy(() -> new AgentConfigLoader().load(validEnvironment(duplicate)))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("Duplicate target");
    }

    @Test
    void rejectsBlankTargetReference() throws Exception {
        String blankReference = """
                targets:
                  - name: invalid
                    type: CONTAINER_IMAGE
                    reference: " "
                """;
        assertThatThrownBy(() -> new AgentConfigLoader().load(validEnvironment(blankReference)))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("reference");
    }

    private Map<String, String> validEnvironment(String yaml) throws Exception {
        Path targets = temporaryDirectory.resolve("targets-" + Math.abs(yaml.hashCode()) + ".yml");
        Files.writeString(targets, yaml);
        Map<String, String> environment = new HashMap<>();
        environment.put("VULNFLOW_API_URL", "http://localhost:8080");
        environment.put("VULNFLOW_API_KEY", "super-secret-value");
        environment.put("VULNFLOW_AGENT_ID", "test-agent");
        environment.put("VULNFLOW_TARGETS_FILE", targets.toString());
        environment.put("VULNFLOW_AGENT_DATA_DIR", temporaryDirectory.resolve("data").toString());
        environment.put("VULNFLOW_AGENT_TEMP_DIR", temporaryDirectory.resolve("temp").toString());
        return environment;
    }

    private String singleTarget() {
        return """
                targets:
                  - name: alpine
                    type: CONTAINER_IMAGE
                    reference: alpine:3.15
                """;
    }
}
