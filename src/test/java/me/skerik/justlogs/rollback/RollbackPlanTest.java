package me.skerik.justlogs.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.BlockEntry;
import org.junit.jupiter.api.Test;

class RollbackPlanTest {

    private static BlockEntry row(long id, int time, int x, int y, int z, String oldMat, String newMat) {
        return new BlockEntry(id, time, "Notch", true, Action.PLACE, "world", x, y, z, oldMat, "", newMat, "", false, false);
    }

    @Test
    void rollbackUsesTheOldestRowPerPosition() {
        // stone -> dirt -> glass at one spot: rolling back everything must end at stone
        RollbackPlan plan = RollbackPlan.build(List.of(
                row(1, 10, 5, 64, 5, "stone", "dirt"),
                row(2, 20, 5, 64, 5, "dirt", "glass")), false);
        assertEquals(1, plan.blocks());
        assertEquals("stone", plan.chunks().get(0).changes().get(0).material());
        assertEquals(List.of(1L, 2L), plan.rowIds());
    }

    @Test
    void restoreUsesTheNewestRowPerPosition() {
        RollbackPlan plan = RollbackPlan.build(List.of(
                row(1, 10, 5, 64, 5, "stone", "dirt"),
                row(2, 20, 5, 64, 5, "dirt", "glass")), true);
        assertEquals("glass", plan.chunks().get(0).changes().get(0).material());
    }

    @Test
    void groupsByChunkAndSortsBottomUp() {
        RollbackPlan plan = RollbackPlan.build(List.of(
                row(1, 1, 20, 70, 3, "air", "stone"),
                row(2, 2, 1, 80, 1, "air", "stone"),
                row(3, 3, 2, 65, 2, "air", "stone"),
                row(4, 4, -1, 10, -1, "air", "stone")), false);
        assertEquals(3, plan.chunks().size());
        RollbackPlan.ChunkChanges origin = plan.chunks().stream()
                .filter(c -> c.chunkX() == 0 && c.chunkZ() == 0).findFirst().orElseThrow();
        assertEquals(List.of(65, 80), origin.changes().stream().map(RollbackPlan.Change::y).toList());
        assertTrue(plan.chunks().stream().anyMatch(c -> c.chunkX() == -1 && c.chunkZ() == -1)); // negative coordinates floor
    }

    @Test
    void emptyMaterialMeansAir() {
        RollbackPlan plan = RollbackPlan.build(List.of(row(1, 1, 0, 0, 0, "", "stone")), false);
        assertEquals("air", plan.chunks().get(0).changes().get(0).material());
    }
}
