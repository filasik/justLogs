package me.skerik.justlogs.rollback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import me.skerik.justlogs.Scheduler;
import org.jspecify.annotations.Nullable;
import me.skerik.justlogs.model.BlockEntry;

/**
 * What a rollback or restore will do, worked out from the matching log rows without touching the server.
 * Per block position the OLDEST row decides a rollback (the position goes back to its {@code old} state), the NEWEST
 * row decides a restore (it goes to its {@code new} state). Positions are grouped per chunk so the world is changed
 * one chunk at a time on the thread that owns it.
 */
public final class RollbackPlan implements Plan {

    /** One block to set. Material is a registry key ({@code stone}), data the state without brackets. */
    public record Change(int x, int y, int z, String material, String data, byte @Nullable [] nbt) {
    }

    /** All changes in one chunk, lowest {@code y} first. */
    public record ChunkChanges(String world, int chunkX, int chunkZ, List<Change> changes) {
    }

    private record Pos(String world, int x, int y, int z) {
    }

    private final List<ChunkChanges> chunks;
    private final List<Long> rowIds;
    private final int blocks;

    private RollbackPlan(List<ChunkChanges> chunks, List<Long> rowIds, int blocks) {
        this.chunks = chunks;
        this.rowIds = rowIds;
        this.blocks = blocks;
    }

    /** @param rows matching rows, oldest first ({@code time}, then {@code id}) */
    public static RollbackPlan build(List<BlockEntry> rows, boolean restore) {
        return build(rows, restore, Map.of());
    }

    /** @param nbt tile entity snapshots (chest contents) by log row id; used for the OLD state of a rollback */
    public static RollbackPlan build(List<BlockEntry> rows, boolean restore, Map<Long, byte[]> nbt) {
        Map<Pos, BlockEntry> decisive = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>(rows.size());
        for (BlockEntry row : rows) {
            ids.add(row.id());
            Pos pos = new Pos(row.world(), row.x(), row.y(), row.z());
            if (restore) decisive.put(pos, row); // later rows overwrite earlier ones
            else decisive.putIfAbsent(pos, row);
        }
        Map<String, List<Change>> byChunk = new LinkedHashMap<>();
        Map<String, int[]> chunkCoords = new LinkedHashMap<>();
        Map<String, String> chunkWorld = new LinkedHashMap<>();
        for (Map.Entry<Pos, BlockEntry> e : decisive.entrySet()) {
            Pos p = e.getKey();
            BlockEntry row = e.getValue();
            String material = restore ? row.newMaterial() : row.oldMaterial();
            String data = restore ? row.newData() : row.oldData();
            if (material.isEmpty()) material = "air";
            int cx = Math.floorDiv(p.x(), 16);
            int cz = Math.floorDiv(p.z(), 16);
            String key = p.world() + '\u0000' + cx + ',' + cz;
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(new Change(p.x(), p.y(), p.z(), material, data, restore ? null : nbt.get(row.id())));
            chunkCoords.putIfAbsent(key, new int[] {cx, cz});
            chunkWorld.putIfAbsent(key, p.world());
        }
        List<ChunkChanges> chunks = new ArrayList<>(byChunk.size());
        for (Map.Entry<String, List<Change>> e : byChunk.entrySet()) {
            List<Change> list = e.getValue();
            list.sort(java.util.Comparator.comparingInt(Change::y));
            int[] c = chunkCoords.get(e.getKey());
            chunks.add(new ChunkChanges(chunkWorld.get(e.getKey()), c[0], c[1], List.copyOf(list)));
        }
        return new RollbackPlan(List.copyOf(chunks), List.copyOf(ids), decisive.size());
    }

    @Override
    public int chunkCount() {
        return chunks.size();
    }

    @Override
    public boolean containers() {
        return false;
    }

    @Override
    public CompletableFuture<ApplyResult> apply(Scheduler scheduler, int msPerTick) {
        return BlockApplier.apply(scheduler, msPerTick, this);
    }

    public List<ChunkChanges> chunks() {
        return chunks;
    }

    /** Ids of every matching log row (they get the rolled-back flag, even where several rows share a position). */
    @Override
    public List<Long> rowIds() {
        return rowIds;
    }

    /** Number of distinct block positions that will change. */
    @Override
    public int blocks() {
        return blocks;
    }
}
