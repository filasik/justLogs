package me.skerik.justlogs.query;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.Category;

/** The {@code a:} parameter: group names ({@code block}, {@code +block}, {@code -container}) and single actions. */
public final class ActionGroups {

    /** Names suggested by tab completion. */
    public static final java.util.List<String> NAMES = java.util.List.of(
            "block", "+block", "-block", "container", "+container", "-container", "item", "kill", "click", "sign",
            "chat", "command", "session", "+session", "-session", "entity");

    private ActionGroups() {
    }

    /** @return the actions the text stands for, empty if it is neither a group nor an action */
    public static Optional<Set<Action>> resolve(String text) {
        String s = text.toLowerCase(Locale.ROOT).strip();
        int direction = 0;
        if (s.startsWith("+")) {
            direction = 1;
            s = s.substring(1);
        } else if (s.startsWith("-")) {
            direction = -1;
            s = s.substring(1);
        }
        Category category = switch (s) {
            case "block" -> Category.BLOCK;
            case "container" -> Category.CONTAINER;
            case "item" -> Category.ITEM;
            case "sign" -> Category.SIGN;
            case "session" -> Category.SESSION;
            case "click", "interact" -> Category.INTERACT;
            case "chat" -> Category.CHAT;
            case "entity" -> Category.ENTITY;
            case "kill" -> null;
            default -> null;
        };
        Set<Action> out = EnumSet.noneOf(Action.class);
        if (category != null) {
            for (Action a : Action.values()) {
                if (a.category() == category && (direction == 0 || a.direction() == direction)) out.add(a);
            }
        } else if (s.equals("kill")) {
            out.add(Action.KILL);
            out.add(Action.PLAYER_DEATH);
        } else if (s.equals("command")) {
            out.add(Action.COMMAND);
        } else if (direction == 0) {
            Optional<Action> single = Action.byKey(s);
            if (single.isPresent()) out.add(single.get());
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }
}
