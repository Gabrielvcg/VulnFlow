package com.vulnflow.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentApplicationTest {

    @TempDir Path temporaryDirectory;

    @Test
    void checkPrintsEffectiveConfigurationWithoutTheApiKey() throws Exception {
        Path targets = temporaryDirectory.resolve("targets.yml");
        Files.writeString(targets, """
                targets:
                  - name: alpine
                    type: CONTAINER_IMAGE
                    reference: alpine:3.15
                """);
        Map<String, String> environment = new HashMap<>();
        environment.put("VULNFLOW_API_URL", "http://localhost:8080");
        environment.put("VULNFLOW_API_KEY", "must-never-be-printed");
        environment.put("VULNFLOW_AGENT_ID", "check-agent");
        environment.put("VULNFLOW_TARGETS_FILE", targets.toString());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = AgentApplication.run(
                new String[] {"--check"},
                environment,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        String printed = output.toString(StandardCharsets.UTF_8);
        assertThat(exitCode).isZero();
        assertThat(printed).contains("\"apiKeyConfigured\" : true");
        assertThat(printed).doesNotContain("must-never-be-printed");
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
