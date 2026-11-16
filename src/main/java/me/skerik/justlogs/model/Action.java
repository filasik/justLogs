package me.skerik.justlogs.model;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the plugin can log. The numeric ids are stored in the database: NEVER change or reuse one,
 * only append. {@link #direction()} says whether the action adds (+1), removes (-1) or merely changes (0)
 * something; it drives the {@code a:+block} / {@code a:-block} query groups.
 */
public enum Action {
    BREAK(1, Category.BLOCK, "break", -1),
    PLACE(2, Category.BLOCK, "place", 1),
    BURN(3, Category.BLOCK, "burn", -1),
    IGNITE(4, Category.BLOCK, "ignite", 1),
    EXPLODE(5, Category.BLOCK, "explode", -1),
    FLOW(6, Category.BLOCK, "flow", 1),
    FALL(7, Category.BLOCK, "fall", 0),
    GROW(8, Category.BLOCK, "grow", 1),
    FORM(9, Category.BLOCK, "form", 1),
    FADE(10, Category.BLOCK, "fade", -1),
    SPREAD(11, Category.BLOCK, "spread", 1),
    DECAY(12, Category.BLOCK, "decay", -1),
    PISTON(13, Category.BLOCK, "piston", 0),
    ENTITY_CHANGE(14, Category.BLOCK, "entity-change", 0),
    BUCKET_EMPTY(15, Category.BLOCK, "bucket-empty", 1),
    BUCKET_FILL(16, Category.BLOCK, "bucket-fill", -1),
    DISPENSE(17, Category.BLOCK, "dispense", 1),
    WORLDEDIT(18, Category.BLOCK, "worldedit", 0),

    SIGN_PLACE(20, Category.SIGN, "sign-place", 1),
    SIGN_EDIT(21, Category.SIGN, "sign-edit", 0),
    SIGN_BREAK(22, Category.SIGN, "sign-break", -1),

    DEPOSIT(30, Category.CONTAINER, "deposit", 1),
    WITHDRAW(31, Category.CONTAINER, "withdraw", -1),

    DROP(40, Category.ITEM, "drop", -1),
    PICKUP(41, Category.ITEM, "pickup", 1),
    DEATH_DROP(42, Category.ITEM, "death-drop", -1),
    CRAFT(43, Category.ITEM, "craft", 1),
    FRAME_PUT(44, Category.ITEM, "frame-put", 1),
    FRAME_TAKE(45, Category.ITEM, "frame-take", -1),
    STAND_PUT(46, Category.ITEM, "stand-put", 1),
    STAND_TAKE(47, Category.ITEM, "stand-take", -1),

    KILL(50, Category.ENTITY, "kill", -1),
    PLAYER_DEATH(51, Category.ENTITY, "player-death", -1),
    HANGING_PLACE(52, Category.ENTITY, "hanging-place", 1),
    HANGING_BREAK(53, Category.ENTITY, "hanging-break", -1),
    VEHICLE_BREAK(54, Category.ENTITY, "vehicle-break", -1),
    TAME(55, Category.ENTITY, "tame", 0),
    SHEAR(56, Category.ENTITY, "shear", 0),
    LEASH(57, Category.ENTITY, "leash", 0),
    BUCKET_ENTITY(58, Category.ENTITY, "bucket-entity", -1),

    CLICK(60, Category.INTERACT, "click", 0),
    CONTAINER_OPEN(61, Category.INTERACT, "container-open", 0),

    CHAT(70, Category.CHAT, "chat", 0),
    COMMAND(71, Category.COMMAND, "command", 0),

    LOGIN(80, Category.SESSION, "login", 1),
    LOGOUT(81, Category.SESSION, "logout", -1);

    private static final Action[] BY_ID = new Action[128];
    private static final Map<String, Action> BY_NAME = new HashMap<>();

    static {
        for (Action a : values()) {
            if (BY_ID[a.id] != null) throw new IllegalStateException("Duplicate action id " + a.id);
            BY_ID[a.id] = a;
            BY_NAME.put(a.key, a);
        }
    }

    private final int id;
    private final Category category;
    private final String key;
    private final int direction;

    Action(int id, Category category, String key, int direction) {
        this.id = id;
        this.category = category;
        this.key = key;
        this.direction = direction;
    }

    public int id() {
        return id;
    }

    public Category category() {
        return category;
    }

    /** Lower-case name used in queries and stored in {@code jl_actions}. */
    public String key() {
        return key;
    }

    /** +1 adds something, -1 removes something, 0 neutral. */
    public int direction() {
        return direction;
    }

    public static Optional<Action> byId(int id) {
        return id >= 0 && id < BY_ID.length ? Optional.ofNullable(BY_ID[id]) : Optional.empty();
    }

    public static Optional<Action> byKey(String key) {
        return Optional.ofNullable(BY_NAME.get(key.toLowerCase(Locale.ROOT)));
    }
}
