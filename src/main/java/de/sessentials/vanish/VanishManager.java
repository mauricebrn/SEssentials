package de.sessentials.vanish;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import de.sessentials.SEssentials;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanish: versteckt Spieler vor allen ohne {@code sess.vanish.see}, blockiert verräterische
 * Aktionen, fälscht Spielerzahl/Serverliste und sendet Fake-Join/Leave-Nachrichten.
 */
public final class VanishManager implements Listener {

    private static final String PERM_SEE = "sess.vanish.see";
    private static final String PERM_AUTO = "sess.vanish.auto";
    private static final String PERM_USE = "sess.vanish";
    private static final String PERM_BYPASS = "sess.vanish.bypass";

    /** Was die anderen Spieler über den Spieler wissen: zuletzt angekündigter Status. */
    private static final class Announced {
        boolean online;
        long lastFakeMillis;

        Announced(boolean online) {
            this.online = online;
        }
    }

    private final SEssentials plugin;
    private final NamespacedKey key;
    // wird auch async gelesen (Chat, Serverliste, <online>)
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    // Schutz-Bypass gilt nur bis zum Verlassen des Servers
    private final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Announced> announced = new HashMap<>();

    private volatile boolean fakeMessages;
    private volatile long cooldownMillis;
    private volatile boolean protectPickup;
    private volatile boolean protectChat;
    private volatile boolean protectBreak;
    private volatile boolean protectPlace;
    private volatile boolean protectDamage;

