package de.sessentials.fairplay;

import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Erkennt Verstöße für {@link FairplayManager}:
 * <ul>
 *   <li>{@code combat-log}: Verlassen des Servers, solange man im Kampf ist (PvP-Treffer in den letzten {@code combat-tag-seconds})</li>
 *   <li>{@code spawn-kill}: einen Spieler kurz nach seinem Respawn töten</li>
 *   <li>{@code kill-farming}: denselben Spieler zu oft in kurzer Zeit töten</li>
 * </ul>
 */
public final class FairplayListener implements Listener {

    private final SEssentials plugin;
    private final FairplayManager fairplay;
    /** Spieler → Kampf endet (ms). */
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Map<UUID, Long> respawned = new HashMap<>();
    /** "Täter:Opfer" → Zeitpunkte der Kills. */
    private final Map<String, Deque<Long>> kills = new HashMap<>();

    public FairplayListener(SEssentials plugin, FairplayManager fairplay) {
        this.plugin = plugin;
        this.fairplay = fairplay;
    }

    // ------------------------------------------------------------
    // Combat-Log
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        tag(attacker);
        tag(victim);
    }

    private void tag(Player player) {
        long seconds = plugin.getConfig().getLong("fairplay.combat-tag-seconds");
        if (seconds <= 0) return;
        Long until = combat.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000);
        if (until == null || until < System.currentTimeMillis()) {
            player.sendMessage(fairplay.msg("combat-tagged", player, TextService.placeholder("seconds", String.valueOf(seconds))));
        }
    }

    private boolean inCombat(Player player) {
        Long until = combat.get(player.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        fairplay.ensure(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        boolean logged = inCombat(player) && switch (event.getReason()) {
            case DISCONNECTED -> true;
            case TIMED_OUT -> plugin.getConfig().getBoolean("fairplay.count-timeouts");
            // Kicks (auch Softrestart/Shutdown) und Fehler zählen nicht
            default -> false;
        };
        combat.remove(player.getUniqueId());
        respawned.remove(player.getUniqueId());
        if (!logged) return;
        if (plugin.getConfig().getBoolean("fairplay.combat-log-kill") && !player.hasPermission(FairplayManager.PERM_EXEMPT)) {
            player.setHealth(0);
        }
        fairplay.violate(player, "combat-log", TagResolver.empty());
    }

    // ------------------------------------------------------------
    // Spawn-Kill und Kill-Farming
    // ------------------------------------------------------------

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        respawned.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        combat.remove(victim.getUniqueId());
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        long now = System.currentTimeMillis();
        TagResolver tags = TextService.placeholder("victim", victim.getName());

        Long respawn = respawned.get(victim.getUniqueId());
        long spawnSeconds = plugin.getConfig().getLong("fairplay.violations.spawn-kill.seconds");
        if (respawn != null && now - respawn <= spawnSeconds * 1000) {
            fairplay.violate(killer, "spawn-kill", tags);
        }

        int count = plugin.getConfig().getInt("fairplay.violations.kill-farming.kills");
        long window = plugin.getConfig().getLong("fairplay.violations.kill-farming.minutes") * 60_000L;
        if (count <= 1 || window <= 0) return;
        Deque<Long> times = kills.computeIfAbsent(killer.getUniqueId() + ":" + victim.getUniqueId(), k -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && times.peekFirst() < now - window) times.removeFirst();
        if (times.size() >= count) {
            // neu zählen, damit jeder weitere Block von Kills wieder ein Verstoß ist
            times.clear();
            fairplay.violate(killer, "kill-farming", tags);
        }
        kills.values().removeIf(d -> d.isEmpty() || d.peekLast() < now - window);
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }
}
