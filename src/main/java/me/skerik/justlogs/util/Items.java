package me.skerik.justlogs.util;

import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Item (de)serialization for the log. Plain items need no bytes at all, only items with data (names, enchantments...). */
public final class Items {

    private Items() {
    }

    /** Serialized one-item copy for items with metadata, null for plain items (material + amount say everything). */
    public static byte @Nullable [] dataOf(ItemStack stack) {
        if (!stack.hasItemMeta()) return null;
        return stack.asOne().serializeAsBytes();
    }

    /** Rebuilds a stack from a log row. */
    public static @Nullable ItemStack build(org.bukkit.Material material, int amount, byte @Nullable [] data) {
        if (data != null) {
            ItemStack stack = ItemStack.deserializeBytes(data);
            stack.setAmount(amount);
            return stack;
        }
        if (!material.isItem()) return null;
        return new ItemStack(material, amount);
    }
}
