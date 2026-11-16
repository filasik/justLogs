package me.skerik.justlogs.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import me.skerik.justlogs.model.Action;

/**
 * Versioned schema. Rules:
 * <ul>
 *   <li>Append a new {@link Version} to {@link #VERSIONS} for every schema change; NEVER edit or reorder an old one
 *       (servers in the wild have already run it).</li>
 *   <li>Each version runs in one transaction and is recorded in the {@code schema} table. SQLite rolls a failed
 *       version back completely; MariaDB DDL auto-commits, so keep a version small and, where possible, idempotent.</li>
 *   <li>One list of statements for both dialects, with placeholders (see {@link #render}). A statement that only
 *       one dialect understands starts with {@code @mariadb:} or {@code @sqlite:} and is skipped on the other.</li>
 *   <li>Add an upgrade test for every new version: migrate to the previous version with
 *       {@link #migrate(Database, int)}, insert old-shaped data, migrate to latest, assert the data survived.</li>
 * </ul>
 */
public final class Migrations {

    /** Prefix of every table. Short on purpose; the usual {@code <id>_} would be {@code justlogs_} (docs/DECISIONS.md D4). */
    public static final String PREFIX = "jl_";

    private Migrations() {
    }

    public static String table(String name) {
        return PREFIX + name;
    }

    /**
     * @param sql         statements with placeholders
     * @param syncActions also make sure {@code jl_actions} contains every {@link Action} (idempotent)
     */
    record Version(List<String> sql, boolean syncActions) {
    }

    /** Frozen column group of v1 fact tables: position of the event. */
    private static final String POS = "world_id {U16} NOT NULL, x INT NOT NULL, y {I16} NOT NULL, z INT NOT NULL";

    private static final List<Version> VERSIONS = List.of(v1(), v2(), v3());

