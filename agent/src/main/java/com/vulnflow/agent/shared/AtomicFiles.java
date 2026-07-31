package com.vulnflow.agent.shared;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

public final class AtomicFiles {

    private AtomicFiles() {
    }

    public static void write(Path destination, byte[] content) throws IOException {
        Path parent = destination.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Atomic file destination has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        destination,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("The filesystem does not support atomic metadata replacement", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
