package de.sessentials.gamble;

import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Gewichtung der Glücksrad-Felder pro Spieler:
 * <ol>
 *   <li>Basis: eigene Gewichte aus dem Webpanel (gambling-odds.yml), sonst die aus {@code gambling.segments}.</li>
 *   <li>Anlauf ({@code gambling.warmup}): hohe Gewinne sind zu Beginn einer Sitzung und bei wenig Einsatz seltener
 *       und erreichen ihre volle Gewichtung erst nach {@code full-after-spins} Drehungen <em>und</em>
 *       {@code full-after-staked} Einsatz in der Sitzung.</li>
 * </ol>
 * Nur im Main-Thread benutzen.
 */
public final class GambleOdds implements Listener {

    /** Laufende Sitzung: endet nach {@code session-timeout-minutes} ohne Drehung oder beim Verlassen. */
    private static final class Session {
        int spins;
        long staked;
        long lastSpin;
    }

    private final SEssentials plugin;
    private final File file;
    private final AsyncFileWriter writer;
    /** Spieler → (Feld-Schlüssel → Gewicht). */
    private final Map<UUID, Map<String, Integer>> custom = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();

    GambleOdds(SEssentials plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("gambling-odds.yml");
        this.writer = new AsyncFileWriter("GambleOdds", plugin.getLogger());
        load();
    }

    /** Stabiler Schlüssel eines Felds, z.B. "2" oder "jackpot" – unabhängig von der Reihenfolge in der Config. */
    public static String key(LuckyWheel.Segment seg) {
        if (seg.jackpot()) return "jackpot";
        String s = String.format(Locale.ROOT, "%s", seg.multiplier());
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ------------------------------------------------------------
    // Eigene Gewichte (Webpanel)
    // ------------------------------------------------------------

    /** Basis-Gewicht je Feld für den Spieler (eigene Werte oder Config). */
    public Map<String, Integer> baseWeights(UUID player, List<LuckyWheel.Segment> segments) {
        Map<String, Integer> own = custom.get(player);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (LuckyWheel.Segment seg : segments) {
            String key = key(seg);
            result.put(key, own != null && own.containsKey(key) ? own.get(key) : seg.weight());
        }
        return result;
    }

    public boolean hasCustom(UUID player) {
        return custom.containsKey(player);
    }

    /** Eigene Gewichte setzen; nur bekannte Felder, Gewichte ≥ 0, mindestens eins > 0 (sonst {@code false}, nichts geändert). */
    public boolean setCustom(UUID player, Map<String, Integer> weights, List<LuckyWheel.Segment> segments) {
        Map<String, Integer> clean = new LinkedHashMap<>();
        for (LuckyWheel.Segment seg : segments) {
            Integer w = weights.get(key(seg));
            if (w != null) clean.put(key(seg), w);
        }
        if (clean.values().stream().anyMatch(w -> w < 0)) return false;
        Map<String, Integer> merged = new LinkedHashMap<>(baseWeights(player, segments));
        merged.putAll(clean);
        if (merged.values().stream().mapToLong(Integer::longValue).sum() <= 0) return false;
        custom.put(player, clean);
        save();
        return true;
    }

    public void resetCustom(UUID player) {
        if (custom.remove(player) != null) save();
    }

    // ------------------------------------------------------------
    // Sitzung und Anlauf
    // ------------------------------------------------------------

    /** Wirksame Gewichte für den nächsten Dreh, in Reihenfolge von {@code segments}. */
    double[] effectiveWeights(UUID player, List<LuckyWheel.Segment> segments) {
        Map<String, Integer> base = baseWeights(player, segments);
        double factor = warmupFactor(player);
        double high = plugin.getConfig().getDouble("gambling.warmup.high-multiplier");
        double[] weights = new double[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            LuckyWheel.Segment seg = segments.get(i);
            boolean isHigh = seg.jackpot() || seg.multiplier() >= high;
            weights[i] = base.get(key(seg)) * (isHigh ? factor : 1.0);
        }
        // nur hohe Felder gewichtet und min-factor 0: lieber ungedämpft als gar kein Ergebnis
        if (Arrays.stream(weights).sum() <= 0) {
            for (int i = 0; i < segments.size(); i++) weights[i] = base.get(key(segments.get(i)));
        }
        return weights;
    }

    /** 1 = volle Gewichtung; zu Beginn {@code min-factor}, steigt linear mit Drehungen und Einsatz (der langsamere zählt). */
    private double warmupFactor(UUID player) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("gambling.warmup.enabled")) return 1;
        Session s = session(player);
        double min = Math.max(0, Math.min(1, cfg.getDouble("gambling.warmup.min-factor")));
        long fullSpins = cfg.getLong("gambling.warmup.full-after-spins");
        long fullStaked = cfg.getLong("gambling.warmup.full-after-staked");
        double bySpins = fullSpins <= 0 ? 1 : Math.min(1, (double) s.spins / fullSpins);
        double byStake = fullStaked <= 0 ? 1 : Math.min(1, (double) s.staked / fullStaked);
        return min + (1 - min) * Math.min(bySpins, byStake);
    }

    /** Nach jedem Dreh aufrufen (nach der Auswahl, damit der erste Dreh einer Sitzung voll gedämpft ist). */
    void recordSpin(UUID player, long stake) {
        Session s = session(player);
        s.spins++;
        s.staked += stake;
        s.lastSpin = System.currentTimeMillis();
    }

    private Session session(UUID player) {
        long timeout = Math.max(1, plugin.getConfig().getLong("gambling.warmup.session-timeout-minutes")) * 60_000L;
        Session s = sessions.get(player);
        if (s == null || System.currentTimeMillis() - s.lastSpin > timeout) {
            s = new Session();
            s.lastSpin = System.currentTimeMillis();
            sessions.put(player, s);
        }
        return s;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("players");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) continue;
            Map<String, Integer> weights = new LinkedHashMap<>();
            for (String key : sec.getKeys(false)) weights.put(key.replace(',', '.'), Math.max(0, sec.getInt(key)));
            custom.put(UUID.fromString(id), weights);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        // "." trennt in YAML-Pfaden – Schlüssel wie "0.5" werden als "0,5" gespeichert
        custom.forEach((id, weights) -> {
            ConfigurationSection sec = yml.createSection("players." + id);
            weights.forEach((key, w) -> sec.set(key.replace('.', ','), w));
        });
        writer.write(file, yml.saveToString());
    }

    void shutdown() {
        writer.shutdown();
    }
}