    private static Version v1() {
        List<String> s = new ArrayList<>();
        // ── dictionaries ───────────────────────────────────────────────────────────────
        s.add("CREATE TABLE {P}meta (k VARCHAR(32) NOT NULL PRIMARY KEY, v VARCHAR(255) NOT NULL)");
        s.add("CREATE TABLE {P}users (id {ID}, ident VARCHAR(36) NOT NULL, name VARCHAR(48) NOT NULL,"
                + " is_player {U8} NOT NULL DEFAULT 1, first_seen {U32} NOT NULL){TBL}");
        s.add("CREATE UNIQUE INDEX {P}users_ident ON {P}users (ident)");
        s.add("CREATE INDEX {P}users_name ON {P}users (name)");
        s.add("CREATE TABLE {P}user_names (user_id {U32} NOT NULL, since {U32} NOT NULL, name VARCHAR(48) NOT NULL,"
                + " PRIMARY KEY (user_id, since)){TBL}");
        s.add("CREATE INDEX {P}user_names_name ON {P}user_names (name)");
        s.add("CREATE TABLE {P}worlds (id {ID}, server VARCHAR(32) NOT NULL DEFAULT '', name VARCHAR(128) NOT NULL){TBL}");
        s.add("CREATE UNIQUE INDEX {P}worlds_name ON {P}worlds (server, name)");
        // id 0 means "not set" in fact tables, so no row with id 0 exists in the three dictionaries below
        s.add("CREATE TABLE {P}materials (id {ID}, name VARCHAR(128) NOT NULL){TBL}");
        s.add("CREATE UNIQUE INDEX {P}materials_name ON {P}materials (name)");
        s.add("CREATE TABLE {P}blockdata (id {ID}, data VARCHAR(512) NOT NULL){TBL}");
        s.add("CREATE UNIQUE INDEX {P}blockdata_data ON {P}blockdata (data)");
        s.add("CREATE TABLE {P}entities (id {ID}, name VARCHAR(128) NOT NULL){TBL}");
        s.add("CREATE UNIQUE INDEX {P}entities_name ON {P}entities (name)");
        s.add("CREATE TABLE {P}actions (id {U8} NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL,"
                + " category VARCHAR(16) NOT NULL){TBL}");
        s.add("CREATE UNIQUE INDEX {P}actions_name ON {P}actions (name)");

        // ── blocks ─────────────────────────────────────────────────────────────────────
        s.add("CREATE TABLE {P}block (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " action {U8} NOT NULL, old_mat {U16} NOT NULL DEFAULT 0, old_data {U32} NOT NULL DEFAULT 0,"
                + " new_mat {U16} NOT NULL DEFAULT 0, new_data {U32} NOT NULL DEFAULT 0,"
                + " flags {U8} NOT NULL DEFAULT 0){TBL_DYN}");
        s.add("CREATE INDEX {P}block_pos ON {P}block (world_id, x, z, y, time)");
        s.add("CREATE INDEX {P}block_user ON {P}block (user_id, time)");
        s.add("CREATE INDEX {P}block_time ON {P}block (time)");
        s.add("CREATE TABLE {P}block_nbt (block_id {U64} NOT NULL PRIMARY KEY, old_nbt {MBLOB} NULL, new_nbt {MBLOB} NULL){TBL_DYN}");
        s.add("CREATE TABLE {P}sign (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " action {U8} NOT NULL, side {U8} NOT NULL DEFAULT 0, color {U8} NOT NULL DEFAULT 0,"
                + " flags {U8} NOT NULL DEFAULT 0, old_text TEXT NULL, new_text TEXT NULL){TBL}");
        s.add("CREATE INDEX {P}sign_pos ON {P}sign (world_id, x, z, y, time)");
        s.add("CREATE INDEX {P}sign_user ON {P}sign (user_id, time)");
        s.add("CREATE INDEX {P}sign_time ON {P}sign (time)");

        // ── items and containers ───────────────────────────────────────────────────────
        s.add("CREATE TABLE {P}item_data (id {ID}, hash {HASH20} NOT NULL, mat {U16} NOT NULL, data {MBLOB} NOT NULL){TBL_DYN}");
        s.add("CREATE UNIQUE INDEX {P}item_data_hash ON {P}item_data (hash)");
        s.add("CREATE TABLE {P}container (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " action {U8} NOT NULL, holder_mat {U16} NOT NULL, mat {U16} NOT NULL, amount INT NOT NULL,"
                + " item_data_id {U32} NULL, flags {U8} NOT NULL DEFAULT 0){TBL}");
        s.add("CREATE INDEX {P}container_pos ON {P}container (world_id, x, z, y, time)");
        s.add("CREATE INDEX {P}container_user ON {P}container (user_id, time)");
        s.add("CREATE INDEX {P}container_mat ON {P}container (mat, time)");
        s.add("CREATE INDEX {P}container_time ON {P}container (time)");
        s.add("CREATE TABLE {P}item (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " action {U8} NOT NULL, mat {U16} NOT NULL, amount INT NOT NULL, item_data_id {U32} NULL,"
                + " flags {U8} NOT NULL DEFAULT 0){TBL}");
        s.add("CREATE INDEX {P}item_pos ON {P}item (world_id, x, z, y, time)");
        s.add("CREATE INDEX {P}item_user ON {P}item (user_id, time)");
        s.add("CREATE INDEX {P}item_mat ON {P}item (mat, time)");
        s.add("CREATE INDEX {P}item_time ON {P}item (time)");

        // ── entities ───────────────────────────────────────────────────────────────────
        s.add("CREATE TABLE {P}entity_data (id {ID}, data {MBLOB} NOT NULL){TBL_DYN}");
        s.add("CREATE TABLE {P}entity (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " action {U8} NOT NULL, entity_id {U16} NOT NULL, victim_user_id {U32} NULL,"
                + " cause {U8} NOT NULL DEFAULT 0, name VARCHAR(96) NULL, detail VARCHAR(255) NULL,"
                + " entity_data_id {U32} NULL, flags {U8} NOT NULL DEFAULT 0){TBL}");
        s.add("CREATE INDEX {P}entity_pos ON {P}entity (world_id, x, z, y, time)");
        s.add("CREATE INDEX {P}entity_user ON {P}entity (user_id, time)");
        s.add("CREATE INDEX {P}entity_victim ON {P}entity (victim_user_id, time)");
        s.add("CREATE INDEX {P}entity_time ON {P}entity (time)");

        // ── players ────────────────────────────────────────────────────────────────────
        s.add("CREATE TABLE {P}interact (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " action {U8} NOT NULL, mat {U16} NOT NULL){TBL}");
        s.add("CREATE INDEX {P}interact_pos ON {P}interact (world_id, x, z, y, time)");
        s.add("CREATE INDEX {P}interact_user ON {P}interact (user_id, time)");
        s.add("CREATE INDEX {P}interact_time ON {P}interact (time)");
        s.add("CREATE TABLE {P}chat (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " message TEXT NOT NULL){TBL}");
        s.add("CREATE INDEX {P}chat_user ON {P}chat (user_id, time)");
        s.add("CREATE INDEX {P}chat_time ON {P}chat (time)");
        s.add("@mariadb:CREATE FULLTEXT INDEX {P}chat_ft ON {P}chat (message)");
        s.add("CREATE TABLE {P}command (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " message TEXT NOT NULL){TBL}");
        s.add("CREATE INDEX {P}command_user ON {P}command (user_id, time)");
        s.add("CREATE INDEX {P}command_time ON {P}command (time)");
        s.add("@mariadb:CREATE FULLTEXT INDEX {P}command_ft ON {P}command (message)");
        s.add("CREATE TABLE {P}session (id {ROWID}, time {U32} NOT NULL, user_id {U32} NOT NULL, " + POS + ","
                + " action {U8} NOT NULL, ip_hash VARCHAR(43) NULL, ip VARCHAR(45) NULL){TBL}");
        s.add("CREATE INDEX {P}session_user ON {P}session (user_id, time)");
        s.add("CREATE INDEX {P}session_ip ON {P}session (ip_hash)");
        s.add("CREATE INDEX {P}session_time ON {P}session (time)");

        // ── aggregation, audit, web ────────────────────────────────────────────────────
        s.add("CREATE TABLE {P}chunk_activity (world_id {U16} NOT NULL, cx INT NOT NULL, cz INT NOT NULL,"
                + " day {U32} NOT NULL, grp {U8} NOT NULL, cnt {U32} NOT NULL,"
                + " PRIMARY KEY (world_id, cx, cz, day, grp)){TBL_NOROWID}");
        s.add("CREATE INDEX {P}chunk_activity_day ON {P}chunk_activity (world_id, day)");
        s.add("CREATE TABLE {P}rollbacks (id {ID}, time {U32} NOT NULL, user_id {U32} NOT NULL, source {U8} NOT NULL,"
                + " kind {U8} NOT NULL, status {U8} NOT NULL, query TEXT NOT NULL, bounds VARCHAR(512) NOT NULL,"
                + " blocks {U32} NOT NULL DEFAULT 0, items {U32} NOT NULL DEFAULT 0, entities {U32} NOT NULL DEFAULT 0,"
                + " chunks {U32} NOT NULL DEFAULT 0, duration_ms {U32} NOT NULL DEFAULT 0, undo_of {U32} NULL){TBL}");
        s.add("CREATE INDEX {P}rollbacks_time ON {P}rollbacks (time)");
        s.add("CREATE INDEX {P}rollbacks_user ON {P}rollbacks (user_id, time)");
        s.add("CREATE TABLE {P}audit (id {ID}, time {U32} NOT NULL, user_id {U32} NULL, source {U8} NOT NULL,"
                + " ip_hash VARCHAR(43) NULL, action VARCHAR(32) NOT NULL, detail TEXT NULL){TBL}");
        s.add("CREATE INDEX {P}audit_time ON {P}audit (time)");
        s.add("CREATE INDEX {P}audit_user ON {P}audit (user_id, time)");
        s.add("CREATE TABLE {P}web_sessions (id {ID}, token_hash {HASH32} NOT NULL, user_id {U32} NOT NULL,"
                + " perms {U32} NOT NULL, created {U32} NOT NULL, expires {U32} NOT NULL, last_seen {U32} NOT NULL,"
                + " ip_hash VARCHAR(43) NULL, user_agent VARCHAR(160) NULL, revoked {U8} NOT NULL DEFAULT 0){TBL}");
        s.add("CREATE UNIQUE INDEX {P}web_sessions_token ON {P}web_sessions (token_hash)");
        s.add("CREATE INDEX {P}web_sessions_user ON {P}web_sessions (user_id)");
        return new Version(List.copyOf(s), true);
    }

