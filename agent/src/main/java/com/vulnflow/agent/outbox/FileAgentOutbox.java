package com.vulnflow.agent.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vulnflow.agent.client.UploadReceipt;
import com.vulnflow.agent.shared.AtomicFiles;
import com.vulnflow.agent.shared.Hashes;
import com.vulnflow.agent.target.ScanTarget;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class FileAgentOutbox implements AgentOutbox {

    private static final String REPORT_FILE = "report.json";
    private static final String METADATA_FILE = "metadata.json";
    private final Path itemsDirectory;
    private final long maxBytes;
    private final int maxItems;
    private final ObjectMapper objectMapper;
    private final Set<UUID> active = new HashSet<>();

    public FileAgentOutbox(Path dataDirectory, long maxBytes, int maxItems, ObjectMapper objectMapper) {
        this.itemsDirectory = dataDirectory.toAbsolutePath().normalize().resolve("outbox").resolve("items");
        this.maxBytes = maxBytes;
        this.maxItems = maxItems;
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(itemsDirectory);
            cleanupInterruptedWrites();
        } catch (IOException exception) {
            throw new IllegalStateException("Agent outbox directory could not be created", exception);
        }
    }

    @Override
    public synchronized OutboxItem enqueue(String agentId, ScanTarget target, Instant scannedAt, Path report) {
        try {
            long reportSize = Files.size(report);
            List<OutboxItem> existing = loadAll();
            long existingBytes = existing.stream().mapToLong(OutboxItem::sizeBytes).sum();
            if (existing.size() >= maxItems || reportSize > maxBytes - existingBytes) {
                throw new OutboxCapacityException("Agent outbox capacity has been reached");
            }
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            OutboxItem item = new OutboxItem(
                    id,
                    agentId,
                    target,
                    null,
                    scannedAt,
                    REPORT_FILE,
                    Hashes.sha256(report),
                    reportSize,
                    0,
                    now,
                    null,
                    OutboxStatus.PENDING,
                    now,
                    now,
                    null,
                    null,
                    null,
                    null);
            Path temporaryDirectory = itemsDirectory.resolve("." + id + ".tmp");
            Path finalDirectory = itemDirectory(id);
            Files.createDirectory(temporaryDirectory);
            try {
                Path copiedReport = temporaryDirectory.resolve(REPORT_FILE);
                Files.copy(report, copiedReport, StandardCopyOption.COPY_ATTRIBUTES);
                try (FileChannel channel = FileChannel.open(copiedReport, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                AtomicFiles.write(temporaryDirectory.resolve(METADATA_FILE), objectMapper.writeValueAsBytes(item));
                try {
                    Files.move(temporaryDirectory, finalDirectory, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw new IOException("The filesystem does not support atomic outbox creation", exception);
                }
            } finally {
                deleteDirectoryIfExists(temporaryDirectory);
            }
            return item;
        } catch (OutboxCapacityException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalStateException("Agent outbox item could not be stored", exception);
        }
    }

    @Override
    public synchronized List<OutboxItem> claimReady(Instant now, int limit) {
        List<OutboxItem> claimed = new ArrayList<>();
        for (OutboxItem item : loadAll().stream()
                .sorted(Comparator.comparing(OutboxItem::nextAttemptAt).thenComparing(OutboxItem::createdAt))
                .toList()) {
            if (claimed.size() >= limit) {
                break;
            }
            boolean available = item.status() == OutboxStatus.PENDING || item.status() == OutboxStatus.RETRY_WAIT;
            if (available && !item.nextAttemptAt().isAfter(now) && active.add(item.id())) {
                OutboxItem next = item.claimed(now);
                persist(next);
                claimed.add(next);
            }
        }
        return List.copyOf(claimed);
    }

    @Override
    public synchronized void assignAsset(UUID itemId, UUID assetId, Instant now) {
        mutate(itemId, item -> item.withAsset(assetId, now), false);
    }

    @Override
    public synchronized void markRetry(UUID itemId, Instant nextAttemptAt, String safeError, Instant now) {
        mutate(itemId, item -> item.retryAt(nextAttemptAt, safeError, now), true);
    }

    @Override
    public synchronized void markDeadLetter(UUID itemId, String safeError, Instant now) {
        mutate(itemId, item -> item.deadLetter(safeError, now), true);
    }

    @Override
    public synchronized void markUploaded(UUID itemId, UploadReceipt receipt, Instant now) {
        mutate(itemId, item -> item.uploaded(receipt, now), true);
    }

    @Override
    public synchronized Path reportPath(OutboxItem item) {
        Path directory = itemDirectory(item.id());
        Path path = directory.resolve(item.reportFile()).normalize();
        if (!path.startsWith(directory)) {
            throw new IllegalStateException("Outbox report path escaped the data directory");
        }
        return path;
    }

    @Override
    public synchronized List<OutboxItem> list() {
        return List.copyOf(loadAll());
    }

    @Override
    public synchronized OutboxStats stats() {
        List<OutboxItem> items = loadAll();
        return new OutboxStats(
                count(items, OutboxStatus.PENDING),
                count(items, OutboxStatus.RETRY_WAIT),
                count(items, OutboxStatus.UPLOADING),
                count(items, OutboxStatus.UPLOADED),
                count(items, OutboxStatus.DEAD_LETTER));
    }

    @Override
    public synchronized int recoverInterrupted(Instant now) {
        int recovered = 0;
        for (OutboxItem item : loadAll()) {
            if (item.status() == OutboxStatus.UPLOADING) {
                persist(item.recover(now));
                active.remove(item.id());
                recovered++;
            }
        }
        return recovered;
    }

    @Override
    public synchronized int cleanupUploadedBefore(Instant cutoff) {
        int deleted = 0;
        for (OutboxItem item : loadAll()) {
            if (item.status() == OutboxStatus.UPLOADED
                    && item.uploadedAt() != null
                    && item.uploadedAt().isBefore(cutoff)
                    && !active.contains(item.id())) {
                deleteDirectory(itemDirectory(item.id()));
                deleted++;
            }
        }
        return deleted;
    }

    private long count(List<OutboxItem> items, OutboxStatus status) {
        return items.stream().filter(item -> item.status() == status).count();
    }

    private void mutate(UUID itemId, java.util.function.UnaryOperator<OutboxItem> mutation, boolean release) {
        OutboxItem current = read(itemId);
        persist(mutation.apply(current));
        if (release) {
            active.remove(itemId);
        }
    }

    private List<OutboxItem> loadAll() {
        try (var directories = Files.list(itemsDirectory)) {
            List<OutboxItem> result = new ArrayList<>();
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                try {
                    UUID id = UUID.fromString(directory.getFileName().toString());
                    result.add(read(id));
                } catch (IllegalArgumentException ignoredTemporaryDirectory) {
                    // Temporary directories are ignored and removed by the writer that owns them.
                }
            }
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("Agent outbox could not be listed", exception);
        }
    }

    private void cleanupInterruptedWrites() throws IOException {
        try (var paths = Files.list(itemsDirectory)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith(".") || !name.endsWith(".tmp")) {
                    continue;
                }
                String candidate = name.substring(1, name.length() - ".tmp".length());
                try {
                    UUID.fromString(candidate);
                    deleteDirectoryIfExists(path);
                } catch (IllegalArgumentException ignoredUnownedDirectory) {
                    // Only directories created by enqueue are eligible for recovery cleanup.
                }
            }
        }
    }

    private OutboxItem read(UUID id) {
        try {
            return objectMapper.readValue(itemDirectory(id).resolve(METADATA_FILE).toFile(), OutboxItem.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Agent outbox metadata could not be read", exception);
        }
    }

    private void persist(OutboxItem item) {
        try {
            AtomicFiles.write(
                    itemDirectory(item.id()).resolve(METADATA_FILE),
                    objectMapper.writeValueAsBytes(item));
        } catch (IOException exception) {
            throw new IllegalStateException("Agent outbox metadata could not be updated", exception);
        }
    }

    private Path itemDirectory(UUID id) {
        Path directory = itemsDirectory.resolve(id.toString()).normalize();
        if (!directory.startsWith(itemsDirectory)) {
            throw new IllegalStateException("Outbox item path escaped the data directory");
        }
        return directory;
    }

    private void deleteDirectory(Path directory) {
        try {
            deleteDirectoryIfExists(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Retained outbox item could not be removed", exception);
        }
    }

    private void deleteDirectoryIfExists(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        if (!directory.toAbsolutePath().normalize().startsWith(itemsDirectory)) {
            throw new IOException("Refusing to delete outside the outbox directory");
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
