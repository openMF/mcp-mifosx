/** Copyright since 2026 Mifos Initiative. Mozilla Public License, v. 2.0. */
package org.mifos.community.copilot.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Single-process durable JSONL journal. A failed append poisons the writer: uncertain
 * persistence must never grant a new write. Restart requires a complete readable journal.
 * Pending approvals themselves remain ephemeral and are never restored from this file. */
public final class FileActionAudit implements ActionAudit, AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Entry> latest = new HashMap<>();
    private final Path path;
    private FileChannel channel;
    private FileLock lock;
    private boolean unavailable;

    public FileActionAudit(Path path) { this.path = path.toAbsolutePath(); }

    @Override public synchronized void create(Entry entry) {
        open();
        if (entry.state() != State.proposed || latest.containsKey(entry.correlationId())) { throw unavailable(); }
        append(entry);
    }

    @Override public synchronized void transition(String id, State state, Outcome outcome) {
        open();
        Entry old = latest.get(id);
        if (old == null) { throw unavailable(); }
        // Identical result delivery is idempotent; it cannot create another audit chain.
        if (old.state() == state && (state == State.succeeded || state == State.failed)
                && outcome != null && java.util.Objects.equals(old.httpStatus(), outcome.httpStatus())
                && java.util.Objects.equals(old.failureClass(), outcome.failureClass())) { return; }
        boolean allowed = switch (old.state()) {
            case proposed -> state == State.approved || state == State.rejected || state == State.expired;
            case approved -> state == State.executing || state == State.rejected || state == State.expired
                    || state == State.failed && outcome != null
                    && "execution_blocked".equals(outcome.failureClass());
            case executing -> state == State.succeeded || state == State.failed;
            default -> false;
        };
        if (!allowed || ((state == State.succeeded || state == State.failed) != (outcome != null))
                || outcome != null && outcome.success() != (state == State.succeeded)) { throw unavailable(); }
        append(new Entry(id, old.tool(), old.tenant(), old.callerFingerprint(), old.sessionReference(), state,
                old.createdAt(), Instant.now().toString(), outcome == null ? null : outcome.httpStatus(),
                outcome == null ? null : outcome.failureClass()));
    }

    @Override public synchronized Optional<Entry> find(String id) {
        open();
        return Optional.ofNullable(latest.get(id));
    }

    private void open() {
        if (unavailable) { throw unavailable(); }
        if (channel != null) { return; }
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                // Persist the directory entry as well as each subsequent file append.
                try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
            }
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) { throw new IOException("Audit journal already in use"); }
            // Refuse a torn tail instead of appending to an ambiguous record.
            if (channel.size() > 0) {
                ByteBuffer tail = ByteBuffer.allocate(1);
                channel.read(tail, channel.size() - 1);
                if (tail.array()[0] != '\n') { throw new IOException("Incomplete audit journal"); }
            }
            try (var lines = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    Entry entry = mapper.readValue(line, Entry.class);
                    latest.put(entry.correlationId(), entry);
                }
            }
            channel.position(channel.size());
        } catch (Exception failure) {
            unavailable = true;
            release();
            throw unavailable(); // Never surface paths, input, credentials or low-level exceptions.
        }
    }

    private void append(Entry entry) {
        try {
            ByteBuffer bytes = ByteBuffer.wrap((mapper.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) { channel.write(bytes); }
            channel.force(true);
            latest.put(entry.correlationId(), entry);
            System.getLogger(FileActionAudit.class.getName()).log(System.Logger.Level.INFO,
                    "[correlationId={0}] create_client {1}", entry.correlationId(), entry.state());
        } catch (IOException failure) {
            unavailable = true;
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("Action audit unavailable"); }

    private void release() {
        try { if (lock != null) { lock.release(); } } catch (IOException ignored) { /* fail closed */ }
        try { if (channel != null) { channel.close(); } } catch (IOException ignored) { /* fail closed */ }
    }

    @Override public synchronized void close() { unavailable = true; release(); }
}
