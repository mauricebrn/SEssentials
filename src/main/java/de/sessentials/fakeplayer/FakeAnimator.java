package de.sessentials.fakeplayer;

import de.sessentials.SEssentials;
import de.sessentials.punish.PunishmentManager;
import de.sessentials.text.TextService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Animieren: Ein Admin steuert einen Fake-Spieler. Der Admin wird unsichtbar und „verlässt“ den Server
 * (Leave-Nachricht), der Fake-Spieler übernimmt Position, Blick, Schleichen, Sprinten und Armschwung;
 * Chat-Nachrichten des Admins schreibt der Fake-Spieler. Beim Beenden „kommt“ der Admin zurück
 * (Join-Nachricht); war der Fake-Spieler nur fürs Animieren online, verlässt er den Server wieder.
 */
final class FakeAnimator implements Listener {

    private record Animation(Player admin, FakePlayer bot, boolean joinedForIt, boolean vanishedByUs,
                             boolean hiddenByUs, boolean announced) {}

    private final SEssentials plugin;
    private final FakePlayerManager manager;
    /** Admin → laufende Animation; wird auch im asynchronen Chat-Event gelesen. */
    private final Map<UUID, Animation> byAdmin = new ConcurrentHashMap<>();

    FakeAnimator(SEssentials plugin, FakePlayerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    boolean isAnimated(FakePlayer b) {
        return byAdmin.values().stream().anyMatch(a -> a.bot() == b);
    }

    /** Startet die Animation – oder beendet sie, wenn der Admin diesen Fake-Spieler schon steuert. */
    void toggle(Player admin, FakePlayer b) {
        Animation current = byAdmin.get(admin.getUniqueId());
        if (current != null) {
            stop(current, true);
            if (current.bot() == b) return;
        }
        start(admin, b);
    }

    void stop(Player admin) {
        Animation current = byAdmin.get(admin.getUniqueId());
        if (current == null) admin.sendMessage(manager.msg("animate-none", admin));
        else stop(current, true);
    }

    void stopAll() {
        for (Animation a : List.copyOf(byAdmin.values())) stop(a, false);
    }

    private void start(Player admin, FakePlayer b) {
        if (isAnimated(b)) {
            admin.sendMessage(manager.msg("animate-busy", admin, TextService.placeholder("name", b.name)));
            return;
        }
        boolean joined = false;
        if (b.handle == null) {
            String problem = manager.join(b);
            if (problem != null) {
                admin.sendMessage(manager.msg(problem, admin, TextService.placeholder("name", b.name)));
                return;
            }
            joined = true;
        }
        b.bukkit().teleport(admin.getLocation());
        b.clearState();
        b.handle.setNoGravity(true);

        // den Admin verstecken – über Vanish, wenn das Modul läuft (dann greifen auch dessen Schutzregeln)
        boolean vanishOn = plugin.modules().on("vanish");
        boolean alreadyHidden = vanishOn && plugin.vanish().isVanished(admin);
        boolean vanished = false, hidden = false;
        if (!alreadyHidden) {
            if (vanishOn) {
                plugin.vanish().toggle(admin, false);
                vanished = true;
            } else {
                for (Player other : Bukkit.getOnlinePlayers()) if (other != admin) other.hidePlayer(plugin, admin);
                hidden = true;
            }
        }
        boolean announce = !alreadyHidden && plugin.getConfig().getBoolean("fakeplayers.animate.announce", true);
        if (announce) broadcast(plugin.joinQuit().quitMessage(admin));

        byAdmin.put(admin.getUniqueId(), new Animation(admin, b, joined, vanished, hidden, announce));
        admin.sendMessage(manager.msg("animate-start", admin, TextService.placeholder("name", b.name)));
    }

    /** @param announce false = ohne Join-Nachricht für den Admin (er verlässt den Server oder das Plugin stoppt) */
    private void stop(Animation a, boolean announce) {
        if (byAdmin.remove(a.admin().getUniqueId()) == null) return;
        FakePlayer b = a.bot();
        if (b.handle != null) {
            b.handle.setNoGravity(false);
            b.clearState();
        }
        Player admin = a.admin();
        // auch beim Verlassen zurücksetzen, sonst wäre der Admin beim nächsten Join noch im Vanish
        if (a.vanishedByUs() && plugin.vanish().isVanished(admin)) plugin.vanish().toggle(admin, false);
        if (a.hiddenByUs()) for (Player other : Bukkit.getOnlinePlayers()) other.showPlayer(plugin, admin);
        if (announce && a.announced()) broadcast(plugin.joinQuit().joinMessage(admin));
        if (admin.isOnline()) admin.sendMessage(manager.msg("animate-stop", admin, TextService.placeholder("name", b.name)));
        if (a.joinedForIt()) manager.disconnect(b, "Verlassen");
    }

    private static void broadcast(Component message) {
        if (message != null) Bukkit.broadcast(message);
    }

    /** Jeden Tick: Fake-Spieler auf die Position des Admins setzen (vor seinem eigenen Tick). */
    void tick() {
        for (Animation a : List.copyOf(byAdmin.values())) {
            Player admin = a.admin();
            Player body = a.bot().bukkit();
            if (!admin.isOnline() || body == null) {
                stop(a, true);
                continue;
            }
            if (body.isDead()) continue; // erscheint gleich am Heimatpunkt neu und folgt dann wieder
            Location at = admin.getLocation();
            if (!at.getWorld().equals(body.getWorld())) {
                body.teleport(at);
                continue;
            }
            FakeServerPlayer handle = a.bot().handle;
            handle.snapTo(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
            handle.setYHeadRot(at.getYaw());
            handle.setDeltaMovement(Vec3.ZERO);
            handle.setShiftKeyDown(admin.isSneaking());
            handle.setSprinting(admin.isSprinting());
        }
    }

    // ------------------------------------------------------------
    // Events
    // ------------------------------------------------------------

    /** Chat des Admins schreibt der Fake-Spieler – außer der Admin ist gemutet (dann blockt das die Strafe). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Animation a = byAdmin.get(event.getPlayer().getUniqueId());
        if (a == null) return;
        if (plugin.punishments().activeFor(PunishmentManager.Type.MUTE, event.getPlayer().getUniqueId()) != null) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player body = a.bot().bukkit();
            if (body != null) body.chat(text);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        Animation a = byAdmin.get(event.getPlayer().getUniqueId());
        Player body = a == null ? null : a.bot().bukkit();
        if (body != null) body.swingMainHand();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Animation a = byAdmin.get(event.getPlayer().getUniqueId());
        if (a != null) stop(a, false);
    }

    /** Ohne Vanish-Modul: den Admin auch vor neu beitretenden Spielern verstecken. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (Animation a : byAdmin.values()) {
            if (a.hiddenByUs()) event.getPlayer().hidePlayer(plugin, a.admin());
        }
    }
}
