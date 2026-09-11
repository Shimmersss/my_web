package com.web.backen.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Same-directory replacement with fsync; failed writes are visible to callers and retention. */
@Component
public class AtomicTaskStore {
    private final ObjectMapper mapper;
    private final Set<Path> dirty = ConcurrentHashMap.newKeySet();
    public AtomicTaskStore(ObjectMapper mapper) { this.mapper = mapper; }
    public void write(Path target, Object snapshot) {
        synchronized (snapshot) {
            // Cancellation must not prevent recording its durable recovery state.
            boolean interrupted = Thread.interrupted();
            Path temporary = null;
            try {
                Files.createDirectories(target.toAbsolutePath().getParent());
                temporary = Files.createTempFile(target.toAbsolutePath().getParent(), "snapshot-", ".tmp");
                mapper.writeValue(temporary.toFile(), snapshot);
                try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
                replace(temporary, target);
                try (FileChannel directory = FileChannel.open(target.toAbsolutePath().getParent(), StandardOpenOption.READ)) {
                    directory.force(true);
                } catch (IOException | UnsupportedOperationException unsupported) { /* Directory fsync is OS dependent. */ }
                dirty.remove(target.toAbsolutePath().normalize());
            } catch (IOException e) {
                dirty.add(target.toAbsolutePath().normalize());
                throw new UncheckedIOException("任务快照保存失败", e);
            } finally {
                if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
    protected void replace(Path temporary, Path target) throws IOException {
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    public boolean isDirty(Path path) { return dirty.contains(path.toAbsolutePath().normalize()); }
}
