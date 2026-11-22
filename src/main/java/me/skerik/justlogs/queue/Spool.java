package me.skerik.justlogs.queue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Actor;
import me.skerik.justlogs.model.BlockRecord;
import me.skerik.justlogs.model.ChatRecord;
import me.skerik.justlogs.model.CommandRecord;
import me.skerik.justlogs.model.ContainerRecord;
import me.skerik.justlogs.model.EntityRecord;
import me.skerik.justlogs.model.InteractRecord;
import me.skerik.justlogs.model.ItemRecord;
import me.skerik.justlogs.model.SignRecord;
import me.skerik.justlogs.model.SessionRecord;
import me.skerik.justlogs.model.LogRecord;
import org.jspecify.annotations.Nullable;

/**
 * Disk overflow for {@link BatchWriter}: when the database is unreachable or shutdown times out, unsaved batches are
 * written as segment files ({@code spool-*.bin}) and replayed in order once the database comes back.
 *
 * <p>Each segment is written to a {@code .tmp} file and renamed atomically, so a crash mid-write never leaves a half
 * written segment behind.
 */
public final class Spool {

    public static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAGIC = 0x4A4C5350; // "JLSP"
    private static final int VERSION = 1;
    private static final byte TYPE_BLOCK = 1;
    private static final byte TYPE_CONTAINER = 2;
    private static final byte TYPE_SIGN = 3;
    private static final byte TYPE_CHAT = 4;
    private static final byte TYPE_COMMAND = 5;
    private static final byte TYPE_SESSION = 6;
    private static final byte TYPE_INTERACT = 7;
    private static final byte TYPE_ITEM = 8;
    private static final byte TYPE_ENTITY = 9;

    public record Batch(Path file, List<LogRecord> records, long sizeBytes) {
    }

    private final Path dir;
    private final long maxBytes;
    private final Logger log;
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong pendingRecords = new AtomicLong();
    private final AtomicLong pendingBytes = new AtomicLong();
    private volatile boolean fullWarned;

    public Spool(Path dir, Logger log) {
        this(dir, DEFAULT_MAX_BYTES, log);
    }

    public Spool(Path dir, long maxBytes, Logger log) {
        this.dir = dir;
        this.maxBytes = maxBytes;
        this.log = log;
        scanExisting();
    }

    public long pendingCount() {
        return pendingRecords.get();
    }

    public long pendingBytes() {
        return pendingBytes.get();
    }

    public boolean hasPending() {
        return pendingRecords.get() > 0;
    }