    public VanishManager(SEssentials plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "vanished");
        reload();
    }

    /** Ohne explizite Defaults lesen: fehlende Keys in älteren Configs fallen auf die config.yml im JAR zurück. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        fakeMessages = cfg.getBoolean("vanish.fake-messages");
        cooldownMillis = Math.max(0L, cfg.getLong("vanish.fake-message-cooldown")) * 1000L;
        protectPickup = cfg.getBoolean("vanish.protections.pickup");
        protectChat = cfg.getBoolean("vanish.protections.chat");
        protectBreak = cfg.getBoolean("vanish.protections.break");
        protectPlace = cfg.getBoolean("vanish.protections.place");
        protectDamage = cfg.getBoolean("vanish.protections.damage");
    }

    /** Macht beim Deaktivieren des Plugins alle wieder sichtbar. */
    public void shutdown() {
        for (UUID id : vanished) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) applyVisibility(player, false);
        }
        vanished.clear();
    }

    public boolean isVanished(Player player) {
        return vanished.contains(player.getUniqueId());
    }

    /** Spielerzahl, wie sie Spieler ohne Vanish-Sicht sehen. */
    public int visibleCount() {
        return Math.max(0, Bukkit.getOnlinePlayers().size() - vanished.size());
    }

    /** @param announce false = ohne Fake-Join/Leave-Nachricht umschalten */
    public void toggle(Player player, boolean announce) {
        boolean vanish = !isVanished(player);
        setVanished(player, vanish);
        player.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, vanish);
        message(player, vanish ? "enabled" : "disabled");
        if (announce) announce(player, !vanish);
    }

    public void toggleBypass(Player player) {
        boolean on = bypass.add(player.getUniqueId());
        if (!on) bypass.remove(player.getUniqueId());
        message(player, on ? "bypass-on" : "bypass-off");
    }

    /** Gilt ein Schutz für den Spieler? Nur im Vanish, wenn in der Config aktiv und ohne Bypass. */
    private boolean isProtected(Player player, boolean enabled) {
        return enabled && isVanished(player) && !bypass.contains(player.getUniqueId());
    }

    private void setVanished(Player player, boolean vanish) {
        if (vanish) vanished.add(player.getUniqueId());
        else vanished.remove(player.getUniqueId());
        applyVisibility(player, vanish);
    }

    private void applyVisibility(Player player, boolean hidden) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == player) continue;
            if (hidden && !other.hasPermission(PERM_SEE)) other.hidePlayer(plugin, player);
            else other.showPlayer(plugin, player);
        }
    }

    /**
     * Sendet eine Fake-Join/Leave-Nachricht, wenn sich der angekündigte Status ändert.
     * Innerhalb des Cooldowns wird nichts gesendet; der angekündigte Status bleibt dann,
     * damit nie zwei gleiche Nachrichten hintereinander kommen.
     */
    private void announce(Player player, boolean online) {
        if (!fakeMessages) return;
        Announced state = announced.get(player.getUniqueId());
        if (state == null || state.online == online) return;
        long now = System.currentTimeMillis();
        if (now - state.lastFakeMillis < cooldownMillis) {
            message(player, "cooldown");
            return;
        }
        Component msg = online ? plugin.joinQuit().joinMessage(player) : plugin.joinQuit().quitMessage(player);
        if (msg != null) Bukkit.broadcast(msg);
        state.online = online;
        state.lastFakeMillis = now;
    }

    private void message(Player player, String key) {
        player.sendMessage(plugin.text().render(plugin.getConfig().getString("vanish.messages." + key), player));
    }

    private void notifyStaff(Player player, String key) {
        Component msg = plugin.text().render(plugin.getConfig().getString("vanish.messages." + key), player);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != player && other.hasPermission(PERM_SEE)) other.sendMessage(msg);
        }
    }

    private void block(Cancellable event, Player player) {
        event.setCancelled(true);
        player.sendActionBar(plugin.text().render(plugin.getConfig().getString("vanish.messages.blocked"), player));
    }

    // ------------------------------------------------------------
    // Join / Quit
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinEarly(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(PERM_SEE)) {
            for (UUID id : vanished) {
                Player other = Bukkit.getPlayer(id);
                if (other != null) player.hidePlayer(plugin, other);
            }
        }
        boolean vanish = player.hasPermission(PERM_AUTO)
                || (player.hasPermission(PERM_USE)
                    && player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BOOLEAN, false));
        if (vanish) setVanished(player, true);
        announced.put(player.getUniqueId(), new Announced(!vanish));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isVanished(player)) return;
        event.joinMessage(null);
        notifyStaff(player, "staff-join");
        message(player, "join-vanished");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Announced state = announced.remove(player.getUniqueId());
        bypass.remove(player.getUniqueId());
        // Im Vanish nie eine Leave-Nachricht, auch wenn still (/vanish silent) versteckt wurde
        if (isVanished(player) || (state != null && !state.online)) event.quitMessage(null);
        if (isVanished(player)) {
            notifyStaff(player, "staff-quit");
            // Versteckt-Status hängt am Betrachter – sonst bliebe der Spieler nach Rejoin unsichtbar
            setVanished(player, false);
        }
    }

    // ------------------------------------------------------------
    // Schutz
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isProtected(player, protectPickup)) event.setCancelled(true);
    }

    // LOW statt LOWEST: Chat-Eingaben für Admin-Menüs (ChatPrompt, LOWEST) haben Vorrang
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (isProtected(event.getPlayer(), protectChat)) block(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isProtected(event.getPlayer(), protectBreak)) block(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isProtected(event.getPlayer(), protectPlace)) block(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            damager = shooter;
        }
        if (damager instanceof Player player && isProtected(player, protectDamage)) block(event, player);
    }

    // ------------------------------------------------------------
    // Commands & Serverliste
    // ------------------------------------------------------------

    /**
     * Paper blendet versteckte Spieler aus Tab-Completion und /list aus, löst exakte Namen
     * (z.B. /msg Name) aber weiter auf. Daher: erstes Argument = versteckter Spieler → "nicht gefunden".
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        if (vanished.isEmpty() || sender.hasPermission(PERM_SEE)) return;
        String[] parts = event.getMessage().split(" ");
        if (parts.length < 2) return;
        Player target = Bukkit.getPlayerExact(parts[1]);
        if (target != null && target != sender && isVanished(target)) {
            event.setCancelled(true);
            sender.sendMessage(Component.translatable("argument.entity.notfound.player", NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onPing(PaperServerListPingEvent event) {
        if (vanished.isEmpty()) return;
        event.setNumPlayers(visibleCount());
        event.getListedPlayers().removeIf(info -> vanished.contains(info.id()));
    }
}
