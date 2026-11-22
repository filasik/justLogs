package me.skerik.justlogs.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import me.skerik.justlogs.model.Action;
import org.jspecify.annotations.Nullable;

/**
 * Parsed, validated query. Immutable; identical for in-game commands, the web API and other plugins.
 *
 * @param fromSec  Unix seconds, 0 = no lower bound
 * @param toSec    Unix seconds, 0 = no upper bound
 * @param timeText the {@code t:} value as written ({@code 2d-1d}), null when no time was given
 * @param actions  empty = every action
 * @param at       explicit centre {@code x,y,z} of the radius instead of the sender's position, or null
 */
public record LogQuery(List<String> users, boolean usersNegated, long fromSec, long toSec, @Nullable String timeText,
                       Radius radius, Set<Action> actions, List<String> actionTexts, List<String> materials,
                       boolean materialsNegated, List<String> entities, boolean entitiesNegated,
                       @Nullable String keyword, @Nullable String world, int @Nullable [] at, Set<Flag> flags) {

    public LogQuery(List<String> users, boolean usersNegated, long fromSec, long toSec, @Nullable String timeText,
                    Radius radius, Set<Action> actions, List<String> actionTexts, List<String> materials,
                    boolean materialsNegated, @Nullable String world, int @Nullable [] at, Set<Flag> flags) {
        this(users, usersNegated, fromSec, toSec, timeText, radius, actions, actionTexts, materials, materialsNegated,
                List.of(), false, null, world, at, flags);
    }

    public enum Flag { PREVIEW, VERBOSE, COUNT, SILENT }

    public boolean hasTime() {
        return fromSec > 0 || toSec > 0;
    }

    public boolean has(Flag flag) {
        return flags.contains(flag);
    }

    public LogQuery withRadius(Radius newRadius) {
        return new LogQuery(users, usersNegated, fromSec, toSec, timeText, newRadius, actions, actionTexts, materials,
                materialsNegated, entities, entitiesNegated, keyword, world, at, flags);
    }

    /** Normalized text form, shown in result headers and stored with rollbacks. */
    public String canonical() {
        List<String> parts = new ArrayList<>();
        if (!users.isEmpty()) parts.add("u:" + (usersNegated ? "!" : "") + String.join(",", users));
        if (timeText != null) parts.add("t:" + timeText);
        switch (radius.kind()) {
            case CUBE -> parts.add("r:" + radius.size());
            case GLOBAL -> parts.add("r:#global");
            case WORLD -> parts.add("r:#world");
            case CHUNK -> parts.add("r:#chunk");
            case SELECTION -> parts.add("r:#worldedit");
            case NONE -> {
            }
        }
        if (!actionTexts.isEmpty()) parts.add("a:" + String.join(",", actionTexts));
        if (!materials.isEmpty()) parts.add("b:" + (materialsNegated ? "!" : "") + String.join(",", materials));
        if (!entities.isEmpty()) parts.add("e:" + (entitiesNegated ? "!" : "") + String.join(",", entities));
        if (keyword != null) parts.add("k:" + (keyword.contains(" ") ? "\"" + keyword + "\"" : keyword));
        if (world != null) parts.add("w:" + world);
        if (at != null) parts.add("at:" + at[0] + "," + at[1] + "," + at[2]);
        for (Flag f : flags) parts.add("#" + f.name().toLowerCase(java.util.Locale.ROOT));
        return String.join(" ", parts);
    }
}
