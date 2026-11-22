package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import me.skerik.justlogs.model.Action;
import me.skerik.justlogs.model.BlockEntry;
import me.skerik.justlogs.model.Category;
import me.skerik.justlogs.model.ContainerEntry;
import me.skerik.justlogs.model.LogEntry;
import me.skerik.justlogs.query.LogQuery;
import me.skerik.justlogs.query.Origin;
import me.skerik.justlogs.query.QuerySyntaxException;
import me.skerik.justlogs.query.Radius;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link LogQuery} into SQL (parameterized, nothing from the user ever lands in the SQL text) and runs it.
 * Paging is keyset based ({@code (time, id) < cursor}), so page 500 costs the same as page 1. Multi-category queries
 * merge-sort across each matching table with keyset {@code (time, category, id)}.
 */
public final class QueryCompiler {

    /** Position after the last row of a page. {@code cat} is -1 for single-category queries. */
    public record Cursor(int time, long id, int cat) {

        public Cursor(int time, long id) {
            this(time, id, -1);
        }

        public String encode() {
            return cat < 0 ? time + "." + id : time + "." + id + "." + cat;
        }

        public static @Nullable Cursor decode(@Nullable String text) {
            if (text == null) return null;
            int dot = text.indexOf('.');
            if (dot <= 0) return null;
            int dot2 = text.indexOf('.', dot + 1);
            try {
                int time = Integer.parseInt(text.substring(0, dot));
                if (dot2 < 0) {
                    return new Cursor(time, Long.parseLong(text.substring(dot + 1)), -1);
                }
                long id = Long.parseLong(text.substring(dot + 1, dot2));
                int cat = Integer.parseInt(text.substring(dot2 + 1));
                return new Cursor(time, id, cat);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** One page of results. {@code next} is null on the last page. */
    public record Page(List<LogEntry> rows, @Nullable Cursor next) {
    }

    /** Primary category of a query (used by rollback/restore). */
    public static Category categoryOf(LogQuery q) {
        return categoriesOf(q).getFirst();
    }

    /** All categories a query reads. */
    public static List<Category> categoriesOf(LogQuery q) {
        if (!q.actions().isEmpty()) {
            Set<Category> set = EnumSet.noneOf(Category.class);
            for (Action a : q.actions()) set.add(a.category());
            return List.copyOf(set);
        }
        if (!q.entities().isEmpty()) return List.of(Category.ENTITY);
        if (q.keyword() != null) return List.of(Category.CHAT, Category.COMMAND, Category.SIGN);
        return List.of(Category.BLOCK);
    }

    private final IdCache ids;

    public QueryCompiler(IdCache ids) {
        this.ids = ids;
    }

    /** Runs the lookup on the given connection (call it from {@code Database.supply}). */
    public Page lookup(Connection c, LogQuery query, Origin origin, @Nullable Cursor cursor, int limit)
            throws SQLException, QuerySyntaxException {
        List<Category> cats = categoriesOf(query);
        if (cats.size() == 1) {
            Category cat = cats.getFirst();
            List<LogEntry> rows = queryCategory(c, query, origin, cursor, cat, limit + 1);
            if (rows.size() > limit) {
                List<LogEntry> page = new ArrayList<>(rows.subList(0, limit));
                LogEntry last = page.getLast();
                return new Page(page, new Cursor(last.time(), last.id()));
            }
            return new Page(rows, null);
        }
        List<LogEntry> merged = new ArrayList<>();
        for (Category cat : cats) {
            merged.addAll(queryCategory(c, query, origin, cursor, cat, limit + 1));
        }
        merged.sort(Comparator.comparingInt(LogEntry::time).reversed()
                .thenComparing(Comparator.comparingInt((LogEntry e) -> e.action().category().ordinal()).reversed())
                .thenComparing(Comparator.comparingLong(LogEntry::id).reversed()));
        if (merged.size() > limit) {
            List<LogEntry> page = new ArrayList<>(merged.subList(0, limit));
            LogEntry last = page.getLast();
            return new Page(page, new Cursor(last.time(), last.id(), last.action().category().ordinal()));
        }
        return new Page(merged, null);
    }

    private List<LogEntry> queryCategory(Connection c, LogQuery query, Origin origin, @Nullable Cursor cursor,
                                         Category cat, int max) throws SQLException, QuerySyntaxException {
        Where where = where(c, query, origin, cursor, cat);
        if (where == null) return List.of();
        String sql = selectFor(cat) + where.sql + " ORDER BY b.time DESC, b.id DESC LIMIT " + max;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, where.params);
            try (ResultSet rs = ps.executeQuery()) {
                return readFor(cat, rs, max);
            }
        }
    }

    private static String selectFor(Category cat) {
        return switch (cat) {
            case BLOCK -> BlockStore.SELECT;
            case SIGN -> SignStore.SELECT;
            case CONTAINER -> ContainerStore.SELECT;
            case ITEM -> ItemStore.SELECT;
            case ENTITY -> EntityStore.SELECT;
            case INTERACT -> InteractStore.SELECT;
            case CHAT -> TextStore.SELECT_CHAT;
            case COMMAND -> TextStore.SELECT_COMMAND;
            case SESSION -> SessionStore.SELECT;
        };
    }

