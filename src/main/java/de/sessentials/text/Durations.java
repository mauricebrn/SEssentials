package de.sessentials.text;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Zeitangaben deutsch oder englisch lesen ("2h", "7 Tage", "1d 12h") und lesbar ausgeben ("3 Tage 4 Stunden"). */
public final class Durations {

    private static final Pattern PART = Pattern.compile("(\\d+)\\s*([a-zäöü]+)");

    private Durations() {}

    /** {@code null} bei ungültiger Eingabe oder 0. */
    public static Duration parse(String input) {
        String text = input.toLowerCase(Locale.ROOT).trim();
        Matcher m = PART.matcher(text);
        Duration total = Duration.ZERO;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (!text.substring(end, m.start()).replaceAll("[\\s,]|und|and", "").isEmpty()) return null;
            Duration unit = switch (m.group(2)) {
                case "s", "sek", "sekunde", "sekunden", "sec", "second", "seconds" -> Duration.ofSeconds(1);
                case "m", "min", "minute", "minuten", "minutes" -> Duration.ofMinutes(1);
                case "h", "std", "stunde", "stunden", "hour", "hours" -> Duration.ofHours(1);
                case "d", "t", "tag", "tage", "tagen", "day", "days" -> Duration.ofDays(1);
                case "w", "woche", "wochen", "week", "weeks" -> Duration.ofDays(7);
                case "mo", "monat", "monate", "month", "months" -> Duration.ofDays(30);
                case "y", "j", "jahr", "jahre", "year", "years" -> Duration.ofDays(365);
                default -> null;
            };
            if (unit == null) return null;
            total = total.plus(unit.multipliedBy(Long.parseLong(m.group(1))));
            end = m.end();
            any = true;
        }
        if (!any || !text.substring(end).isBlank() || total.isZero()) return null;
        return total;
    }

    /** Die zwei größten Einheiten, z.B. "3 Tage 4 Stunden"; unter einer Minute "weniger als 1 Minute". */
    public static String format(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        long[] sizes = {86400, 3600, 60};
        String[][] names = {{"Tag", "Tage"}, {"Stunde", "Stunden"}, {"Minute", "Minuten"}};
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < sizes.length && parts.size() < 2; i++) {
            long n = seconds / sizes[i];
            if (n > 0) {
                parts.add(n + " " + names[i][n == 1 ? 0 : 1]);
                seconds -= n * sizes[i];
            } else if (!parts.isEmpty()) {
                break;
            }
        }
        return parts.isEmpty() ? "weniger als 1 Minute" : String.join(" ", parts);
    }
}
