package com.vulnflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.agent.client.VulnFlowHttpClient;
import com.vulnflow.agent.config.AgentConfig;
import com.vulnflow.agent.config.AgentConfigLoader;
import com.vulnflow.agent.config.AgentConfigurationException;
import com.vulnflow.agent.outbox.FileAgentOutbox;
import com.vulnflow.agent.scanner.ProcessBuilderCommandExecutor;
import com.vulnflow.agent.scanner.TrivyImageScanner;
import com.vulnflow.agent.scheduling.AgentScheduler;
import com.vulnflow.agent.scheduling.AgentStateStore;
import com.vulnflow.agent.scheduling.AgentStatus;
import com.vulnflow.agent.scheduling.AssetCache;
import com.vulnflow.agent.scheduling.ScanCoordinator;
import com.vulnflow.agent.scheduling.UploadCoordinator;
import com.vulnflow.agent.scheduling.CommandCoordinator;
import com.vulnflow.agent.shared.AgentObjectMapper;
import com.vulnflow.agent.target.ConfiguredTargetRegistry;
import java.io.PrintStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public final class AgentApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentApplication.class);

    private AgentApplication() {
    }

    public static void main(String[] args) {
        int exitCode = run(args, System.getenv(), System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, Map<String, String> environment, PrintStream stdout, PrintStream stderr) {
        try {
            Mode mode = parseMode(args);
            AgentConfig config = new AgentConfigLoader().load(environment);
            ObjectMapper objectMapper = AgentObjectMapper.create();
            if (mode == Mode.CHECK) {
                stdout.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config.safeView()));
                return 0;
            }
            MDC.put("agentId", config.agentId());
            FileAgentOutbox outbox = new FileAgentOutbox(
                    config.dataDirectory(),
                    config.maxOutboxBytes(),
                    config.maxOutboxItems(),
                    objectMapper);
            AgentStateStore stateStore = new AgentStateStore(config.dataDirectory(), objectMapper);
            if (mode == Mode.STATUS) {
                stdout.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        AgentStatus.from(stateStore.current(), outbox.stats())));
                return 0;
            }
            TrivyImageScanner scanner = new TrivyImageScanner(
                    config.trivyPath(),
                    config.temporaryDirectory(),
                    config.trivyTimeout(),
                    config.maxReportBytes(),
                    new ProcessBuilderCommandExecutor(),
                    objectMapper);
            String version = scanner.verifyAvailable();
            LOGGER.info("event=trivy_verified agentId={} result=available version={}", config.agentId(), version);
            int recovered = outbox.recoverInterrupted(Instant.now());
            if (recovered > 0) {
                LOGGER.warn("event=outbox_recovered agentId={} recovered={} result=retry_wait", config.agentId(), recovered);
            }
            AssetCache assetCache = new AssetCache(config.dataDirectory(), objectMapper);
            VulnFlowHttpClient client = new VulnFlowHttpClient(
                    config.apiUrl(),
                    config.apiKey(),
                    config.httpConnectTimeout(),
                    config.httpRequestTimeout(),
                    objectMapper);
            ExecutorService scanExecutor = Executors.newFixedThreadPool(
                    config.maxConcurrentScans(),
                    runnable -> namedThread(runnable, "vulnflow-scan"));
            ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(
                    4,
                    runnable -> namedThread(runnable, "vulnflow-cycle"));
            ScanCoordinator scanCoordinator = new ScanCoordinator(
                    config.agentId(),
                    new ConfiguredTargetRegistry(config.targets()),
                    scanner,
                    outbox,
                    stateStore,
                    scanExecutor);
            UploadCoordinator uploadCoordinator = new UploadCoordinator(
                    config.agentId(),
                    outbox,
                    assetCache,
                    client,
                    stateStore,
                    config.uploadRetryInterval());
            CommandCoordinator commandCoordinator = new CommandCoordinator(config.agentId(), config.commandsEnabled(),
                    config.dataDirectory(), client, scanner, outbox, scanExecutor);
            AgentScheduler scheduler = new AgentScheduler(
                    config.agentId(),
                    scanCoordinator,
                    uploadCoordinator,
                    outbox,
                    scheduledExecutor,
                    scanExecutor,
                    config.scanInterval(),
                    config.uploadRetryInterval(),
                    config.uploadedRetention(),
                    config.shutdownTimeout(),
                    commandCoordinator,
                    config.commandPollInterval());
            if (mode == Mode.ONCE) {
                try (scheduler) {
                    scheduler.runOnce();
                }
                stdout.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        AgentStatus.from(stateStore.current(), outbox.stats())));
                return 0;
            }
            runContinuously(scheduler);
            return 0;
        } catch (AgentConfigurationException exception) {
            stderr.println("Agent configuration is invalid: " + exception.getMessage());
            return 2;
        } catch (RuntimeException | java.io.IOException exception) {
            stderr.println("Agent startup failed: " + exception.getClass().getSimpleName());
            return 1;
        } finally {
            MDC.clear();
        }
    }

    private static void runContinuously(AgentScheduler scheduler) {
        CountDownLatch stopped = new CountDownLatch(1);
        Thread shutdownHook = new Thread(() -> {
            scheduler.close();
            stopped.countDown();
        }, "vulnflow-agent-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        scheduler.start();
        try {
            stopped.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            scheduler.close();
        }
    }

    private static Thread namedThread(Runnable runnable, String prefix) {
        Thread thread = new Thread(runnable, prefix + "-" + UUIDHolder.next());
        thread.setDaemon(false);
        return thread;
    }

    private static Mode parseMode(String[] args) {
        Set<String> supported = Set.of("--once", "--check", "--status");
        if (args.length > 1 || (args.length == 1 && !supported.contains(args[0]))) {
            throw new AgentConfigurationException("Use no option, --once, --check, or --status");
        }
        if (args.length == 0) {
            return Mode.DAEMON;
        }
        return switch (args[0]) {
            case "--once" -> Mode.ONCE;
            case "--check" -> Mode.CHECK;
            case "--status" -> Mode.STATUS;
            default -> throw new AgentConfigurationException("Unsupported option: " + Arrays.toString(args));
        };
    }

    private enum Mode {
        DAEMON,
        ONCE,
        CHECK,
        STATUS
    }

    private static final class UUIDHolder {
        private static final java.util.concurrent.atomic.AtomicLong NEXT = new java.util.concurrent.atomic.AtomicLong();

        private UUIDHolder() {
        }

        static long next() {
            return NEXT.incrementAndGet();
        }
    }
}
