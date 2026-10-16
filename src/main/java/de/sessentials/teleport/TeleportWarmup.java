package de.sessentials.teleport;

import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wartezeit vor Spieler-Teleports (/spawn, /tpa, /nether, /end, /clan home): Der Teleport läuft erst, wenn der
 * Spieler {@code teleport-warmup.seconds} lang weder den Block gewechselt noch Schaden genommen hat.
 * Kosten ziehen die Aufrufer erst in {@code action} ab, damit ein Abbruch nichts kostet.
 */
public final class TeleportWarmup implements Listener {

    public static final String PERM_BYPASS = "sess.teleport.nowarmup";

    private record Pending(Location start, BukkitTask task) {}

    private final SEssentials plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public TeleportWarmup(SEssentials plugin) {
        this.plugin = plugin;
    }

    /**
     * Führt {@code action} nach der Wartezeit für {@code teleport} (z.B. "spawn") aus – sofort ohne Wartezeit
     * oder mit {@link #PERM_BYPASS}. Ein neuer Teleport ersetzt einen laufenden.
     */
    public void start(Player player, String teleport, Runnable action) {
        cancel(player.getUniqueId());
        int seconds = seconds(teleport);
        if (seconds <= 0 || player.hasPermission(PERM_BYPASS)) {
            action.run();
            return;
        }
        player.sendMessage(msg("started", player, seconds));
        int[] left = {seconds};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (--left[0] > 0) {
                player.sendActionBar(msg("countdown", player, left[0]));
                return;
            }
            pending.remove(player.getUniqueId()).task().cancel();
            if (player.isOnline()) action.run();
        }, 20L, 20L);
        pending.put(player.getUniqueId(), new Pending(player.getLocation().toBlockLocation(), task));
        player.sendActionBar(msg("countdown", player, seconds));
    }

    /** {@code teleport-warmup.per-teleport.<teleport>}, sonst {@code teleport-warmup.seconds}. */
    private int seconds(String teleport) {
        var cfg = plugin.getConfig();
        String override = "teleport-warmup.per-teleport." + teleport;
        return Math.max(0, cfg.isSet(override) ? cfg.getInt(override) : cfg.getInt("teleport-warmup.seconds"));
    }

    private boolean cancel(UUID id) {
        Pending p = pending.remove(id);
        if (p == null) return false;
        p.task().cancel();
        return true;
    }

    private void abort(Player player, String reason) {
        if (cancel(player.getUniqueId())) player.sendMessage(msg("cancelled-" + reason, player, 0));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending p = pending.get(event.getPlayer().getUniqueId());
        if (p == null || !event.hasChangedBlock() || !plugin.getConfig().getBoolean("teleport-warmup.cancel-on-move")) return;
        Location to = event.getTo();
        int tolerance = Math.max(0, plugin.getConfig().getInt("teleport-warmup.move-tolerance-blocks"));
        boolean moved = to.getWorld() != p.start().getWorld()
                || Math.abs(to.getBlockX() - p.start().getBlockX()) > tolerance
                || Math.abs(to.getBlockY() - p.start().getBlockY()) > tolerance
                || Math.abs(to.getBlockZ() - p.start().getBlockZ()) > tolerance;
        if (moved) abort(event.getPlayer(), "move");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && pending.containsKey(player.getUniqueId())
                && plugin.getConfig().getBoolean("teleport-warmup.cancel-on-damage")) {
            abort(player, "damage");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    private Component msg(String key, Player player, int seconds) {
        return plugin.text().render(plugin.getConfig().getString("teleport-warmup.messages." + key), player,
                TextService.placeholder("seconds", String.valueOf(seconds)));
    }
}