    /** v2: which block rows a rollback/restore touched, so /jo undo can reverse exactly that. */
    private static Version v2() {
        List<String> s = new ArrayList<>();
        s.add("CREATE TABLE {P}rollback_rows (rollback_id {U32} NOT NULL, block_id {U64} NOT NULL,"
                + " PRIMARY KEY (rollback_id, block_id)){TBL_NOROWID}");
        return new Version(List.copyOf(s), false);
    }

    /** v3: the rows of a container rollback (jl_container ids), kept apart from the block rows of v2. */
    private static Version v3() {
        List<String> s = new ArrayList<>();
        s.add("CREATE TABLE {P}rollback_crows (rollback_id {U32} NOT NULL, container_id {U64} NOT NULL,"
                + " PRIMARY KEY (rollback_id, container_id)){TBL_NOROWID}");
        return new Version(List.copyOf(s), false);
    }

    public static int latestVersion() {
        return VERSIONS.size();
    }

    /** @return the schema version after migrating */
    public static int migrate(Database db) throws SQLException {
        return migrate(db, VERSIONS.size());
    }

    /** Migrates up to {@code target} (tests use this to simulate an older install). */
    public static int migrate(Database db, int target) throws SQLException {
        return db.call(c -> {
            ensureVersionTable(c);
            int current = currentVersion(c);
            for (int v = current + 1; v <= Math.min(target, VERSIONS.size()); v++) {
                int version = v;
                Database.transaction(c, tx -> {
                    Version def = VERSIONS.get(version - 1);
                    for (String sql : def.sql()) {
                        String rendered = render(sql, db.dialect());
                        if (rendered.isEmpty()) continue;
                        try (Statement st = tx.createStatement()) {
                            st.execute(rendered);
                        }
                    }
                    if (def.syncActions()) syncActions(tx);
                    try (PreparedStatement ps = tx.prepareStatement("INSERT INTO " + table("schema") + " (version) VALUES (?)")) {
                        ps.setInt(1, version);
                        ps.executeUpdate();
                    }
                    return null;
                });
            }
            return currentVersion(c);
        });
    }

