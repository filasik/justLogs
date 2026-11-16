package me.skerik.justlogs.model;

/** Log category. Each one has its own fact table ({@code jl_<table>}); chat and command rows share the layout. */
public enum Category {
    BLOCK("block"),
    SIGN("sign"),
    CONTAINER("container"),
    ITEM("item"),
    ENTITY("entity"),
    INTERACT("interact"),
    CHAT("chat"),
    COMMAND("command"),
    SESSION("session");

    private final String table;

    Category(String table) {
        this.table = table;
    }

    /** Table name without the {@code jl_} prefix. */
    public String table() {
        return table;
    }
}
