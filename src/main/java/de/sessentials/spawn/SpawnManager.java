package de.sessentials.spawn;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/**
 * Server-Spawn: /setspawn speichert ihn, /spawn teleportiert hin – mit {@code sess.spawn} kostenlos,
 * sonst gegen Items aus der Config. Neue Spieler und Respawns ohne Bett/Anker landen exakt am Spawn.
 */
public final class SpawnManager implements Listener {

    private static final String PERM_FREE = "sess.spawn";
    private static final String PERM_SET = "sess.setspawn";

    private final SEssentials plugin;
    private final File file;
    private Location spawn;

    private volatile Material costItem;
    private volatile int costAmount;

    public SpawnManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "spawn.yml");
        this.spawn = YamlConfiguration.loadConfiguration(file).getLocation("spawn");
        reload();
    }

    /** Ohne explizite Defaults lesen: fehlende Keys in älteren Configs fallen auf die config.yml im JAR zurück. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        String itemName = cfg.getString("spawn.cost.item");
        Material item = itemName == null ? null : Material.matchMaterial(itemName);
        if (item == null || !item.isItem()) {
            plugin.getLogger().warning("spawn.cost.item '" + itemName + "' ist kein Item – nutze DIAMOND.");
            item = Material.DIAMOND;
        }
        costItem = item;
        costAmount = Math.max(0, cfg.getInt("spawn.cost.amount"));
    }

    /** Gesetzter Spawn, sonst der Spawn der Hauptwelt. */
    public Location spawn() {
        if (spawn != null && spawn.isWorldLoaded()) return spawn.clone();
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    public void setSpawn(Player player) {
        Location loc = player.getLocation();
        spawn = loc.clone();
        loc.getWorld().setSpawnLocation(loc);
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("spawn", spawn);
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "spawn.yml konnte nicht gespeichert werden", e);
        }
        player.sendMessage(msg("set", player));
    }

    /** /spawn: mit sess.spawn kostenlos, sonst gegen die konfigurierten Items. */
    public void teleport(Player player) {
        ItemStack cost = player.hasPermission(PERM_FREE) || costAmount == 0 ? null : new ItemStack(costItem, costAmount);
        TagResolver costTags = TagResolver.resolver(
                Placeholder.unparsed("amount", String.valueOf(costAmount)),
                Placeholder.component("item", Component.translatable(costItem.translationKey())));

        if (cost != null) {
            if (!player.getInventory().containsAtLeast(cost, costAmount)) {
                player.sendMessage(msg("not-enough", player, costTags));
                return;
            }
            // vorher abziehen, damit niemand die Items während des Teleports weglegen kann
            player.getInventory().removeItem(cost.clone());
        }
        player.teleportAsync(spawn()).thenAccept(ok -> {
            if (!ok) {
                if (cost != null) player.getInventory().addItem(cost).values().forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
                player.sendMessage(msg("failed", player));
                return;
            }
            player.sendMessage(cost == null ? msg("teleported", player) : msg("teleported-paid", player, costTags));
        });
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("spawn")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(ctx -> {
                            teleport((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Teleportiert dich zum Spawn",
                List.of());
        commands.register(
                Commands.literal("setspawn")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_SET))
                        .executes(ctx -> {
                            setSpawn((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Setzt den Server-Spawn auf deine Position",
                List.of());
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("spawn.messages." + key), player, extra);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        // Bett/Anker haben Vorrang; sonst exakt zum Spawn statt Vanilla-Streuradius
        if (spawn != null && !event.isBedSpawn() && !event.isAnchorSpawn() && spawn.isWorldLoaded()) {
            event.setRespawnLocation(spawn.clone());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFirstJoin(PlayerJoinEvent event) {
        if (spawn != null && !event.getPlayer().hasPlayedBefore() && spawn.isWorldLoaded()) {
            event.getPlayer().teleportAsync(spawn.clone());
        }
    }
}
