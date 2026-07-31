package com.vulnflow.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileReportStorageTest {

    @TempDir
    Path directory;

    @Test
    void storesAtomicallyWithoutLeavingTemporaryFiles() throws Exception {
        LocalFileReportStorage storage = storage();
        byte[] content = "{\"Results\":[]}".getBytes(StandardCharsets.UTF_8);

        String key = storage.store(UUID.randomUUID(), content);

        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.load(key)).isEqualTo(content);
        try (var files = Files.walk(directory)) {
            assertThat(files.filter(Files::isRegularFile).map(path -> path.getFileName().toString()))
                    .allMatch(name -> name.endsWith(".json"));
        }
    }

    @Test
    void rejectsKeysThatEscapeTheConfiguredDirectory() {
        LocalFileReportStorage storage = storage();

        assertThatThrownBy(() -> storage.load("../outside.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private LocalFileReportStorage storage() {
        LocalFileReportStorage storage = new LocalFileReportStorage(new ReportStorageProperties(directory));
        storage.initialize();
        return storage;
    }
}
