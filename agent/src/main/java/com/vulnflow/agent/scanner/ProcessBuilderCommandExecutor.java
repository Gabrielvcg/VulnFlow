package com.vulnflow.agent.scanner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ProcessBuilderCommandExecutor implements CommandExecutor {

    @Override
    public CommandResult execute(List<String> arguments, Duration timeout, int captureLimitBytes) {
        Process process = null;
        ExecutorService collectors = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "trivy-output-collector");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Process startedProcess = new ProcessBuilder(List.copyOf(arguments)).start();
            process = startedProcess;
            Future<String> stdout = collectors.submit(
                    () -> collect(startedProcess.getInputStream(), captureLimitBytes));
            Future<String> stderr = collectors.submit(
                    () -> collect(startedProcess.getErrorStream(), captureLimitBytes));
            boolean finished = startedProcess.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                startedProcess.destroy();
                if (!startedProcess.waitFor(2, TimeUnit.SECONDS)) {
                    startedProcess.destroyForcibly();
                    startedProcess.waitFor(2, TimeUnit.SECONDS);
                }
            }
            int exitCode = finished ? startedProcess.exitValue() : -1;
            return new CommandResult(exitCode, !finished, get(stdout), get(stderr));
        } catch (IOException exception) {
            throw new ScanException("Trivy could not be started", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new ScanException("Trivy execution was interrupted", exception);
        } finally {
            collectors.shutdownNow();
        }
    }

    private String collect(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0 && captured.size() < limit) {
                int retained = Math.min(read, limit - captured.size());
                captured.write(buffer, 0, retained);
            }
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private String get(Future<String> output) {
        try {
            return output.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            return "";
        }
    }
}
