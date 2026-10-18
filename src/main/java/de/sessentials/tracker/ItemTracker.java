package de.sessentials.tracker;

import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Erfasst, wie viele wertvolle Items Spieler im Inventar und in der Enderchest haben (inkl. Shulkerkisten darin),
 * und führt einen Verlauf pro Spieler und für den ganzen Server.
 * Erfasst wird beim Join, beim Verlassen und regelmäßig für alle Online-Spieler; gespeichert nur bei Änderungen.
 */
public final class ItemTracker implements Listener {

    /** Bestände zu einem Zeitpunkt. Leere Einträge werden weggelassen. */
    public record Snapshot(long time, Map<Material, Integer> inventory, Map<Material, Integer> enderChest) {

        public int total(Material material) {
            return inventory.getOrDefault(material, 0) + enderChest.getOrDefault(material, 0);
        }

        boolean sameCounts(Snapshot other) {
            return other != null && inventory.equals(other.inventory) && enderChest.equals(other.enderChest);
        }
    }

    /** Verlauf eines Spielers, älteste zuerst. */
    public static final class History {
        final UUID id;
        String name;
        final List<Snapshot> snapshots = new ArrayList<>();

        History(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID id() {
            return id;
        }

        public String name() {
            return name;
        }

        public List<Snapshot> snapshots() {
            return snapshots;
        }

        public Snapshot latest() {
            return snapshots.isEmpty() ? null : snapshots.getLast();
        }
    }

    private static final Duration HOURLY_AFTER = Duration.ofHours(24);
    private static final Duration DAILY_AFTER = Duration.ofDays(7);

    private final SEssentials plugin;
    private final File playerDir;
    private final File serverFile;
    private final Map<UUID, History> players = new HashMap<>();
    /** Server-Gesamtbestand (Summe der letzten Erfassung aller Spieler), im Feld {@code inventory}. */
    private final List<Snapshot> server = new ArrayList<>();
    private final AsyncFileWriter writer;

    private volatile boolean enabled;
    private volatile Set<Material> items = Set.of();
    private volatile boolean includeShulkers;
    private volatile Duration retention;
    private BukkitTask task;
    private long lastCapture;

    public ItemTracker(SEssentials plugin) {
        this.plugin = plugin;
        File dir = plugin.dataFile("tracker");
        this.playerDir = new File(dir, "players");
        this.serverFile = new File(dir, "server.yml");
        this.writer = new AsyncFileWriter("Tracker", plugin.getLogger());
        load();
        reload();
    }

    /** Ohne explizite Defaults lesen: fehlende Keys in älteren Configs fallen auf die config.yml im JAR zurück. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("tracker.enabled");
        includeShulkers = cfg.getBoolean("tracker.include-shulkers");
        retention = Duration.ofDays(Math.max(1, cfg.getLong("tracker.history-days")));
        Set<Material> parsed = new LinkedHashSet<>();
        for (String name : cfg.getStringList("tracker.items")) {
            Material m = Material.matchMaterial(name);
            if (m == null || !m.isItem()) plugin.getLogger().warning("tracker.items: '" + name + "' ist kein Item – übersprungen.");
            else parsed.add(m);
        }
        items = Set.copyOf(parsed);

        if (task != null) task.cancel();
        task = null;
        if (enabled) {
            long ticks = Math.max(1L, cfg.getLong("tracker.interval-minutes")) * 60L * 20L;
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::captureAll, ticks, ticks);
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
        if (enabled) captureAll();
        writer.shutdown();
    }

    // ------------------------------------------------------------
    // Abfragen (für das Menü)
    // ------------------------------------------------------------

    /** Getrackte Items in Config-Reihenfolge. */
    public List<Material> items() {
        List<Material> ordered = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList("tracker.items")) {
            Material m = Material.matchMaterial(name);
            if (m != null && items.contains(m) && !ordered.contains(m)) ordered.add(m);
        }
        return ordered;
    }

    public Collection<History> players() {
        return players.values();
    }

    public History player(UUID id) {
        return players.get(id);
    }

    public List<Snapshot> serverHistory() {
        return server;
    }

    public long lastCapture() {
        return lastCapture;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Letzte Erfassung, die mindestens {@code age} alt ist – für Trends wie „seit 24 h“. */
    public static Snapshot atOrBefore(List<Snapshot> history, long time) {
        Snapshot found = null;
        for (Snapshot s : history) {
            if (s.time() > time) break;
            found = s;
        }
        return found;
    }

    /** Gesamtwert einer Erfassung nach den Bank-Kursen. */
    public long value(Snapshot snapshot) {
        if (snapshot == null) return 0;
        long total = 0;
        for (Material m : items) total += plugin.bankExchange().worth(m) * snapshot.total(m);
        return total;
    }

    // ------------------------------------------------------------
    // Erfassen
    // ------------------------------------------------------------

    /** Erfasst alle Online-Spieler und aktualisiert den Server-Gesamtbestand. */
    public void captureAll() {
        if (!enabled) return;
        for (Player player : Bukkit.getOnlinePlayers()) capture(player);
        updateServerTotals();
        lastCapture = System.currentTimeMillis();
    }

    private void capture(Player player) {
        Snapshot snapshot = new Snapshot(System.currentTimeMillis(),
                count(player.getInventory().getContents()), count(player.getEnderChest().getContents()));
        History history = players.computeIfAbsent(player.getUniqueId(), id -> new History(id, player.getName()));
        boolean renamed = !player.getName().equals(history.name);
        history.name = player.getName();
        if (snapshot.sameCounts(history.latest()) && !renamed) return;
        if (!snapshot.sameCounts(history.latest())) history.snapshots.add(snapshot);
        compact(history.snapshots);
        savePlayer(history);
    }

    private Map<Material, Integer> count(ItemStack[] contents) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        addCounts(counts, contents, includeShulkers);
        return Map.copyOf(counts);
    }

