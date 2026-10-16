package de.sessentials.clan;

import de.sessentials.SEssentials;
import de.sessentials.bank.BankManager;
import de.sessentials.storage.AsyncFileWriter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clans, Mitgliedschaften, Clan-Konto und Truhen-Inventare; gespeichert in clans.yml.
 * Änderungen nur im Main-Thread. {@link #of} ist auch asynchron sicher (Clan-Tag im Chat).
 */
public final class ClanManager {

    private final SEssentials plugin;
    private final BankManager bank;
    private final File file;
    private final AsyncFileWriter writer;
    /** Name in Kleinbuchstaben → Clan. */
    private final Map<String, Clan> byName = new HashMap<>();
    /** Spieler → Clan; concurrent, weil der Chat-Tag asynchron liest. */
    private final Map<UUID, Clan> byMember = new ConcurrentHashMap<>();

    public ClanManager(SEssentials plugin) {
        this.plugin = plugin;
        this.bank = plugin.bank();
        this.file = new File(plugin.getDataFolder(), "clans.yml");
        this.writer = new AsyncFileWriter("Clans", plugin.getLogger());
        load();
    }

    // ------------------------------------------------------------
    // Abfragen
    // ------------------------------------------------------------

    public Clan of(UUID player) {
        return byMember.get(player);
    }

    public Clan byName(String name) {
        return byName.get(name.toLowerCase(Locale.ROOT));
    }

    public Collection<Clan> all() {
        return byName.values();
    }

    // ------------------------------------------------------------
    // Clans und Mitglieder
    // ------------------------------------------------------------

    Clan create(String name, NamedTextColor color, UUID leader) {
        Clan clan = new Clan(name, color, leader);
        byName.put(name.toLowerCase(Locale.ROOT), clan);
        byMember.put(leader, clan);
        save();
        return clan;
    }

    /**
     * Löst den Clan auf: das Clan-Konto geht an den Leiter, der Truhenblock verschwindet.
     * Die Truhe muss vorher leer sein (prüft der Aufrufer).
     */
    void disband(Clan clan) {
        if (clan.balance > 0) {
            bank.deposit(clan.leader, clan.balance);
            clan.balance = 0;
        }
        removeChestBlock(clan);
        if (clan.chest != null) new ArrayList<>(clan.chest.getViewers()).forEach(v -> v.closeInventory());
        clan.members.forEach(byMember::remove);
        byName.remove(clan.name.toLowerCase(Locale.ROOT));
        save();
    }

    void join(Clan clan, UUID player) {
        clan.members.add(player);
        byMember.put(player, clan);
        save();
    }

    void leave(Clan clan, UUID player) {
        clan.members.remove(player);
        byMember.remove(player);
        save();
    }

    void setLeader(Clan clan, UUID player) {
        clan.leader = player;
        save();
    }

    void setColor(Clan clan, NamedTextColor color) {
        clan.color = color;
        save();
    }

    void setHome(Clan clan, Location home) {
        clan.home = home.clone();
        save();
    }

    // ------------------------------------------------------------
    // Clan-Konto
    // ------------------------------------------------------------

    /** Vom Bankkonto des Spielers aufs Clan-Konto. {@code false} = nicht genug Guthaben. */
    boolean deposit(Clan clan, UUID player, long amount) {
        if (!bank.withdraw(player, amount)) return false;
        clan.balance = Math.addExact(clan.balance, amount);
        save();
        return true;
    }

    /** Vom Clan-Konto aufs Bankkonto des Spielers. {@code false} = nicht genug auf dem Clan-Konto. */
    boolean withdraw(Clan clan, UUID player, long amount) {
        if (clan.balance < amount) return false;
        clan.balance -= amount;
        bank.deposit(player, amount);
        save();
        return true;
    }

    /** Zahlung vom Clan-Konto an den Server (z.B. Truhenkauf). */
    boolean payServer(Clan clan, long amount) {
        if (clan.balance < amount) return false;
        clan.balance -= amount;
        bank.addToPot(amount);
        save();
        return true;
    }

    /** Admin: Clan-Konto direkt setzen (nie unter 0). */
    void setBalance(Clan clan, long amount) {
        clan.balance = Math.max(0, amount);
        save();
    }

    /** {@code false}, wenn der Clan inzwischen aufgelöst wurde (z.B. während ein Menü offen war). */
    boolean exists(Clan clan) {
        return byName.get(clan.name.toLowerCase(Locale.ROOT)) == clan;
    }

    // ------------------------------------------------------------
    // Truhe
    // ------------------------------------------------------------

    void setChestBought(Clan clan) {
        clan.chestBought = true;
        save();
    }

    void setChestBlock(Clan clan, Location block) {
        clan.chestBlock = block == null ? null : block.toBlockLocation();
        save();
    }

    /** Clan, dessen Truhe an {@code block} steht, sonst {@code null}. */
    Clan chestAt(Location block) {
        Location at = block.toBlockLocation();
        for (Clan clan : byName.values()) {
            if (at.equals(clan.chestBlock)) return clan;
        }
        return null;
    }

    private void removeChestBlock(Clan clan) {
        Location at = clan.chestBlock;
        if (at != null && at.isWorldLoaded() && at.getBlock().getType() == Material.ENDER_CHEST) at.getBlock().setType(Material.AIR);
        clan.chestBlock = null;
    }

    /** Gemeinsames Truhen-Inventar, beim ersten Zugriff angelegt. */
    Inventory chest(Clan clan) {
        if (clan.chest == null) clan.chest = newChest(clan, List.of());
        return clan.chest;
    }

    /** Mindestens {@code chest.rows} Reihen, aber nie kleiner als nötig – verkleinern in der Config löscht keine Items. */
    private Inventory newChest(Clan clan, List<ItemStack> contents) {
        int rows = Math.max(1, Math.min(6, plugin.getConfig().getInt("clans.chest.rows")));
        int needed = (contents.size() + 8) / 9;
        int size = Math.min(6, Math.max(rows, needed)) * 9;
        Component title = plugin.text().render(plugin.getConfig().getString("clans.chest.title"),
                Placeholder.component("clan", Component.text(clan.name, clan.color)));
        Inventory inv = Bukkit.createInventory(new Clan.ChestHolder(clan), size, title);
        for (int i = 0; i < Math.min(size, contents.size()); i++) inv.setItem(i, contents.get(i));
        return inv;
    }

    boolean chestEmpty(Clan clan) {
        if (clan.chest == null) return true;
        for (ItemStack item : clan.chest.getContents()) {
            if (item != null && !item.isEmpty()) return false;
        }
        return true;
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("clans");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;
            NamedTextColor color = NamedTextColor.NAMES.valueOr(sec.getString("color", "white"), NamedTextColor.WHITE);
            Clan clan = new Clan(sec.getString("name", key), color, UUID.fromString(sec.getString("leader")));
            sec.getStringList("members").forEach(m -> clan.members.add(UUID.fromString(m)));
            clan.home = location(sec, "home");
            clan.balance = Math.max(0, sec.getLong("balance"));
            clan.chestBought = sec.getBoolean("chest.bought");
            clan.chestBlock = location(sec, "chest.block");
            ConfigurationSection items = sec.getConfigurationSection("chest.items");
            if (items != null) {
                List<ItemStack> contents = new ArrayList<>();
                for (String slot : items.getKeys(false)) {
                    int i = Integer.parseInt(slot);
                    while (contents.size() <= i) contents.add(null);
                    contents.set(i, ItemStack.deserializeBytes(Base64.getDecoder().decode(items.getString(slot))));
                }
                clan.chest = newChest(clan, contents);
            }
            byName.put(clan.name.toLowerCase(Locale.ROOT), clan);
            clan.members.forEach(m -> byMember.put(m, clan));
        }
    }

    /** Schnappschuss im Main-Thread (auch die Truhen), Schreiben asynchron. */
    void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Clan clan : byName.values()) {
            String p = "clans." + clan.name.toLowerCase(Locale.ROOT) + ".";
            yml.set(p + "name", clan.name);
            yml.set(p + "color", clan.color.toString());
            yml.set(p + "leader", clan.leader.toString());
            yml.set(p + "members", clan.members.stream().map(UUID::toString).toList());
            setLocation(yml, p + "home", clan.home);
            yml.set(p + "balance", clan.balance);
            yml.set(p + "chest.bought", clan.chestBought);
            setLocation(yml, p + "chest.block", clan.chestBlock);
            if (clan.chest != null) {
                ItemStack[] contents = clan.chest.getContents();
                for (int i = 0; i < contents.length; i++) {
                    if (contents[i] != null && !contents[i].isEmpty()) {
                        yml.set(p + "chest.items." + i, Base64.getEncoder().encodeToString(contents[i].serializeAsBytes()));
                    }
                }
            }
        }
        writer.write(file, yml.saveToString());
    }

    private Location location(ConfigurationSection sec, String path) {
        if (!sec.isConfigurationSection(path)) return null;
        String worldName = sec.getString(path + ".world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Clan " + sec.getName() + ": Welt '" + worldName + "' für " + path + " nicht geladen – Position verworfen.");
            return null;
        }
        return new Location(world, sec.getDouble(path + ".x"), sec.getDouble(path + ".y"), sec.getDouble(path + ".z"),
                (float) sec.getDouble(path + ".yaw"), (float) sec.getDouble(path + ".pitch"));
    }

    private static void setLocation(YamlConfiguration yml, String path, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        yml.set(path + ".world", loc.getWorld().getName());
        yml.set(path + ".x", loc.getX());
        yml.set(path + ".y", loc.getY());
        yml.set(path + ".z", loc.getZ());
        yml.set(path + ".yaw", loc.getYaw());
        yml.set(path + ".pitch", loc.getPitch());
    }

    public void shutdown() {
        save();
        writer.shutdown();
    }
}
