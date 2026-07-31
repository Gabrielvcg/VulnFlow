package com.vulnflow.agent.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vulnflow.agent.shared.AgentObjectMapper;
import com.vulnflow.agent.target.ScanTarget;
import com.vulnflow.agent.target.TargetType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrivyImageScannerTest {

    @TempDir Path temporaryDirectory;

    @Test
    void buildsExactArgumentsAndTreatsMaliciousReferenceAsOneArgument() throws Exception {
        RecordingExecutor executor = RecordingExecutor.success("{\"Results\":[]}");
        TrivyImageScanner scanner = scanner(executor, 1024);
        ScanTarget target = new ScanTarget(
                "malicious-looking",
                TargetType.CONTAINER_IMAGE,
                "image:latest; touch /tmp/should-not-run");

        try (ScanArtifact artifact = scanner.scan(target)) {
            assertThat(executor.arguments).containsExactly(
                    "trivy",
                    "image",
                    "--scanners", "vuln",
                    "--format", "json",
                    "--output", artifact.path().toString(),
                    "image:latest; touch /tmp/should-not-run");
            assertThat(Files.exists(artifact.path())).isTrue();
        }
        assertThat(Files.list(temporaryDirectory)).isEmpty();
    }

    @Test
    void validatesExitCodeTimeoutEmptyJsonAndSizeLimit() {
        assertThatThrownBy(() -> scanner(RecordingExecutor.exit(7), 1024).scan(target()))
                .isInstanceOf(ScanException.class)
                .hasMessageContaining("exit code 7");
        assertThatThrownBy(() -> scanner(RecordingExecutor.timeout(), 1024).scan(target()))
                .isInstanceOf(ScanException.class)
                .hasMessageContaining("timed out");
        assertThatThrownBy(() -> scanner(RecordingExecutor.success(""), 1024).scan(target()))
                .isInstanceOf(ScanException.class)
                .hasMessageContaining("empty report");
        assertThatThrownBy(() -> scanner(RecordingExecutor.success("{\"padding\":\"123456789\"}"), 8)
                        .scan(target()))
                .isInstanceOf(ScanException.class)
                .hasMessageContaining("size limit");
        assertThatThrownBy(() -> scanner(RecordingExecutor.success("not-json"), 1024).scan(target()))
                .isInstanceOf(ScanException.class)
                .hasMessageContaining("could not be validated");
    }

    @Test
    void verifiesTrivyAvailability() {
        RecordingExecutor executor = new RecordingExecutor(
                new CommandResult(0, false, "Version: 1.2.3", ""), null);
        assertThat(scanner(executor, 1024).verifyAvailable()).isEqualTo("Version: 1.2.3");
        assertThat(executor.arguments).containsExactly("trivy", "--version");
    }

    @Test
    void executesAFakeTrivyScriptWithoutAShell() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path fakeTrivy = temporaryDirectory.resolve(windows ? "fake-trivy.cmd" : "fake-trivy");
        if (windows) {
            Files.writeString(fakeTrivy, """
                    @echo off
                    if "%~1"=="--version" (
                      echo fake-trivy 1.0
                      exit /b 0
                    )
                    :loop
                    if "%~1"=="" exit /b 2
                    if "%~1"=="--output" (
                      >"%~2" echo {"Results":[]}
                      exit /b 0
                    )
                    shift
                    goto loop
                    """);
        } else {
            Files.writeString(fakeTrivy, """
                    #!/bin/sh
                    if [ "$1" = "--version" ]; then
                      printf '%s\\n' 'fake-trivy 1.0'
                      exit 0
                    fi
                    while [ "$#" -gt 0 ]; do
                      if [ "$1" = "--output" ]; then
                        shift
                        printf '%s' '{"Results":[]}' > "$1"
                        exit 0
                      fi
                      shift
                    done
                    exit 2
                    """);
            Files.setPosixFilePermissions(
                    fakeTrivy,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        TrivyImageScanner scanner = new TrivyImageScanner(
                fakeTrivy,
                temporaryDirectory.resolve("reports"),
                Duration.ofSeconds(5),
                1024,
                new ProcessBuilderCommandExecutor(),
                AgentObjectMapper.create());

        assertThat(scanner.verifyAvailable()).contains("fake-trivy");
        try (ScanArtifact artifact = scanner.scan(target())) {
            assertThat(Files.readString(artifact.path()).trim()).isEqualTo("{\"Results\":[]}");
        }
    }

    private TrivyImageScanner scanner(CommandExecutor executor, long maxSize) {
        return new TrivyImageScanner(
                Path.of("trivy"),
                temporaryDirectory,
                Duration.ofSeconds(2),
                maxSize,
                executor,
                AgentObjectMapper.create());
    }

    private ScanTarget target() {
        return new ScanTarget("alpine", TargetType.CONTAINER_IMAGE, "alpine:3.15");
    }

    private static final class RecordingExecutor implements CommandExecutor {
        private final CommandResult result;
        private final String output;
        private List<String> arguments = new ArrayList<>();

        private RecordingExecutor(CommandResult result, String output) {
            this.result = result;
            this.output = output;
        }

        static RecordingExecutor success(String output) {
            return new RecordingExecutor(new CommandResult(0, false, "", ""), output);
        }

        static RecordingExecutor exit(int exitCode) {
            return new RecordingExecutor(new CommandResult(exitCode, false, "", "failed"), null);
        }

        static RecordingExecutor timeout() {
            return new RecordingExecutor(new CommandResult(-1, true, "", ""), null);
        }

        @Override
        public CommandResult execute(List<String> arguments, Duration timeout, int captureLimitBytes) {
            this.arguments = List.copyOf(arguments);
            int outputIndex = arguments.indexOf("--output");
            if (output != null && outputIndex >= 0) {
                try {
                    Files.writeString(Path.of(arguments.get(outputIndex + 1)), output);
                } catch (java.io.IOException exception) {
                    throw new RuntimeException(exception);
                }
            }
            return result;
        }
    }
}
