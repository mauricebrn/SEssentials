package de.sessentials.bank;

import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Digitale Konten in ganzen Einheiten der Server-Währung. Thread-sicher, weil Vault-Aufrufe anderer
 * Plugins auch asynchron kommen können. Jede Änderung wird sofort (asynchron) in bank.yml gesichert.
 */
public final class BankManager implements Listener {

    public record Account(UUID id, String name, long balance) {}

    private final SEssentials plugin;
    private final File file;
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final AsyncFileWriter writer;

    public BankManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bank.yml");
        this.writer = new AsyncFileWriter("Bank", plugin.getLogger());
        load();
    }

    // ------------------------------------------------------------
    // Konten
    // ------------------------------------------------------------

    public synchronized long balance(UUID id) {
        return balances.getOrDefault(id, startBalance());
    }

    public synchronized boolean hasAccount(UUID id) {
        return balances.containsKey(id);
    }

    /** Legt das Konto mit Startguthaben an, falls es noch nicht existiert, und merkt sich den Namen für /baltop. */
    public synchronized void ensureAccount(OfflinePlayer player) {
        String name = player.getName();
        boolean changed = name != null && !name.equals(names.put(player.getUniqueId(), name));
        if (balances.putIfAbsent(player.getUniqueId(), startBalance()) == null) changed = true;
        if (changed) save();
    }

    public synchronized void deposit(UUID id, long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        balances.put(id, Math.addExact(balance(id), amount));
        save();
    }

    /** Zieht ab, wenn genug da ist. {@code false} = nicht genug Guthaben, nichts geändert. */
    public synchronized boolean withdraw(UUID id, long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        long current = balance(id);
        if (current < amount) return false;
        balances.put(id, current - amount);
        save();
        return true;
    }

    public synchronized void set(UUID id, long amount) {
        balances.put(id, Math.max(0, amount));
        save();
    }

    /** Überweisung in einem Schritt – entweder beide Buchungen oder keine. */
    public synchronized boolean transfer(UUID from, UUID to, long amount) {
        if (!withdraw(from, amount)) return false;
        deposit(to, amount);
        return true;
    }

    public synchronized List<Account> top(int limit) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> new Account(e.getKey(), names.getOrDefault(e.getKey(), "?"), e.getValue()))
                .toList();
    }

    public synchronized long total() {
        return balances.values().stream().mapToLong(Long::longValue).sum();
    }

    private long startBalance() {
        return Math.max(0, plugin.getConfig().getLong("bank.start-balance"));
    }

    // ------------------------------------------------------------
    // Anzeige
    // ------------------------------------------------------------

    public String currencyName(long amount) {
        return plugin.getConfig().getString(amount == 1 ? "bank.currency.singular" : "bank.currency.plural");
    }

    /** z.B. "1.250 Taler" */
    public String format(long amount) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(amount) + " " + currencyName(amount);
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("accounts");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            UUID id = UUID.fromString(key);
            balances.put(id, sec.getLong(key + ".balance"));
            String name = sec.getString(key + ".name");
            if (name != null) names.put(id, name);
        }
    }

    /** Schnappschuss unter Sperre, Schreiben asynchron. */
    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : balances.entrySet()) {
            yml.set("accounts." + e.getKey() + ".name", names.get(e.getKey()));
            yml.set("accounts." + e.getKey() + ".balance", e.getValue());
        }
        writer.write(file, yml.saveToString());
    }

    /** Wartet auf ausstehende Speichervorgänge (beim Deaktivieren). */
    public void shutdown() {
        writer.shutdown();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ensureAccount(event.getPlayer());
    }
}
