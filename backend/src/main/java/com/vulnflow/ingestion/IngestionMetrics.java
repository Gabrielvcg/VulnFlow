package com.vulnflow.ingestion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class IngestionMetrics {

    private final MeterRegistry registry;
    private final Counter accepted;
    private final Counter completed;
    private final Counter retried;
    private final Counter deadLetter;
    private final Timer processingDuration;

    public IngestionMetrics(MeterRegistry registry, IngestionJobRepository repository) {
        this.registry = registry;
        this.accepted = registry.counter("vulnflow.ingestion.jobs.accepted");
        this.completed = registry.counter("vulnflow.ingestion.jobs.completed");
        this.retried = registry.counter("vulnflow.ingestion.jobs.retried");
        this.deadLetter = registry.counter("vulnflow.ingestion.jobs.dead_letter");
        this.processingDuration = registry.timer("vulnflow.ingestion.processing.duration");

        registerGauge(registry, repository, "vulnflow.ingestion.jobs.pending", IngestionJobStatus.PENDING);
        registerGauge(registry, repository, "vulnflow.ingestion.jobs.retry", IngestionJobStatus.RETRY_WAIT);
        registerGauge(registry, repository, "vulnflow.ingestion.jobs.dead_letter.current", IngestionJobStatus.DEAD_LETTER);
    }

    public void jobAccepted() { accepted.increment(); }
    public void jobCompleted() { completed.increment(); }
    public void jobRetried() { retried.increment(); }
    public void jobDeadLettered() { deadLetter.increment(); }
    public Timer.Sample startProcessing() { return Timer.start(registry); }
    public void stopProcessing(Timer.Sample sample) { sample.stop(processingDuration); }

    private void registerGauge(
            MeterRegistry registry,
            IngestionJobRepository repository,
            String name,
            IngestionJobStatus status) {
        Gauge.builder(name, repository, value -> value.countByStatus(status))
                .register(registry);
    }
}
