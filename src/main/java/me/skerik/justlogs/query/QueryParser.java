package me.skerik.justlogs.query;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.regex.Pattern;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.util.Durations;
import org.jspecify.annotations.Nullable;

/**
 * Parses {@code u:Notch t:2d-1d r:10 a:-block b:!stone w:world #preview}. Order is free, several values are separated by
 * commas, {@code !} negates users and materials. The one parser behind commands, the web API and the plugin API.
 */
public final class QueryParser {

    /** Expands a {@code #tag} in {@code b:} to material keys; the Bukkit side knows the tags. */
    @FunctionalInterface
    public interface TagResolver {
        Optional<Set<String>> resolve(String tag);
    }

    public static final TagResolver NO_TAGS = tag -> Optional.empty();
    public static final int MAX_RADIUS = 100_000;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_#.\\-]{1,48}");
    private static final Pattern MATERIAL = Pattern.compile("[a-z0-9_/.\\-]{1,128}");
    private static final Pattern WORLD = Pattern.compile("[^\\s,]{1,128}");

    private QueryParser() {
    }

    /**
     * @param nowSec         current Unix time, passed in so tests are deterministic
     * @param defaultSeconds applied as {@code t:} when the query has none (0 = no default)
     */
    public static LogQuery parse(String input, long nowSec, TagResolver tags, long defaultSeconds) throws QuerySyntaxException {
        List<String> users = List.of();
        boolean usersNegated = false;
        long from = 0;
        long to = 0;
        String timeText = null;
        Radius radius = Radius.NONE;
        Set<Action> actions = EnumSet.noneOf(Action.class);
        List<String> actionTexts = new ArrayList<>();
        List<String> materials = List.of();
        boolean materialsNegated = false;
        String world = null;
        int[] at = null;
        Set<LogQuery.Flag> flags = EnumSet.noneOf(LogQuery.Flag.class);

        for (String token : input.strip().split("\\s+")) {
            if (token.isEmpty()) continue;
            if (token.startsWith("#")) {
                try {
                    flags.add(LogQuery.Flag.valueOf(token.substring(1).toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    throw new QuerySyntaxException("unknown-flag", token);
                }
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0) throw new QuerySyntaxException("unknown-param", token);
            String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = token.substring(colon + 1);
            if (value.isEmpty()) throw new QuerySyntaxException("missing-value", key + ":");
            switch (key) {
                case "u" -> {
                    usersNegated = value.startsWith("!");
                    List<String> names = split(usersNegated ? value.substring(1) : value);
                    for (String n : names) if (!NAME.matcher(n).matches()) throw new QuerySyntaxException("bad-user", n);
                    if (names.isEmpty()) throw new QuerySyntaxException("missing-value", "u:");
                    users = names;
                }
                case "t" -> {
                    String[] range = value.split("-", 2);
                    OptionalLong older = Durations.parseSeconds(range[0]);
                    if (older.isEmpty()) throw new QuerySyntaxException("bad-time", value);
                    if (range.length == 2) {
                        OptionalLong newer = Durations.parseSeconds(range[1]);
                        if (newer.isEmpty()) throw new QuerySyntaxException("bad-time", value);
                        long a = Math.max(older.getAsLong(), newer.getAsLong());
                        long b = Math.min(older.getAsLong(), newer.getAsLong());
                        from = nowSec - a;
                        to = nowSec - b;
                    } else {
                        from = nowSec - older.getAsLong();
                        to = 0;
                    }
                    timeText = value.toLowerCase(Locale.ROOT);
                }
                case "r" -> radius = radius(value);
                case "a" -> {
                    actions.clear();
                    actionTexts.clear();
                    for (String a : split(value)) {
                        Optional<Set<Action>> resolved = ActionGroups.resolve(a);
                        if (resolved.isEmpty()) throw new QuerySyntaxException("unknown-action", a);
                        actions.addAll(resolved.get());
                        actionTexts.add(a.toLowerCase(Locale.ROOT));
                    }
                }
                case "b" -> {
                    materialsNegated = value.startsWith("!");
                    Set<String> keys = new LinkedHashSet<>();
                    for (String m : split(materialsNegated ? value.substring(1) : value)) {
                        if (m.startsWith("#")) {
                            keys.addAll(tags.resolve(m.substring(1).toLowerCase(Locale.ROOT))
                                    .orElseThrow(() -> new QuerySyntaxException("unknown-tag", m)));
                            continue;
                        }
                        String k = m.toLowerCase(Locale.ROOT);
                        if (k.startsWith("minecraft:")) k = k.substring("minecraft:".length());
                        if (!MATERIAL.matcher(k).matches()) throw new QuerySyntaxException("bad-material", m);
                        keys.add(k);
                    }
                    if (keys.isEmpty()) throw new QuerySyntaxException("missing-value", "b:");
                    materials = List.copyOf(keys);
                }
                case "w" -> {
                    if (!WORLD.matcher(value).matches()) throw new QuerySyntaxException("bad-world", value);
                    world = value;
                }
                case "at" -> at = position(value);
                default -> throw new QuerySyntaxException("unknown-param", token);
            }
        }
        if (timeText == null && defaultSeconds > 0) {
            from = nowSec - defaultSeconds;
            timeText = Durations.format(defaultSeconds).replace(" ", "");
        }
        return new LogQuery(users, usersNegated, from, to, timeText, radius, Set.copyOf(actions), List.copyOf(actionTexts),
                materials, materialsNegated, world, at, flags.isEmpty() ? Set.of() : Set.copyOf(flags));
    }

    private static Radius radius(String value) throws QuerySyntaxException {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "#global" -> {
                return Radius.GLOBAL;
            }
            case "#world" -> {
                return Radius.WORLD;
            }
            case "#chunk" -> {
                return Radius.CHUNK;
            }
            case "#worldedit", "#we" -> {
                return Radius.SELECTION;
            }
            default -> {
                try {
                    int size = Integer.parseInt(value);
                    if (size < 0 || size > MAX_RADIUS) throw new QuerySyntaxException("bad-radius", value);
                    return Radius.cube(size);
                } catch (NumberFormatException e) {
                    throw new QuerySyntaxException("bad-radius", value);
                }
            }
        }
    }

    private static int[] position(String value) throws QuerySyntaxException {
        String[] parts = value.split(",");
        if (parts.length != 3) throw new QuerySyntaxException("bad-position", value);
        try {
            return new int[] {Integer.parseInt(parts[0].strip()), Integer.parseInt(parts[1].strip()), Integer.parseInt(parts[2].strip())};
        } catch (NumberFormatException e) {
            throw new QuerySyntaxException("bad-position", value);
        }
    }

    private static List<String> split(@Nullable String value) {
        if (value == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String p = part.strip();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }
}