    /** Writes a batch to disk. Returns {@code false} if the spool is full or an I/O error occurs. */
    public boolean write(List<LogRecord> records) {
        if (records.isEmpty()) return true;
        if (pendingBytes.get() >= maxBytes) {
            if (!fullWarned) {
                fullWarned = true;
                log.warning("Spool directory " + dir + " reached " + (maxBytes / (1024 * 1024))
                        + " MiB limit; dropping further overflow records");
            }
            return false;
        }
        try {
            Files.createDirectories(dir);
            String base = String.format("spool-%013d-%06d", System.currentTimeMillis(), seq.incrementAndGet());
            Path tmp = dir.resolve(base + ".tmp");
            Path bin = dir.resolve(base + ".bin");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(records.size());
                for (LogRecord r : records) writeRecord(out, r);
            }
            moveAtomic(tmp, bin);
            long bytes = Files.size(bin);
            pendingRecords.addAndGet(records.size());
            pendingBytes.addAndGet(bytes);
            fullWarned = false;
            return true;
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to write spool batch to " + dir, e);
            return false;
        }
    }

    /** Reads the oldest pending spool segment without removing it. Call {@link #ack(Batch)} after DB commit. */
    public Optional<Batch> peek() {
        if (!Files.isDirectory(dir)) return Optional.empty();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("spool-") && n.endsWith(".bin");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.log(Level.WARNING, "Cannot list spool directory " + dir, e);
            return Optional.empty();
        }
        for (Path file : files) {
            try {
                long bytes = Files.size(file);
                List<LogRecord> records = readFile(file);
                return Optional.of(new Batch(file, records, bytes));
            } catch (IOException e) {
                log.log(Level.WARNING, "Discarding corrupt spool file " + file.getFileName() + ": " + e.getMessage());
                deleteQuietly(file);
            }
        }
        recount();
        return Optional.empty();
    }

    /** Deletes a replayed spool segment and updates counters. */
    public void ack(Batch batch) {
        if (deleteQuietly(batch.file())) {
            pendingRecords.updateAndGet(v -> Math.max(0, v - batch.records().size()));
            pendingBytes.updateAndGet(v -> Math.max(0, v - batch.sizeBytes()));
            fullWarned = false;
        }
    }

    private void scanExisting() {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    deleteQuietly(p);
                } else if (name.startsWith("spool-") && name.endsWith(".bin")) {
                    try {
                        int count = readHeaderCount(p);
                        pendingRecords.addAndGet(count);
                        pendingBytes.addAndGet(Files.size(p));
                    } catch (IOException e) {
                        log.log(Level.WARNING, "Discarding corrupt spool file " + name + ": " + e.getMessage());
                        deleteQuietly(p);
                    }
                }
            }
        } catch (IOException e) {
            log.log(Level.WARNING, "Cannot scan spool directory " + dir, e);
        }
    }

    private void recount() {
        pendingRecords.set(0);
        pendingBytes.set(0);
        scanExisting();
    }

    private static int readHeaderCount(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = in.readInt();
            int version = in.readInt();
            int count = in.readInt();
            if (magic != MAGIC || version != VERSION || count < 0) {
                throw new IOException("invalid spool header");
            }
            return count;
        }
    }

    private static List<LogRecord> readFile(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int magic = in.readInt();
            int version = in.readInt();
            int count = in.readInt();
            if (magic != MAGIC || version != VERSION || count < 0) {
                throw new IOException("invalid spool header");
            }
            List<LogRecord> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) out.add(readRecord(in));
            return out;
        } catch (EOFException e) {
            throw new IOException("truncated spool file", e);
        }
    }

    private static void writeRecord(DataOutputStream out, LogRecord record) throws IOException {
        switch (record) {
            case BlockRecord b -> {
                out.writeByte(TYPE_BLOCK);
                writeCommon(out, b);
                out.writeUTF(b.oldMaterial());
                out.writeUTF(b.oldData());
                out.writeUTF(b.newMaterial());
                out.writeUTF(b.newData());
                writeBytes(out, b.oldNbt());
            }
            case ContainerRecord c -> {
                out.writeByte(TYPE_CONTAINER);
                writeCommon(out, c);
                out.writeUTF(c.holderMaterial());
                out.writeUTF(c.material());
                out.writeInt(c.amount());
                writeBytes(out, c.itemData());
            }
            case SignRecord s -> {
                out.writeByte(TYPE_SIGN);
                writeCommon(out, s);
                out.writeInt(s.side());
                out.writeUTF(s.oldText());
                out.writeUTF(s.newText());
            }
            case ChatRecord ch -> {
                out.writeByte(TYPE_CHAT);
                writeCommon(out, ch);
                out.writeUTF(ch.message());
            }
            case CommandRecord cm -> {
                out.writeByte(TYPE_COMMAND);
                writeCommon(out, cm);
                out.writeUTF(cm.message());
            }
            case SessionRecord se -> {
                out.writeByte(TYPE_SESSION);
                writeCommon(out, se);
                writeNullable(out, se.ipHash());
                writeNullable(out, se.ip());
            }
            case InteractRecord in -> {
                out.writeByte(TYPE_INTERACT);
                writeCommon(out, in);
                out.writeUTF(in.material());
            }
            case ItemRecord it -> {
                out.writeByte(TYPE_ITEM);
                writeCommon(out, it);
                out.writeUTF(it.material());
                out.writeInt(it.amount());
                writeBytes(out, it.itemData());
            }
            case EntityRecord en -> {
                out.writeByte(TYPE_ENTITY);
                writeCommon(out, en);
                out.writeUTF(en.entityType());
                writeActor(out, en.victim());
                out.writeInt(en.cause());
                writeNullable(out, en.name());
                writeNullable(out, en.detail());
            }
        }
    }

    private static void writeCommon(DataOutputStream out, LogRecord r) throws IOException {
        out.writeInt(r.time());
        out.writeUTF(r.actor().ident());
        out.writeUTF(r.actor().name());
        out.writeBoolean(r.actor().player());
        out.writeInt(r.action().id());
        out.writeUTF(r.world());
        out.writeInt(r.x());
        out.writeInt(r.y());
        out.writeInt(r.z());
    }

    private static LogRecord readRecord(DataInputStream in) throws IOException {
        byte type = in.readByte();
        int time = in.readInt();
        String ident = in.readUTF();
        String name = in.readUTF();
        boolean player = in.readBoolean();
        Actor actor = new Actor(ident, name, player);
        int actionId = in.readInt();
        Action action = Action.byId(actionId)
                .orElseThrow(() -> new IOException("unknown action id " + actionId));
        String world = in.readUTF();
        int x = in.readInt();
        int y = in.readInt();
        int z = in.readInt();
        return switch (type) {
            case TYPE_BLOCK -> new BlockRecord(time, actor, action, world, x, y, z,
                    in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), readBytes(in));
            case TYPE_CONTAINER -> new ContainerRecord(time, actor, action, world, x, y, z,
                    in.readUTF(), in.readUTF(), in.readInt(), readBytes(in));
            case TYPE_SIGN -> new SignRecord(time, actor, action, world, x, y, z,
                    in.readInt(), in.readUTF(), in.readUTF());
            case TYPE_CHAT -> new ChatRecord(time, actor, world, x, y, z, in.readUTF());
            case TYPE_COMMAND -> new CommandRecord(time, actor, world, x, y, z, in.readUTF());
            case TYPE_SESSION -> new SessionRecord(time, actor, action, world, x, y, z,
                    readNullable(in), readNullable(in));
            case TYPE_INTERACT -> new InteractRecord(time, actor, action, world, x, y, z, in.readUTF());
            case TYPE_ITEM -> new ItemRecord(time, actor, action, world, x, y, z,
                    in.readUTF(), in.readInt(), readBytes(in));
            case TYPE_ENTITY -> new EntityRecord(time, actor, action, world, x, y, z,
                    in.readUTF(), readActor(in), in.readInt(), readNullable(in), readNullable(in));
            default -> throw new IOException("unknown record type " + type);
        };
    }

    private static void writeNullable(DataOutputStream out, @Nullable String s) throws IOException {
        if (s == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(s);
        }
    }

    private static @Nullable String readNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static void writeActor(DataOutputStream out, @Nullable Actor a) throws IOException {
        if (a == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(a.ident());
            out.writeUTF(a.name());
            out.writeBoolean(a.player());
        }
    }

    private static @Nullable Actor readActor(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        return new Actor(in.readUTF(), in.readUTF(), in.readBoolean());
    }

    private static void writeBytes(DataOutputStream out, byte @Nullable [] data) throws IOException {
        if (data == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(data.length);
            out.write(data);
        }
    }

    private static byte @Nullable [] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) return null;
        return in.readNBytes(len);
    }

    private static void moveAtomic(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean deleteQuietly(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException ignored) {
            return false;
        }
    }
}