    private void addCounts(Map<Material, Integer> counts, ItemStack[] contents, boolean openShulkers) {
        for (ItemStack item : contents) {
            if (item == null || item.isEmpty()) continue;
            if (items.contains(item.getType())) counts.merge(item.getType(), item.getAmount(), Integer::sum);
            // eine Ebene tief: Shulkerkisten im Inventar/in der Enderchest (Shulker können keine Shulker enthalten)
            if (openShulkers && Tag.SHULKER_BOXES.isTagged(item.getType())
                    && item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox box) {
                addCounts(counts, box.getInventory().getContents(), false);
            }
        }
    }

    private void updateServerTotals() {
        Map<Material, Integer> totals = new EnumMap<>(Material.class);
        for (History history : players.values()) {
            Snapshot latest = history.latest();
            if (latest == null) continue;
            for (Material m : items) {
                int n = latest.total(m);
                if (n > 0) totals.merge(m, n, Integer::sum);
            }
        }
        Snapshot snapshot = new Snapshot(System.currentTimeMillis(), Map.copyOf(totals), Map.of());
        if (!server.isEmpty() && snapshot.sameCounts(server.getLast())) return;
        server.add(snapshot);
        compact(server);
        saveServer();
    }

    /**
     * Verdichtet den Verlauf: letzte 24 h vollständig, bis 7 Tage die letzte Erfassung pro Stunde,
     * danach die letzte pro Tag; älter als {@code history-days} wird gelöscht.
     */
    private void compact(List<Snapshot> history) {
        long now = System.currentTimeMillis();
        history.removeIf(s -> s.time() < now - retention.toMillis());
        List<Snapshot> kept = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            String key = bucket(history.get(i).time(), now);
            String nextKey = i + 1 < history.size() ? bucket(history.get(i + 1).time(), now) : null;
            if (!key.equals(nextKey)) kept.add(history.get(i));
        }
        history.clear();
        history.addAll(kept);
    }

    /** Erfassungen mit gleichem Schlüssel werden auf die letzte reduziert. */
    private static String bucket(long time, long now) {
        long age = now - time;
        if (age < HOURLY_AFTER.toMillis()) return "t" + time;
        if (age < DAILY_AFTER.toMillis()) return "h" + time / Duration.ofHours(1).toMillis();
        return "d" + time / Duration.ofDays(1).toMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        capture(event.getPlayer());
        updateServerTotals();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!enabled) return;
        capture(event.getPlayer());
        updateServerTotals();
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        File[] files = playerDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File f : files) {
                try {
                    UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                    YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
                    History history = new History(id, yml.getString("name", "?"));
                    history.snapshots.addAll(readSnapshots(yml));
                    players.put(id, history);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Tracker: " + f.getName() + " übersprungen (kein gültiger Dateiname).");
                }
            }
        }
        server.addAll(readSnapshots(YamlConfiguration.loadConfiguration(serverFile)));
    }

    private static List<Snapshot> readSnapshots(YamlConfiguration yml) {
        List<Snapshot> list = new ArrayList<>();
        ConfigurationSection sec = yml.getConfigurationSection("snapshots");
        if (sec == null) return list;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            list.add(new Snapshot(Long.parseLong(key), readCounts(s.getConfigurationSection("inventory")),
                    readCounts(s.getConfigurationSection("enderchest"))));
        }
        list.sort((a, b) -> Long.compare(a.time(), b.time()));
        return list;
    }

    private static Map<Material, Integer> readCounts(ConfigurationSection sec) {
        if (sec == null) return Map.of();
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        for (String key : sec.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m != null) counts.put(m, sec.getInt(key));
        }
        return Map.copyOf(counts);
    }

    private static String serialize(String name, List<Snapshot> snapshots) {
        YamlConfiguration yml = new YamlConfiguration();
        if (name != null) yml.set("name", name);
        for (Snapshot s : snapshots) {
            s.inventory().forEach((m, n) -> yml.set("snapshots." + s.time() + ".inventory." + m.name(), n));
            s.enderChest().forEach((m, n) -> yml.set("snapshots." + s.time() + ".enderchest." + m.name(), n));
            if (s.inventory().isEmpty() && s.enderChest().isEmpty()) yml.createSection("snapshots." + s.time());
        }
        return yml.saveToString();
    }

    private void savePlayer(History history) {
        writer.write(new File(playerDir, history.id + ".yml"), serialize(history.name, history.snapshots));
    }

    private void saveServer() {
        writer.write(serverFile, serialize(null, server));
    }
}
