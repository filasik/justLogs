package me.skerik.justlogs.command;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import me.skerik.justlogs.config.Messages;
import me.skerik.justlogs.model.BlockEntry;
import me.skerik.justlogs.model.ContainerEntry;
import me.skerik.justlogs.model.SignEntry;
import me.skerik.justlogs.model.LogEntry;
import me.skerik.justlogs.util.Durations;
import me.skerik.justlogs.util.Keys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** Builds the chat lines of a result page: header, rows with clickable position and user, page footer. */
final class LookupRenderer {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private LookupRenderer() {
    }

    static List<Component> page(Messages m, ResultCache.Entry entry, List<LogEntry> rows, int page, boolean hasNext,
                                long nowSec, boolean verbose) {
        List<Component> out = new ArrayList<>();
        if (entry.spot()) {
            out.add(m.get("lookup.header-spot", Messages.text("x", String.valueOf(entry.spotX())),
                    Messages.text("y", String.valueOf(entry.spotY())), Messages.text("z", String.valueOf(entry.spotZ())),
                    Messages.text("world", entry.spotWorld())));
        } else {
            out.add(m.get("lookup.header", Messages.text("query", entry.query().canonical())));
        }
        for (LogEntry row : rows) {
            Component line = row(m, row, nowSec);
            out.add(row.rolledBack() ? line.decorate(TextDecoration.STRIKETHROUGH) : line);
            if (verbose) out.add(detail(m, row));
        }
        out.add(footer(m, entry, page, hasNext));
        return out;
    }

    private static Component row(Messages m, LogEntry e, long nowSec) {
        Component user = m.get("lookup.user", Messages.text("name", e.user()),
                Messages.comp("tooltip", m.get("lookup.user-hover", Messages.text("name", e.user()))));
        Component action = switch (e) {
            case BlockEntry b -> m.get("action." + e.action().key(),
                    Messages.text("old", Keys.pretty(b.oldMaterial())), Messages.text("new", Keys.pretty(b.newMaterial())));
            case ContainerEntry c -> m.get("action." + e.action().key(), Messages.text("amount", String.valueOf(c.amount())),
                    Messages.text("item", Keys.pretty(c.material())), Messages.text("holder", Keys.pretty(c.holderMaterial())),
                    Messages.text("data", c.hasData() ? "*" : ""));
            case SignEntry s -> m.get("action." + e.action().key(), Messages.text("text", oneLine(s.newText())),
                    Messages.text("side", m.raw(s.side() == 1 ? "sign.back" : "sign.front")));
        };
        Component pos = m.get("lookup.pos", Messages.text("x", String.valueOf(e.x())), Messages.text("y", String.valueOf(e.y())),
                Messages.text("z", String.valueOf(e.z())), Messages.text("world", e.world()),
                Messages.comp("tooltip", m.get("lookup.pos-hover", Messages.text("world", e.world()))));
        return m.get("lookup.row", Messages.text("ago", Durations.format(nowSec - e.time())),
                Messages.comp("user", user), Messages.comp("action", action), Messages.comp("pos", pos));
    }

    private static Component detail(Messages m, LogEntry entry) {
        if (entry instanceof ContainerEntry c) {
            return m.get("lookup.row-verbose-container", Messages.text("id", String.valueOf(c.id())),
                    Messages.text("time", TIME.format(Instant.ofEpochSecond(c.time()))),
                    Messages.text("holder", c.holderMaterial()), Messages.text("item", c.material()),
                    Messages.text("data", c.hasData() ? "yes" : "no"));
        }
        if (entry instanceof SignEntry s) {
            return m.get("lookup.row-verbose-sign", Messages.text("id", String.valueOf(s.id())),
                    Messages.text("time", TIME.format(Instant.ofEpochSecond(s.time()))),
                    Messages.text("old", oneLine(s.oldText())), Messages.text("new", oneLine(s.newText())));
        }
        BlockEntry e = (BlockEntry) entry;
        return m.get("lookup.row-verbose", Messages.text("id", String.valueOf(e.id())),
                Messages.text("time", TIME.format(Instant.ofEpochSecond(e.time()))),
                Messages.text("old_state", state(e.oldMaterial(), e.oldData())),
                Messages.text("new_state", state(e.newMaterial(), e.newData())));
    }

    private static String oneLine(String text) {
        return text.isBlank() ? "-" : text.strip().replace("\n", " | ");
    }

    private static String state(String material, String data) {
        return data.isEmpty() ? material : material + "[" + data + "]";
    }

    private static Component footer(Messages m, ResultCache.Entry entry, int page, boolean hasNext) {
        Component prev = page > 1 ? m.get("lookup.prev", Messages.text("page", String.valueOf(page - 1))) : m.get("lookup.prev-disabled");
        Component next = hasNext ? m.get("lookup.next", Messages.text("page", String.valueOf(page + 1))) : m.get("lookup.next-disabled");
        String pages = entry.pages() + (entry.capped() ? "+" : "");
        return m.get("lookup.footer", Messages.comp("prev", prev), Messages.comp("next", next),
                Messages.text("page", String.valueOf(page)), Messages.text("pages", pages));
    }
}
