package io.github.phateio.worldrewild;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact duration strings for config values — units {@code d/h/m/s}, single or
 * concatenated, e.g. {@code 90d}, {@code 6h}, {@code 10m}, {@code 1d12h}.
 */
final class Durations {

    private Durations() {
    }

    private static final Pattern UNIT = Pattern.compile("(\\d+)([dhms])");

    /** Parse a duration to seconds; -1 if null, blank, or malformed. */
    static long seconds(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim().toLowerCase();
        Matcher m = UNIT.matcher(t);
        long total = 0;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() != end) {
                return -1; // gap between tokens
            }
            long v;
            try {
                v = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
            total += switch (m.group(2)) {
                case "d" -> v * 86400L;
                case "h" -> v * 3600L;
                case "m" -> v * 60L;
                default -> v; // "s"
            };
            end = m.end();
            any = true;
        }
        if (!any || end != t.length()) {
            return -1; // nothing matched, or trailing garbage
        }
        return total;
    }

    /** Parse {@code v.toString()} to seconds, falling back to {@code def} if absent/invalid. */
    static long secondsOr(Object v, long def) {
        long s = seconds(v == null ? null : v.toString());
        return s > 0 ? s : def;
    }

    /** Format seconds compactly using the largest whole unit (e.g. 21600 -> "6h"). */
    static String human(long secs) {
        if (secs >= 86400) {
            return (secs / 86400) + "d";
        }
        if (secs >= 3600) {
            return (secs / 3600) + "h";
        }
        if (secs >= 60) {
            return (secs / 60) + "m";
        }
        return secs + "s";
    }
}
