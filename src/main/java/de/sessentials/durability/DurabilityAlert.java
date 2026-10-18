package de.sessentials.durability;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

import java.util.Comparator;
import java.util.List;

/**
 * Warnt Spieler mit {@code sess.durability}, wenn Rüstung, Werkzeug oder Waffe fast kaputt ist: einmal beim
 * Unterschreiten jeder Stufe aus {@code durability.warn-percent}. An/aus pro Spieler mit /durability
 * (gespeichert am Spieler), Standard aus {@code durability.default-enabled}.
 */
public final class DurabilityAlert implements Listener {

    private static final String PERM = "sess.durability";
    private static final Sound SOUND = Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 0.6f);

    private final SEssentials plugin;
    private final NamespacedKey key;
    /** Warnstufen in Prozent, absteigend. */
    private List<Integer> warnPercent;
    private boolean defaultEnabled;

    public DurabilityAlert(SEssentials plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "durability-alert");
        reload();
    }

    public void reload() {
        warnPercent = plugin.getConfig().getIntegerList("durability.warn-percent").stream()
                .filter(p -> p > 0 && p < 100).distinct().sorted(Comparator.reverseOrder()).toList();
        defaultEnabled = plugin.getConfig().getBoolean("durability.default-enabled", true);
    }

    private boolean enabled(Player player) {
        return player.hasPermission(PERM)
                && player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BOOLEAN, defaultEnabled);
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("durability.messages." + key, ""), player, extra);
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("durability")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM))
                        .executes(ctx -> {
                            Player player = (Player) ctx.getSource().getExecutor();
                            boolean on = !enabled(player);
                            player.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, on);
                            player.sendMessage(msg(on ? "enabled" : "disabled", player));
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Schaltet die Warnung bei fast kaputter Ausrüstung an/aus",
                List.of("haltbarkeit"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (event.getDamage() <= 0 || warnPercent.isEmpty() || !(item.getItemMeta() instanceof Damageable meta)) return;
        int max = meta.hasMaxDamage() ? meta.getMaxDamage() : item.getType().getMaxDurability();
        if (max <= 0) return;
        int before = max - meta.getDamage();
        int after = before - event.getDamage();
        if (after <= 0) return; // geht gerade kaputt – dafür gibt es die Vanilla-Anzeige

        // niedrigste Stufe, die mit diesem Schaden unterschritten wurde
        Integer crossed = null;
        for (int percent : warnPercent) {
            int threshold = (int) Math.ceil(max * percent / 100.0);
            if (before > threshold && after <= threshold) crossed = percent;
        }
        Player player = event.getPlayer();
        if (crossed == null || !enabled(player)) return;

        player.sendMessage(msg("alert", player,
                Placeholder.component("item", item.effectiveName()),
                TextService.placeholder("remaining", String.valueOf(after)),
                TextService.placeholder("max", String.valueOf(max)),
                TextService.placeholder("percent", String.valueOf(Math.max(1, after * 100 / max)))));
        player.playSound(SOUND);
    }
}
