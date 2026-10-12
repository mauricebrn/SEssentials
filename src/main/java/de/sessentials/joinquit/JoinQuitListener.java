package de.sessentials.joinquit;

import de.sessentials.SEssentials;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinQuitListener implements Listener {

    private final SEssentials plugin;
    private volatile boolean enabled;
    private volatile String joinFormat;
    private volatile String quitFormat;

    public JoinQuitListener(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Ohne explizite Defaults lesen: fehlende Keys in älteren Configs fallen auf die config.yml im JAR zurück. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("join-quit.enabled");
        joinFormat = cfg.getString("join-quit.join");
        quitFormat = cfg.getString("join-quit.quit");
    }

    /** Join-Nachricht wie beim echten Join (auch für Fake-Joins). {@code null} = keine Nachricht. */
    public Component joinMessage(Player player) {
        if (!enabled) return Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, player.displayName());
        return render(joinFormat, player);
    }

    /** Leave-Nachricht wie beim echten Quit (auch für Fake-Leaves). {@code null} = keine Nachricht. */
    public Component quitMessage(Player player) {
        if (!enabled) return Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, player.displayName());
        return render(quitFormat, player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        event.joinMessage(joinMessage(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!enabled) return;
        event.quitMessage(quitMessage(event.getPlayer()));
    }

    /** Leerer Text = keine Nachricht. */
    private Component render(String format, Player player) {
        if (format == null || format.isEmpty()) return null;
        return plugin.text().render(format, player);
    }
}
