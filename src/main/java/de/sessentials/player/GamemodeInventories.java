package de.sessentials.player;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;

/**
 * Getrennte Inventare für Kreativ und Survival (Survival, Abenteuer, Zuschauer) – für Admins mit
 * {@code sess.gamemodeinv}, an/aus mit /gminv. Beim Wechsel zwischen den Gruppen wird das Inventar
 * (samt Rüstung und Zweithand) gesichert und das der anderen Gruppe geladen; die Enderchest bleibt gemeinsam.
 * Gespeichert am Spieler (in seinen Spielerdaten), also zusammen mit dem aktuellen Inventar.
 */
public final class GamemodeInventories implements Listener {

    private static final String PERM = "sess.gamemodeinv";

    private final SEssentials plugin;
    private final NamespacedKey enabledKey;
    private final NamespacedKey survivalKey;
    private final NamespacedKey creativeKey;

    public GamemodeInventories(SEssentials plugin) {
        this.plugin = plugin;
        this.enabledKey = new NamespacedKey(plugin, "gminv-enabled");
        this.survivalKey = new NamespacedKey(plugin, "gminv-survival");
        this.creativeKey = new NamespacedKey(plugin, "gminv-creative");
    }

    private Component msg(String key, Player player) {
        return plugin.text().render(plugin.getConfig().getString("gamemode-inventory.messages." + key, ""), player);
    }

    private static boolean creative(GameMode mode) {
        return mode == GameMode.CREATIVE;
    }

    private NamespacedKey keyFor(GameMode mode) {
        return creative(mode) ? creativeKey : survivalKey;
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("gminv")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM))
                        .executes(ctx -> {
                            toggle((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Getrennte Inventare für Kreativ und Survival an/aus",
                List.of("gamemodeinv"));
    }

    private void toggle(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (!pdc.getOrDefault(enabledKey, PersistentDataType.BOOLEAN, false)) {
            pdc.set(enabledKey, PersistentDataType.BOOLEAN, true);
            player.sendMessage(msg("enabled", player));
            return;
        }
        // Im Kreativmodus liegt das Survival-Inventar gesichert – ausschalten würde es verwaisen lassen
        if (creative(player.getGameMode()) && pdc.has(survivalKey)) {
            player.sendMessage(msg("disable-in-creative", player));
            return;
        }
        pdc.remove(enabledKey);
        pdc.remove(survivalKey);
        pdc.remove(creativeKey);
        player.sendMessage(msg("disabled", player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode from = player.getGameMode();
        GameMode to = event.getNewGameMode();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        // Das Flag zählt, nicht das Recht: wer es verliert, behält trotzdem sein gesichertes Inventar
        if (creative(from) == creative(to) || !pdc.getOrDefault(enabledKey, PersistentDataType.BOOLEAN, false)) return;

        player.closeInventory(); // Cursor- und Crafting-Items zurück ins Inventar
        PlayerInventory inv = player.getInventory();
        ItemStack[] current = Arrays.stream(inv.getContents())
                .map(item -> item == null ? ItemStack.empty() : item)
                .toArray(ItemStack[]::new);
        pdc.set(keyFor(from), PersistentDataType.BYTE_ARRAY, ItemStack.serializeItemsAsBytes(current));

        byte[] stored = pdc.get(keyFor(to), PersistentDataType.BYTE_ARRAY);
        ItemStack[] next = new ItemStack[inv.getSize()];
        if (stored != null) {
            ItemStack[] loaded = ItemStack.deserializeItemsFromBytes(stored);
            System.arraycopy(loaded, 0, next, 0, Math.min(loaded.length, next.length));
        }
        inv.setContents(next);
        pdc.remove(keyFor(to));
        player.sendMessage(msg(creative(to) ? "switched-creative" : "switched-survival", player));
    }
}
