package me.skerik.justlogs.util;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parsing and formatting of durations like {@code 30s}, {@code 1h30m}, {@code 7d}, {@code 2w}. */
public final class Durations {

    private static final Pattern WHOLE = Pattern.compile("(?:\\d{1,9}[smhdw])+");
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdw])");
    private static final long MAX_SECONDS = 10L * 365 * 24 * 3600;

    private Durations() {
    }

    /**
     * @param text units s, m, h, d, w (combinable: {@code 1h30m}); a bare number means seconds
     * @return seconds, empty for blank, zero or malformed input
     */
    public static OptionalLong parseSeconds(String text) {
        String s = text.strip().replace(" ", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return OptionalLong.empty();
        long total = 0;
        if (s.matches("\\d{1,9}")) {
            total = Long.parseLong(s);
        } else if (WHOLE.matcher(s).matches()) {
            Matcher m = PART.matcher(s);
            while (m.find()) {
                total += Long.parseLong(m.group(1)) * unit(m.group(2).charAt(0));
                if (total > MAX_SECONDS) return OptionalLong.empty();
            }
        } else {
            return OptionalLong.empty();
        }
        return total <= 0 || total > MAX_SECONDS ? OptionalLong.empty() : OptionalLong.of(total);
    }

    private static long unit(char c) {
        return switch (c) {
            case 's' -> 1L;
            case 'm' -> 60L;
            case 'h' -> 3_600L;
            case 'd' -> 86_400L;
            default -> 7 * 86_400L;
        };
    }

    /** Short text with at most two parts, e.g. {@code 1d 2h}, {@code 5m 30s}; "0s" for zero or less. */
    public static String format(long seconds) {
        long total = Math.max(0, seconds);
        if (total == 0) return "0s";
        long[] values = {total / 86_400, total % 86_400 / 3_600, total % 3_600 / 60, total % 60};
        String[] names = {"d", "h", "m", "s"};
        StringBuilder out = new StringBuilder();
        int parts = 0;
        for (int i = 0; i < values.length && parts < 2; i++) {
            if (values[i] == 0 && parts == 0) continue;
            if (values[i] == 0) break; // 1d 0h 5m is shown as 1d
            if (parts > 0) out.append(' ');
            out.append(values[i]).append(names[i]);
            parts++;
        }
        return out.toString();
    }
}
