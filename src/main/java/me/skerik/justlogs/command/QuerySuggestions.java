package me.skerik.justlogs.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.query.ActionGroups;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Tab completion for the free-form query: first the parameter names, then values that fit the parameter. */
final class QuerySuggestions {

    private static final List<String> KEYS = List.of("u:", "t:", "r:", "a:", "b:", "w:", "at:", "#verbose", "#count", "#silent");
    private static final List<String> TIMES = List.of("30m", "1h", "12h", "1d", "3d", "7d", "2d-1d");
    private static final List<String> RADII = List.of("5", "10", "25", "50", "#world", "#chunk", "#global");
    private static final int MAX_VALUES = 60;

    private QuerySuggestions() {
    }

    static SuggestionProvider<CommandSourceStack> provider() {
        return (context, builder) -> {
            String remaining = builder.getRemaining();
            int tokenStart = remaining.lastIndexOf(' ') + 1;
            String token = remaining.substring(tokenStart);
            SuggestionsBuilder b = builder.createOffset(builder.getStart() + tokenStart);
            int colon = token.indexOf(':');
            if (colon < 0) {
                String lower = token.toLowerCase(Locale.ROOT);
                KEYS.stream().filter(k -> k.startsWith(lower)).forEach(b::suggest);
                return b.buildFuture();
            }
            String key = token.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = token.substring(colon + 1);
            int comma = value.lastIndexOf(',');
            String typed = value.substring(comma + 1);
            boolean negation = typed.startsWith("!") && (key.equals("u") || key.equals("b"));
            String head = token.substring(0, colon + 1) + value.substring(0, comma + 1) + (negation ? "!" : "");
            String prefix = (negation ? typed.substring(1) : typed).toLowerCase(Locale.ROOT);
            values(key).filter(v -> v.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(MAX_VALUES)
                    .forEach(v -> b.suggest(head + v));
            return b.buildFuture();
        };
    }

    private static Stream<String> values(String key) {
        return switch (key) {
            case "u" -> Bukkit.getOnlinePlayers().stream().map(Player::getName);
            case "t" -> TIMES.stream();
            case "r" -> RADII.stream();
            case "a" -> Stream.concat(ActionGroups.NAMES.stream(), Stream.of(Action.values()).map(Action::key));
            case "b" -> Stream.of(Material.values()).filter(m -> !m.isLegacy() && (m.isBlock() || m.isItem()))
                    .map(m -> m.name().toLowerCase(Locale.ROOT));
            case "w" -> Bukkit.getWorlds().stream().map(World::getName);
            default -> Stream.empty();
        };
    }
}
