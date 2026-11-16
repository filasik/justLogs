package me.skerik.justlogs.listener;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Helpers to find the block behind an inventory. A double chest is always reported as its LEFT half, so both halves share one history. */
public final class Containers {

    private Containers() {
    }

    /** The container block of an inventory, null for player, ender chest, workbench and entity inventories. */
    public static @Nullable Block blockOf(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof DoubleChest dc) holder = dc.getLeftSide(false);
        return holder instanceof Container c ? c.getBlock() : null;
    }

    /** The block a history is stored under: for a chest that is part of a double chest, its left half. */
    public static Block normalize(Block block) {
        BlockState state = block.getState(false);
        if (state instanceof Chest chest) {
            Block left = blockOf(chest.getInventory());
            if (left != null) return left;
        }
        return block;
    }

    public static boolean isContainer(Block block) {
        return block.getState(false) instanceof Container;
    }

    /** Inventory to read or change contents: the whole (double) chest, null when the block is no container. */
    public static @Nullable Inventory inventory(Block block) {
        return block.getState(false) instanceof Container c ? c.getInventory() : null;
    }

    /** Contents of just this block (one half of a double chest) as bytes, null when empty or not a container. */
    public static byte @Nullable [] snapshot(Block block) {
        BlockState state = block.getState(false);
        Inventory inv;
        if (state instanceof Chest chest) inv = chest.getBlockInventory();
        else if (state instanceof Container c) inv = c.getInventory();
        else return null;
        ItemStack[] contents = inv.getContents();
        boolean any = false;
        for (ItemStack s : contents) {
            if (s != null && !s.getType().isAir()) {
                any = true;
                break;
            }
        }
        return any ? ItemStack.serializeItemsAsBytes(contents) : null;
    }

    /** Puts a snapshot from {@link #snapshot} back into the (freshly placed) container block. */
    public static boolean restore(Block block, byte[] data) {
        BlockState state = block.getState(false);
        Inventory inv;
        if (state instanceof Chest chest) inv = chest.getBlockInventory();
        else if (state instanceof Container c) inv = c.getInventory();
        else return false;
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(data);
        if (items.length > inv.getSize()) return false;
        inv.setContents(items);
        return true;
    }
}
