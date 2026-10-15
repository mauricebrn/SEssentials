package de.sessentials.gamble;

import de.sessentials.storage.AsyncFileWriter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Statistik des Glücksrads in gambling-stats.yml: Summen, Treffer je Multiplikator, Spieler,
 * Tagesverlauf (30 Tage) und die letzten Drehungen. Nur im Main-Thread benutzen.
 */
public final class GambleStats {

    public record Spin(long time, String name, long stake, long fee, double multiplier, long win) {}

    public static final class PlayerStats {
        public String name;
        public long spins, staked, won;
    }

    public static final class Day {
        public long spins, staked, paidOut, fees;
        /** Topf nach der letzten Drehung des Tages. */
        public long pot;
    }

    private static final int RECENT = 50;
    private static final int DAYS = 30;

    private final File file;
    private final AsyncFileWriter writer;
    private long spins, staked, paidOut, fees;
    private Spin biggest;
    /** Multiplikator als Text (z.B. "0.5") → Anzahl Treffer. */
    private final Map<String, Long> hits = new LinkedHashMap<>();
    private final Map<UUID, PlayerStats> players = new HashMap<>();
    /** Datum (yyyy-MM-dd) → Tagessummen. */
    private final NavigableMap<String, Day> days = new TreeMap<>();
    private final Deque<Spin> recent = new ArrayDeque<>();

    GambleStats(Plugin plugin) {
        this.file = new File(plugin.getDataFolder(), "gambling-stats.yml");
        this.writer = new AsyncFileWriter("Gambling", plugin.getLogger());
        load();
    }

    void record(UUID player, Spin spin, long potAfter) {
        spins++;
        staked += spin.stake();
        paidOut += spin.win();
        fees += spin.fee();
        if (spin.win() > 0 && (biggest == null || spin.win() > biggest.win())) biggest = spin;
        hits.merge(key(spin.multiplier()), 1L, Long::sum);

        PlayerStats p = players.computeIfAbsent(player, id -> new PlayerStats());
        p.name = spin.name();
        p.spins++;
        p.staked += spin.stake();
        p.won += spin.win();

        Day day = days.computeIfAbsent(LocalDate.now().toString(), d -> new Day());
        day.spins++;
        day.staked += spin.stake();
        day.paidOut += spin.win();
        day.fees += spin.fee();
        day.pot = potAfter;
        while (days.size() > DAYS) days.pollFirstEntry();

        recent.addFirst(spin);
        while (recent.size() > RECENT) recent.removeLast();
        save();
    }

    public long spins() { return spins; }
    public long staked() { return staked; }
    public long paidOut() { return paidOut; }
    public long fees() { return fees; }
    public Spin biggest() { return biggest; }
    public Collection<PlayerStats> players() { return players.values(); }
    public NavigableMap<String, Day> days() { return days; }
    /** Neueste zuerst. */
    public Collection<Spin> recent() { return recent; }

    public long hits(double multiplier) {
        return hits.getOrDefault(key(multiplier), 0L);
    }

    /** Einheitlicher Schlüssel: 2.0 und 2 sind dasselbe Feld. */
    static String key(double multiplier) {
        return BigDecimal.valueOf(multiplier).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        spins = yml.getLong("totals.spins");
        staked = yml.getLong("totals.staked");
        paidOut = yml.getLong("totals.paid-out");
        fees = yml.getLong("totals.fees");
        if (yml.isConfigurationSection("biggest")) biggest = spin(yml.getConfigurationSection("biggest").getValues(false));
        for (Map<?, ?> m : yml.getMapList("hits")) {
            hits.put(String.valueOf(m.get("multiplier")), ((Number) m.get("count")).longValue());
        }
        ConfigurationSection ps = yml.getConfigurationSection("players");
        if (ps != null) {
            for (String id : ps.getKeys(false)) {
                PlayerStats p = new PlayerStats();
                p.name = ps.getString(id + ".name");
                p.spins = ps.getLong(id + ".spins");
                p.staked = ps.getLong(id + ".staked");
                p.won = ps.getLong(id + ".won");
                players.put(UUID.fromString(id), p);
            }
        }
        for (Map<?, ?> m : yml.getMapList("days")) {
            Day d = new Day();
            d.spins = num(m.get("spins"));
            d.staked = num(m.get("staked"));
            d.paidOut = num(m.get("paid-out"));
            d.fees = num(m.get("fees"));
            d.pot = num(m.get("pot"));
            days.put(date(m.get("date")), d);
        }
        for (Map<?, ?> m : yml.getMapList("recent")) recent.addLast(spin(m));
    }

    private static Spin spin(Map<?, ?> m) {
        return new Spin(num(m.get("time")), String.valueOf(m.get("name")), num(m.get("stake")), num(m.get("fee")),
                m.get("multiplier") instanceof Number n ? n.doubleValue() : 0, num(m.get("win")));
    }

    /** YAML liest ein unquotiertes 2026-10-15 als Datum – beides wird zu "yyyy-MM-dd". */
    private static String date(Object o) {
        if (o instanceof Date d) return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        return String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static Map<String, Object> map(Spin s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", s.time());
        m.put("name", s.name());
        m.put("stake", s.stake());
        m.put("fee", s.fee());
        m.put("multiplier", s.multiplier());
        m.put("win", s.win());
        return m;
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("totals.spins", spins);
        yml.set("totals.staked", staked);
        yml.set("totals.paid-out", paidOut);
        yml.set("totals.fees", fees);
        if (biggest != null) yml.createSection("biggest", map(biggest));
        List<Map<String, Object>> hitList = new ArrayList<>();
        hits.forEach((k, v) -> hitList.add(Map.of("multiplier", k, "count", v)));
        yml.set("hits", hitList);
        players.forEach((id, p) -> {
            yml.set("players." + id + ".name", p.name);
            yml.set("players." + id + ".spins", p.spins);
            yml.set("players." + id + ".staked", p.staked);
            yml.set("players." + id + ".won", p.won);
        });
        List<Map<String, Object>> dayList = new ArrayList<>();
        days.forEach((date, d) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", date);
            m.put("spins", d.spins);
            m.put("staked", d.staked);
            m.put("paid-out", d.paidOut);
            m.put("fees", d.fees);
            m.put("pot", d.pot);
            dayList.add(m);
        });
        yml.set("days", dayList);
        yml.set("recent", recent.stream().map(GambleStats::map).toList());
        writer.write(file, yml.saveToString());
    }

    void shutdown() {
        writer.shutdown();
    }
}