    private static List<LogEntry> readFor(Category cat, ResultSet rs, int max) throws SQLException {
        return switch (cat) {
            case BLOCK -> List.copyOf(BlockStore.read(rs, max));
            case SIGN -> List.copyOf(SignStore.read(rs, max));
            case CONTAINER -> List.copyOf(ContainerStore.read(rs, max));
            case ITEM -> List.copyOf(ItemStore.read(rs, max));
            case ENTITY -> List.copyOf(EntityStore.read(rs, max));
            case INTERACT -> List.copyOf(InteractStore.read(rs, max));
            case CHAT -> List.copyOf(TextStore.read(rs, Action.CHAT, max));
            case COMMAND -> List.copyOf(TextStore.read(rs, Action.COMMAND, max));
            case SESSION -> List.copyOf(SessionStore.read(rs, max));
        };
    }

    /** Number of matching rows, counted up to {@code cap} (so a huge result does not scan everything). */
    public int count(Connection c, LogQuery query, Origin origin, int cap) throws SQLException, QuerySyntaxException {
        int total = 0;
        for (Category cat : categoriesOf(query)) {
            if (total >= cap) break;
            Where where = where(c, query, origin, null, cat);
            if (where == null) continue;
            int remaining = cap - total;
            String sql = "SELECT COUNT(*) FROM (SELECT 1 FROM " + Migrations.table(cat.table()) + " b" + where.sql
                    + " LIMIT " + remaining + ") t";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, where.params);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total += rs.getInt(1);
                }
            }
        }
        return Math.min(total, cap);
    }

    /**
     * Rows a rollback or restore would touch, oldest first. {@code rolledBack}: false = rows not yet rolled back
     * (rollback), true = rows that were rolled back (restore). Returns at most {@code max + 1} rows so the caller
     * can tell "too many".
     */
    public List<BlockEntry> forChange(Connection c, LogQuery query, Origin origin, boolean rolledBack, int max)
            throws SQLException, QuerySyntaxException {
        Where where = where(c, query, origin, null, Category.BLOCK);
        if (where == null) return List.of();
        String sql = BlockStore.SELECT + where.sql + (where.sql.isEmpty() ? " WHERE " : " AND ")
                + "(b.flags & 1) = " + (rolledBack ? 1 : 0) + " ORDER BY b.time ASC, b.id ASC LIMIT " + (max + 1);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, where.params);
            try (ResultSet rs = ps.executeQuery()) {
                return BlockStore.read(rs, max + 1);
            }
        }
    }

    /** Container version of {@link #forChange}: rows with their item bytes, oldest first. */
    public List<ContainerEntry> forContainerChange(Connection c, LogQuery query, Origin origin, boolean rolledBack, int max)
            throws SQLException, QuerySyntaxException {
        Where where = where(c, query, origin, null, Category.CONTAINER);
        if (where == null) return List.of();
        String sql = ContainerStore.SELECT_WITH_DATA + where.sql + (where.sql.isEmpty() ? " WHERE " : " AND ")
                + "(b.flags & 1) = " + (rolledBack ? 1 : 0) + " ORDER BY b.time ASC, b.id ASC LIMIT " + (max + 1);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, where.params);
            try (ResultSet rs = ps.executeQuery()) {
                return ContainerStore.read(rs, max + 1);
            }
        }
    }

    private record Where(String sql, List<Object> params) {
    }

    /** @return null when the filters cannot match anything (unknown user, world, material...) */
    private @Nullable Where where(Connection c, LogQuery q, Origin origin, @Nullable Cursor cursor, Category cat)
            throws SQLException, QuerySyntaxException {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (!q.actions().isEmpty()) {
            List<Integer> catActions = new ArrayList<>();
            for (Action a : q.actions()) if (a.category() == cat) catActions.add(a.id());
            if (catActions.isEmpty()) return null;
            // jl_chat and jl_command have no action column (each table holds a single action)
            if (cat != Category.CHAT && cat != Category.COMMAND) {
                conditions.add("b.action IN (" + placeholders(catActions.size()) + ")");
                params.addAll(catActions);
            }
        }

        if (!q.users().isEmpty()) {
            List<Integer> userIds = ids.findUsers(c, q.users());
            if (userIds.isEmpty()) {
                if (!q.usersNegated()) return null;
            } else {
                conditions.add("b.user_id " + (q.usersNegated() ? "NOT IN" : "IN") + " (" + placeholders(userIds.size()) + ")");
                params.addAll(userIds);
            }
        }

        if (!q.materials().isEmpty()) {
            if (cat != Category.BLOCK && cat != Category.CONTAINER && cat != Category.ITEM && cat != Category.INTERACT) {
                return null;
            }
            List<Integer> matIds = ids.findMaterials(c, q.materials());
            if (matIds.isEmpty()) {
                if (!q.materialsNegated()) return null;
            } else {
                String in = placeholders(matIds.size());
                String clause = cat == Category.BLOCK
                        ? "(b.old_mat IN (" + in + ") OR b.new_mat IN (" + in + "))"
                        : "b.mat IN (" + in + ")";
                conditions.add(q.materialsNegated() ? "NOT " + clause : clause);
                params.addAll(matIds);
                if (cat == Category.BLOCK) params.addAll(matIds);
            }
        }

        if (!q.entities().isEmpty()) {
            if (cat != Category.ENTITY) return null;
            List<Integer> entityIds = ids.findEntities(c, q.entities());
            if (entityIds.isEmpty()) {
                if (!q.entitiesNegated()) return null;
            } else {
                conditions.add("b.entity_id " + (q.entitiesNegated() ? "NOT IN" : "IN")
                        + " (" + placeholders(entityIds.size()) + ")");
                params.addAll(entityIds);
            }
        }

        if (q.keyword() != null) {
            String like = "%" + escapeLike(q.keyword().toLowerCase(Locale.ROOT)) + "%";
            if (cat == Category.CHAT || cat == Category.COMMAND) {
                conditions.add("LOWER(b.message) LIKE ? ESCAPE '\\'");
                params.add(like);
            } else if (cat == Category.SIGN) {
                conditions.add("(LOWER(b.old_text) LIKE ? ESCAPE '\\' OR LOWER(b.new_text) LIKE ? ESCAPE '\\')");
                params.add(like);
                params.add(like);
            } else {
                return null;
            }
        }

        if (q.fromSec() > 0) {
            conditions.add("b.time >= ?");
            params.add((int) q.fromSec());
        }
        if (q.toSec() > 0) {
            conditions.add("b.time <= ?");
            params.add((int) q.toSec());
        }

        // Space: an explicit world wins, otherwise the radius decides which world applies
        Radius radius = q.radius();
        Origin centre = q.at() != null
                ? new Origin(q.world() != null ? q.world() : origin.world(), q.at()[0], q.at()[1], q.at()[2])
                : origin;
        String worldName = q.world();
        switch (radius.kind()) {
            case CUBE, CHUNK -> {
                if (q.at() == null && !origin.hasWorld()) throw new QuerySyntaxException("need-position", q.canonical());
                if (worldName == null) worldName = centre.world();
            }
            case WORLD -> {
                if (worldName == null) worldName = origin.world();
                if (worldName == null) throw new QuerySyntaxException("need-world", q.canonical());
            }
            case SELECTION -> throw new QuerySyntaxException("selection-unsupported", q.canonical());
            case GLOBAL, NONE -> {
            }
        }
        if (worldName != null) {
            int worldId = ids.findWorld(c, worldName);
            if (worldId < 0) return null;
            conditions.add("b.world_id = ?");
            params.add(worldId);
        }
        switch (radius.kind()) {
            case CUBE -> {
                int r = radius.size();
                conditions.add("b.x BETWEEN ? AND ?");
                params.add(centre.x() - r);
                params.add(centre.x() + r);
                conditions.add("b.z BETWEEN ? AND ?");
                params.add(centre.z() - r);
                params.add(centre.z() + r);
                conditions.add("b.y BETWEEN ? AND ?");
                params.add(centre.y() - r);
                params.add(centre.y() + r);
            }
            case CHUNK -> {
                int minX = Math.floorDiv(centre.x(), 16) * 16;
                int minZ = Math.floorDiv(centre.z(), 16) * 16;
                conditions.add("b.x BETWEEN ? AND ?");
                params.add(minX);
                params.add(minX + 15);
                conditions.add("b.z BETWEEN ? AND ?");
                params.add(minZ);
                params.add(minZ + 15);
            }
            default -> {
            }
        }

        if (cursor != null) {
            int catOrd = cat.ordinal();
            if (cursor.cat() < 0 || cursor.cat() == catOrd) {
                conditions.add("(b.time < ? OR (b.time = ? AND b.id < ?))");
                params.add(cursor.time());
                params.add(cursor.time());
                params.add(cursor.id());
            } else if (catOrd > cursor.cat()) {
                conditions.add("b.time < ?");
                params.add(cursor.time());
            } else {
                conditions.add("b.time <= ?");
                params.add(cursor.time());
            }
        }

        if (conditions.isEmpty()) return new Where("", params);
        StringJoiner join = new StringJoiner(" AND ", " WHERE ", "");
        conditions.forEach(join::add);
        return new Where(join.toString(), params);
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            switch (p) {
                case Long l -> ps.setLong(i + 1, l);
                case String s -> ps.setString(i + 1, s);
                default -> ps.setInt(i + 1, (Integer) p);
            }
        }
    }

    private static String placeholders(int n) {
        return "?" + ", ?".repeat(Math.max(0, n - 1));
    }
}