    /** Statements of one version rendered for a dialect (tests check that no placeholder is left). */
    static List<String> rendered(int version, DbConfig.Dialect dialect) {
        return VERSIONS.get(version - 1).sql().stream().map(sql -> render(sql, dialect)).filter(s -> !s.isEmpty()).toList();
    }

    /** Adds the actions missing from {@code jl_actions}; never touches existing rows. */
    private static void syncActions(Connection c) throws SQLException {
        Set<Integer> present = new HashSet<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id FROM " + table("actions"))) {
            while (rs.next()) present.add(rs.getInt(1));
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table("actions") + " (id, name, category) VALUES (?, ?, ?)")) {
            for (Action a : Action.values()) {
                if (present.contains(a.id())) continue;
                ps.setInt(1, a.id());
                ps.setString(2, a.key());
                ps.setString(3, a.category().name().toLowerCase(java.util.Locale.ROOT));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void ensureVersionTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + table("schema") + " (version INT NOT NULL PRIMARY KEY)");
        }
    }

    private static int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT MAX(version) FROM " + table("schema"))) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Placeholders: {@code {P}} table prefix, {@code {ID}} auto-increment key of a small table, {@code {ROWID}} key of a
     * fact table (SQLite: plain rowid, no AUTOINCREMENT, faster inserts), {@code {U8} {U16} {U32} {U64}} unsigned integers
     * (SQLite has one integer type), {@code {I16}} signed 16 bit, {@code {BLOB} {MBLOB} {HASH20} {HASH32}} binary columns,
     * {@code {TBL} {TBL_DYN} {TBL_NOROWID}} table options (engine / row format; SQLite: nothing or WITHOUT ROWID).
     * Returns an empty string for a statement meant for the other dialect.
     */
    static String render(String sql, DbConfig.Dialect dialect) {
        String s = sql;
        if (s.startsWith("@mariadb:")) {
            if (dialect != DbConfig.Dialect.MARIADB) return "";
            s = s.substring("@mariadb:".length());
        } else if (s.startsWith("@sqlite:")) {
            if (dialect != DbConfig.Dialect.SQLITE) return "";
            s = s.substring("@sqlite:".length());
        }
        s = s.replace("{P}", PREFIX);
        boolean sqlite = dialect == DbConfig.Dialect.SQLITE;
        return s.replace("{ID}", sqlite ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "INT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY")
                .replace("{ROWID}", sqlite ? "INTEGER PRIMARY KEY" : "BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY")
                .replace("{U8}", sqlite ? "INTEGER" : "TINYINT UNSIGNED")
                .replace("{U16}", sqlite ? "INTEGER" : "SMALLINT UNSIGNED")
                .replace("{U32}", sqlite ? "INTEGER" : "INT UNSIGNED")
                .replace("{U64}", sqlite ? "INTEGER" : "BIGINT UNSIGNED")
                .replace("{I16}", sqlite ? "INTEGER" : "SMALLINT")
                .replace("{BLOB}", sqlite ? "BLOB" : "LONGBLOB")
                .replace("{MBLOB}", sqlite ? "BLOB" : "MEDIUMBLOB")
                .replace("{HASH20}", sqlite ? "BLOB" : "BINARY(20)")
                .replace("{HASH32}", sqlite ? "BLOB" : "BINARY(32)")
                .replace("{TBL_DYN}", sqlite ? "" : " ENGINE=InnoDB ROW_FORMAT=DYNAMIC")
                .replace("{TBL_NOROWID}", sqlite ? " WITHOUT ROWID" : " ENGINE=InnoDB")
                .replace("{TBL}", sqlite ? "" : " ENGINE=InnoDB");
    }
}
