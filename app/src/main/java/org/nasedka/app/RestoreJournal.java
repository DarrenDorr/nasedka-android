package org.nasedka.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/** Internal journal only. Caller owns exclusive barrier. Not wired to BackupStore.
 * Atomic rename and directory sync are required, not optional fallbacks. */
final class RestoreJournal {
    private static final String HEADER = "NASEDKA_RESTORE_V2";
    private static final int LIMIT = 1024;
    private final Path directory, live, temporary;
    // Package-private fault seam; production always calls the real directory force.
    interface DirectorySync { void force(Path directory) throws IOException; }
    private final DirectorySync directorySync;

    RestoreJournal(Path privateDirectory) throws IOException {
        this(privateDirectory,RestoreFileStorage::sync);
    }
    RestoreJournal(Path privateDirectory,DirectorySync directorySync) throws IOException {
        this.directorySync=java.util.Objects.requireNonNull(directorySync);
        directory = privateDirectory.toAbsolutePath().normalize();
        checkDirectory();
        live = directory.resolve("restore-v2.journal");
        temporary = directory.resolve("restore-v2.journal.tmp");
    }

    static final class Entry {
        final UUID generation;
        final RestoreProtocol.Phase phase;
        // Old incubation, farm, prefs; new incubation, farm, prefs SHA-256.
        private final String[] hashes;
        Entry(UUID generation, RestoreProtocol.Phase phase, String[] hashes) {
            if (generation == null || phase == null || phase == RestoreProtocol.Phase.NONE
                    || hashes == null || hashes.length != 6)
                throw new IllegalArgumentException("Invalid restore metadata");
            for (String h : hashes)
                if (h == null || !h.matches("[0-9a-f]{64}"))
                    throw new IllegalArgumentException("Invalid SHA-256");
            this.generation = generation; this.phase = phase; this.hashes = hashes.clone();
        }
        String[] hashes() { return hashes.clone(); }
        Entry withPhase(RestoreProtocol.Phase next) { return new Entry(generation, next, hashes); }
        boolean sameGeneration(Entry other) {
            return generation.equals(other.generation) && Arrays.equals(hashes, other.hashes);
        }
    }

    // NONE only for a definitely absent journal in an existing directory.
    Entry read() throws IOException {
        checkDirectory();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(live, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            checkDirectory(); return null;
        }
        if (!attributes.isRegularFile() || attributes.size() > LIMIT)
            throw new IOException("Invalid restore journal file");
        byte[] bytes = new byte[LIMIT + 1];
        int length = 0;
        try (InputStream input = Files.newInputStream(live, LinkOption.NOFOLLOW_LINKS)) {
            while (length < bytes.length) {
                int n = input.read(bytes, length, bytes.length - length);
                if (n < 0) break;
                length += n;
            }
        }
        if (length > LIMIT) throw new IOException("Restore journal exceeds limit");
        String raw = new String(bytes, 0, length, StandardCharsets.US_ASCII);
        try {
            String[] lines = raw.split("\n", -1);
            if (lines.length != 11 || !HEADER.equals(lines[0]) || !lines[10].isEmpty())
                throw new IllegalArgumentException("Invalid journal format");
            Entry entry = new Entry(UUID.fromString(lines[1]),
                    RestoreProtocol.Phase.valueOf(lines[2]), Arrays.copyOfRange(lines, 3, 9));
            if (!Arrays.equals(Arrays.copyOf(bytes, length), encode(entry)))
                throw new IllegalArgumentException("Invalid checksum or encoding");
            return entry;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid restore journal", invalid);
        }
    }

    void publish(RestoreProtocol.Phase expected, Entry next) throws IOException {
        if (expected == null || next == null) throw new IOException("Invalid transition");
        Entry current = read();
        if (expected == RestoreProtocol.Phase.NONE) {
            if (current != null || next.phase != RestoreProtocol.Phase.PENDING)
                throw new IOException("Expected absent journal and PENDING");
        } else if (current == null || current.phase != expected || !current.sameGeneration(next)
                || expected != RestoreProtocol.Phase.PENDING
                || (next.phase != RestoreProtocol.Phase.COMMITTED
                    && next.phase != RestoreProtocol.Phase.ROLLED_BACK)) {
            throw new IOException("Invalid journal transition");
        }
        // Temp is never authoritative. Discard only under exclusive access
        // after validating the existing journal.
        discardTemporary();
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(encode(next));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        Files.move(temporary, live, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory();
        // Failure after rename is ambiguous; recover from persisted state.
    }

    void clear(Entry expectedTerminal) throws IOException {
        Entry current = read();
        if (expectedTerminal == null || current == null
                || (current.phase != RestoreProtocol.Phase.COMMITTED
                    && current.phase != RestoreProtocol.Phase.ROLLED_BACK)
                || current.phase != expectedTerminal.phase || !current.sameGeneration(expectedTerminal))
            throw new IOException("Cannot clear unresolved journal");
        // Adapter must already have verified terminal generation and cleaned up.
        discardTemporary();
        Files.delete(live);
        syncDirectory();
    }

    /**
     * Visibility after rename is not durability. Before deleting any rollback
     * copies, re-force the exact terminal record and its directory. A failed
     * force leaves cleanup forbidden, even after reopening the adapter.
     */
    void confirmTerminal(Entry expected) throws IOException {
        if(expected==null || (expected.phase!=RestoreProtocol.Phase.COMMITTED
                && expected.phase!=RestoreProtocol.Phase.ROLLED_BACK))
            throw new IOException("Not a terminal journal");
        requireSame(expected);
        try(FileChannel channel=FileChannel.open(live,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            channel.force(true);
        }
        syncDirectory();
        requireSame(expected);
    }
    private void requireSame(Entry expected)throws IOException {
        Entry current=read();
        if(current==null || current.phase!=expected.phase || !current.sameGeneration(expected))
            throw new IOException("Restore journal changed");
    }

    // After an ambiguous unlink/force failure, a visible NONE is not enough.
    // Caller holds the operation barrier; do not permit new live writes until
    // the absent journal directory entry has been synchronized.
    void confirmAbsent()throws IOException {
        if(read()!=null)throw new IOException("Restore journal present");
        syncDirectory();
        if(read()!=null)throw new IOException("Restore journal appeared");
    }

    private void discardTemporary() throws IOException {
        try {
            BasicFileAttributes a = Files.readAttributes(temporary, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) throw new IOException("Invalid temporary journal");
            Files.delete(temporary);
        } catch (NoSuchFileException absent) { checkDirectory(); }
    }
    private void checkDirectory() throws IOException {
        if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory())
            throw new IOException("Restore journal directory unavailable");
    }
    private void syncDirectory() throws IOException {
        checkDirectory();
        directorySync.force(directory);
    }
    private static byte[] encode(Entry entry) {
        StringBuilder body = new StringBuilder(HEADER).append('\n')
                .append(entry.generation).append('\n').append(entry.phase.name()).append('\n');
        for (String hash : entry.hashes) body.append(hash).append('\n');
        byte[] bytes = body.toString().getBytes(StandardCharsets.US_ASCII);
        return body.append(sha256(bytes)).append('\n').toString().getBytes(StandardCharsets.US_ASCII);
    }
    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] digits = "0123456789abcdef".toCharArray();
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) {
                int n = b & 255; out.append(digits[n >>> 4]).append(digits[n & 15]);
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
