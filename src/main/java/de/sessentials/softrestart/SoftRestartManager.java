package de.sessentials.softrestart;

import de.sessentials.SEssentials;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Geplanter Neustart: Countdown, Ankündigungen, Speichern, Kicken, Neustart. */
public final class SoftRestartManager implements Listener {

    private final SEssentials plugin;

    private BukkitTask task;
    private long endMillis;
    private long totalSeconds;
    private BossBar bossBar;
    private boolean finishing;

    public SoftRestartManager(SEssentials plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private String msg(String key) {
        return cfg().getString("softrestart.messages." + key, "");
    }

    public boolean isRunning() {
        return task != null || finishing;
    }

    public long remainingSeconds() {
        return Math.max(0, (long) Math.ceil((endMillis - System.currentTimeMillis()) / 1000.0));
    }

    /** Startet den Countdown. @return false, wenn bereits einer läuft. */
    public boolean start(CommandSender sender, int minutes) {
        if (isRunning()) {
            sender.sendMessage(plugin.text().render(msg("already-running"), time(remainingSeconds())));
            return false;
        }
        totalSeconds = minutes * 60L;
        endMillis = System.currentTimeMillis() + totalSeconds * 1000L;

        if (cfg().getBoolean("softrestart.bossbar.enabled", true)) {
            BossBar.Color color;
            try {
                color = BossBar.Color.valueOf(cfg().getString("softrestart.bossbar.color", "RED").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                color = BossBar.Color.RED;
            }
            bossBar = BossBar.bossBar(Component.empty(), 1f, color, BossBar.Overlay.PROGRESS);
            for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bossBar);
        }

        Bukkit.broadcast(plugin.text().render(msg("started"), time(totalSeconds)));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 20L);
        return true;
    }

    public boolean cancel() {
        if (task == null) return false;
        task.cancel();
        task = null;
        hideBossBar();
        Bukkit.broadcast(plugin.text().render(msg("cancelled")));
        return true;
    }

    /** Beim Plugin-Disable aufrufen. */
    public void shutdown() {
        if (task != null) task.cancel();
        task = null;
        hideBossBar();
    }

    private void tick() {
        long remaining = remainingSeconds();

        if (bossBar != null) {
            bossBar.name(plugin.text().render(cfg().getString("softrestart.bossbar.text", ""), time(remaining)));
            bossBar.progress(totalSeconds <= 0 ? 0f : Math.max(0f, Math.min(1f, remaining / (float) totalSeconds)));
        }

        if (remaining <= 0) {
            task.cancel();
            task = null;
            finish();
            return;
        }

        Set<Long> announceAt = new HashSet<>(cfg().getLongList("softrestart.announce-at"));
        if (announceAt.contains(remaining)) {
            Bukkit.broadcast(plugin.text().render(msg("announce"), time(remaining)));
        }

        long titleBelow = cfg().getLong("softrestart.title-below", 10);
        if (titleBelow > 0 && remaining <= titleBelow) {
            Title title = Title.title(
                    plugin.text().render(msg("title"), time(remaining)),
                    plugin.text().render(msg("subtitle"), time(remaining)),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200)));
            for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(title);
        }
    }

    private void finish() {
        finishing = true;
        hideBossBar();
        Bukkit.broadcast(plugin.text().render(msg("saving")));

        // 1) Spielerdaten speichern
        Bukkit.savePlayers();

        // 2) Alle Spieler mit konfigurierter Nachricht kicken
        String kickFormat = msg("kick");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.kick(plugin.text().render(kickFormat, p));
        }

        // 3) Alle Welten speichern
        for (World world : Bukkit.getWorlds()) {
            world.save();
        }
        plugin.getLogger().info("Softrestart: Spieler gekickt, Welten gespeichert.");

        // 4) Nach kurzer Pause den Neustart-Befehl ausführen
        String command = cfg().getString("softrestart.final-command", "restart");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getLogger().info("Softrestart: führe '/" + command + "' aus.");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }, 40L);
    }

    private void hideBossBar() {
        if (bossBar == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bossBar);
        bossBar = null;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bossBar != null) event.getPlayer().showBossBar(bossBar);
    }

    /** Liefert den <time>-Platzhalter. */
    public TagResolver time(long seconds) {
        return Placeholder.component("time", plugin.text().render(formatTime(seconds)));
    }

    private String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        String fmt;
        if (m > 0 && s == 0) {
            fmt = cfg().getString("softrestart.time-format.minutes", "<m> Minute(n)");
        } else if (m > 0) {
            fmt = cfg().getString("softrestart.time-format.minutes-seconds", "<m>:<ss> Minuten");
        } else {
            fmt = cfg().getString("softrestart.time-format.seconds", "<s> Sekunde(n)");
        }
        return fmt.replace("<m>", String.valueOf(m))
                .replace("<ss>", String.format("%02d", s))
                .replace("<s>", String.valueOf(s));
    }
}
