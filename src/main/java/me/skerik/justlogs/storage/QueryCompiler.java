package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
 * Paging is keyset based ({@code (time, id) < cursor}), so page 500 costs the same as page 1.
 */
public final class QueryCompiler {

    /** Position after the last row of a page. */
    public record Cursor(int time, long id) {

        public String encode() {
            return time + "." + id;
        }

        public static @Nullable Cursor decode(@Nullable String text) {
            if (text == null) return null;
            int dot = text.indexOf('.');
            if (dot <= 0) return null;
            try {
                return new Cursor(Integer.parseInt(text.substring(0, dot)), Long.parseLong(text.substring(dot + 1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** One page of results. {@code next} is null on the last page. */
    public record Page(List<LogEntry> rows, @Nullable Cursor next) {
    }

    /** Which table a query reads: container actions only (a:container, a:deposit...) go to jl_container, the rest to jl_block. */
    public static Category categoryOf(LogQuery q) {
        if (q.actions().isEmpty()) return Category.BLOCK;
        boolean allContainer = true;
        boolean allSign = true;
        for (Action a : q.actions()) {
            if (a.category() != Category.CONTAINER) allContainer = false;
            if (a.category() != Category.SIGN) allSign = false;
        }
        return allContainer ? Category.CONTAINER : allSign ? Category.SIGN : Category.BLOCK;
    }

    private static String table(Category cat) {
        return Migrations.table(cat == Category.CONTAINER ? "container" : cat == Category.SIGN ? "sign" : "block");
    }

    private final IdCache ids;

    public QueryCompiler(IdCache ids) {
        this.ids = ids;
    }

    /** Runs the lookup on the given connection (call it from {@code Database.supply}). */
    public Page lookup(Connection c, LogQuery query, Origin origin, @Nullable Cursor cursor, int limit)
            throws SQLException, QuerySyntaxException {
        Category cat = categoryOf(query);
        Where where = where(c, query, origin, cursor, cat);
        if (where == null) return new Page(List.of(), null);
        String sql = (cat == Category.CONTAINER ? ContainerStore.SELECT : cat == Category.SIGN ? SignStore.SELECT
                : BlockStore.SELECT) + where.sql
                + " ORDER BY b.time DESC, b.id DESC LIMIT " + (limit + 1);
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, where.params);
            try (ResultSet rs = ps.executeQuery()) {
                List<LogEntry> rows = new ArrayList<>(cat == Category.CONTAINER ? ContainerStore.read(rs, limit + 1)
                        : cat == Category.SIGN ? SignStore.read(rs, limit + 1) : BlockStore.read(rs, limit + 1));
                if (rows.size() > limit) {
                    rows = new ArrayList<>(rows.subList(0, limit));
                    LogEntry last = rows.get(rows.size() - 1);
                    return new Page(rows, new Cursor(last.time(), last.id()));
                }
                return new Page(rows, null);
            }
        }
    }

    /** Number of matching rows, counted up to {@code cap} (so a huge result does not scan everything). */
    public int count(Connection c, LogQuery query, Origin origin, int cap) throws SQLException, QuerySyntaxException {
        Category cat = categoryOf(query);
        Where where = where(c, query, origin, null, cat);
        if (where == null) return 0;
        String sql = "SELECT COUNT(*) FROM (SELECT 1 FROM " + table(cat) + " b" + where.sql + " LIMIT " + cap + ") t";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, where.params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
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
            List<Integer> blockActions = new ArrayList<>();
            for (Action a : q.actions()) if (a.category() == cat) blockActions.add(a.id());
            if (blockActions.isEmpty()) return null;
            conditions.add("b.action IN (" + placeholders(blockActions.size()) + ")");
            params.addAll(blockActions);
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
            if (cat == Category.SIGN) return null; // signs have no material filter
            List<Integer> matIds = ids.findMaterials(c, q.materials());
            if (matIds.isEmpty()) {
                if (!q.materialsNegated()) return null;
            } else {
                String in = placeholders(matIds.size());
                String clause = cat == Category.CONTAINER ? "b.mat IN (" + in + ")"
                        : "(b.old_mat IN (" + in + ") OR b.new_mat IN (" + in + "))";
                conditions.add(q.materialsNegated() ? "NOT " + clause : clause);
                params.addAll(matIds);
                if (cat != Category.CONTAINER) params.addAll(matIds);
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
            conditions.add("(b.time < ? OR (b.time = ? AND b.id < ?))");
            params.add(cursor.time());
            params.add(cursor.time());
            params.add(cursor.id());
        }

        if (conditions.isEmpty()) return new Where("", params);
        StringJoiner join = new StringJoiner(" AND ", " WHERE ", "");
        conditions.forEach(join::add);
        return new Where(join.toString(), params);
    }

    private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Long l) ps.setLong(i + 1, l);
            else ps.setInt(i + 1, (Integer) p);
        }
    }

    private static String placeholders(int n) {
        return "?" + ", ?".repeat(Math.max(0, n - 1));
    }
}
