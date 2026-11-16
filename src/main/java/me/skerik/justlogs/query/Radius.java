package me.skerik.justlogs.query;

/**
 * Spatial scope of a query. {@code size} is the half edge of the cube for {@link Kind#CUBE}.
 */
public record Radius(Kind kind, int size) {

    public enum Kind { NONE, CUBE, GLOBAL, WORLD, CHUNK, SELECTION }

    public static final Radius NONE = new Radius(Kind.NONE, 0);
    public static final Radius GLOBAL = new Radius(Kind.GLOBAL, 0);
    public static final Radius WORLD = new Radius(Kind.WORLD, 0);
    public static final Radius CHUNK = new Radius(Kind.CHUNK, 0);
    public static final Radius SELECTION = new Radius(Kind.SELECTION, 0);

    public static Radius cube(int size) {
        return new Radius(Kind.CUBE, size);
    }
}
