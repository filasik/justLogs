package me.skerik.justlogs.util;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Cheap conversions from Bukkit types to the strings stored in the log. No allocation for materials. */
public final class Keys {

    private static final Map<Material, String> MATERIALS = new EnumMap<>(Material.class);

    static {
        for (Material m : Material.values()) MATERIALS.put(m, m.name().toLowerCase(Locale.ROOT));
    }

    private Keys() {
    }

    /** {@code DIAMOND_ORE} becomes {@code diamond_ore}. */
    public static String material(Material m) {
        return MATERIALS.get(m);
    }

    /** The block state without brackets: {@code facing=north,half=top}; empty when the block has no properties. */
    public static String state(BlockData data) {
        String s = data.getAsString(true);
        int open = s.indexOf('[');
        return open < 0 ? "" : s.substring(open + 1, s.length() - 1);
    }

    /** {@code diamond_ore} becomes {@code diamond ore} for chat. */
    public static String pretty(String key) {
        return key.replace('_', ' ');
    }
}
