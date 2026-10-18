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
    /** Topf des Glücksrads: alles, was Spieler an den Server zahlen. Gewinne werden daraus bezahlt. */
    private long pot;

    public BankManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("bank.yml");
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

    /** Legt das Konto mit Startguthaben an, falls es noch nicht existiert, und merkt sich den Namen für die Bestenliste ({@link #top}: /baltop, Bank-Menü, Web-Panel). */
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

    // ------------------------------------------------------------
    // Server-Topf (Glücksrad)
    // ------------------------------------------------------------

    public synchronized long pot() {
        return pot;
    }

    /** Zahlung an den Server (Befehlskosten, Admin-Shops, …): geht in den Topf. {@code false} = nicht genug Guthaben. */
    public synchronized boolean payServer(UUID id, long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount < 0");
        long current = balance(id);
        if (current < amount) return false;
        balances.put(id, current - amount);
        pot = Math.addExact(pot, amount);
        save();
        return true;
    }

    /** Ändert den Topf um {@code delta}, nie unter 0 (z.B. Rückerstattung, nachdem der Topf schon ausgezahlt hat). */
    public synchronized void addToPot(long delta) {
        pot = Math.max(0, Math.addExact(pot, delta));
        save();
    }

    public synchronized void setPot(long amount) {
        pot = Math.max(0, amount);
        save();
    }

    /**
     * Einsatz in den Topf, Gewinn aus dem Topf – in einem Schritt. {@code false} (nichts geändert), wenn der Spieler
     * den Einsatz nicht hat oder Topf plus Einsatz den höchstmöglichen Gewinn {@code maxWin} nicht decken.
     */
    public synchronized boolean wager(UUID id, long stake, long maxWin, long win) {
        if (stake < 0 || win < 0 || win > maxWin) throw new IllegalArgumentException("stake/win");
        long current = balance(id);
        if (current < stake || Math.addExact(pot, stake) < maxWin) return false;
        balances.put(id, current - stake + win);
        pot = pot + stake - win;
        save();
        return true;
    }

    /**
     * Jackpot: Einsatz in den Topf, dann bekommt der Spieler den ganzen Topf und der Topf ist leer.
     * @return Gewinn (Topf inklusive Einsatz) oder -1, wenn der Spieler den Einsatz nicht hat (nichts geändert).
     */
    public synchronized long wagerJackpot(UUID id, long stake) {
        if (stake < 0) throw new IllegalArgumentException("stake < 0");
        long current = balance(id);
        if (current < stake) return -1;
        long win = Math.addExact(pot, stake);
        balances.put(id, current - stake + win);
        pot = 0;
        save();
        return win;
    }

    public synchronized List<Account> top(int limit) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(e -> new Account(e.getKey(), names.getOrDefault(e.getKey(), "?"), e.getValue()))
                .toList();
    }

    public synchronized int accounts() {
        return balances.size();
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
        pot = Math.max(0, yml.getLong("server-pot"));
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
        yml.set("server-pot", pot);
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
