package de.sessentials.tab;

import de.sessentials.SEssentials;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * TAB-Header/Footer, TAB-Namen und Nametags.
 * Nametags laufen über Teams auf dem Haupt-Scoreboard; der Teamname bestimmt
 * gleichzeitig die Sortierung in der TAB-Liste.
 */
public final class TabNametagManager implements Listener {

    private static final String TEAM_PREFIX = "sess_";

    private record GroupStyle(int priority, String prefix, String suffix, NamedTextColor color) {}

    private final SEssentials plugin;
    private final Map<UUID, String> teamOf = new HashMap<>();

    private BukkitTask task;
    private boolean tabEnabled;
    private boolean nametagEnabled;
    private String header;
    private String footer;
    private String playerFormat;
    private GroupStyle defaultStyle;
    private Map<String, GroupStyle> groupStyles = Map.of();

    public TabNametagManager(SEssentials plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        stop();
        FileConfiguration cfg = plugin.getConfig();
        tabEnabled = cfg.getBoolean("tab.enabled", true);
        nametagEnabled = cfg.getBoolean("nametag.enabled", true);
        header = String.join("<newline>", cfg.getStringList("tab.header"));
        footer = String.join("<newline>", cfg.getStringList("tab.footer"));
        playerFormat = cfg.getString("tab.player-format", "");

        defaultStyle = readStyle(cfg.getConfigurationSection("nametag.default"), 99);
        Map<String, GroupStyle> styles = new HashMap<>();
        ConfigurationSection groups = cfg.getConfigurationSection("nametag.groups");
        if (groups != null) {
            for (String key : groups.getKeys(false)) {
                styles.put(key.toLowerCase(Locale.ROOT), readStyle(groups.getConfigurationSection(key), defaultStyle.priority()));
            }
        }
        groupStyles = Map.copyOf(styles);

        // Alten Zustand aufräumen, damit Änderungen sofort greifen
        cleanupTeams();
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playerListName(null);
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }

        if (tabEnabled || nametagEnabled) {
            long interval = Math.max(10L, cfg.getLong("tab.update-interval-ticks", 40L));
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAll, 1L, interval);
        }
    }

    private GroupStyle readStyle(ConfigurationSection sec, int fallbackPriority) {
        if (sec == null) return new GroupStyle(fallbackPriority, "", "", NamedTextColor.WHITE);
        String colorName = sec.getString("name-color", "white").toLowerCase(Locale.ROOT);
        NamedTextColor color = NamedTextColor.NAMES.value(colorName);
        if (color == null) {
            plugin.getLogger().warning("Unbekannte name-color '" + colorName + "' – nutze white.");
            color = NamedTextColor.WHITE;
        }
        int priority = Math.max(0, Math.min(999, sec.getInt("priority", fallbackPriority)));
        return new GroupStyle(priority, sec.getString("prefix", ""), sec.getString("suffix", ""), color);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void shutdown() {
        stop();
        cleanupTeams();
    }

    private void cleanupTeams() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team team : board.getTeams()) {
            if (team.getName().startsWith(TEAM_PREFIX)) team.unregister();
        }
        teamOf.clear();
    }

    private void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
    }

    public void update(Player player) {
        if (tabEnabled) {
            player.sendPlayerListHeaderAndFooter(
                    plugin.text().render(header, player),
                    plugin.text().render(footer, player));
            if (playerFormat != null && !playerFormat.isBlank()) {
                Component name = plugin.text().render(playerFormat, player);
                if (!Objects.equals(name, player.playerListName())) player.playerListName(name);
            }
        }
        if (nametagEnabled) updateTeam(player);
    }

    private void updateTeam(Player player) {
        String group = plugin.text().perms().primaryGroup(player).toLowerCase(Locale.ROOT);
        GroupStyle style = groupStyles.getOrDefault(group, defaultStyle);

        // Teamname: Priorität (Sortierung) + Spielername (eindeutig)
        String teamName = TEAM_PREFIX + String.format("%03d", style.priority()) + "_" + player.getName();
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();

        String old = teamOf.get(player.getUniqueId());
        if (old != null && !old.equals(teamName)) {
            Team oldTeam = board.getTeam(old);
            if (oldTeam != null) oldTeam.unregister();
        }

        Team team = board.getTeam(teamName);
        if (team == null) team = board.registerNewTeam(teamName);
        teamOf.put(player.getUniqueId(), teamName);

        Component prefix = plugin.text().render(style.prefix(), player, TagResolver.empty());
        Component suffix = plugin.text().render(style.suffix(), player, TagResolver.empty());
        if (!Objects.equals(team.prefix(), prefix)) team.prefix(prefix);
        if (!Objects.equals(team.suffix(), suffix)) team.suffix(suffix);
        if (!team.hasColor() || team.color() != style.color()) team.color(style.color());
        if (!team.hasEntry(player.getName())) team.addEntry(player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // einen Tick später, damit LuckPerms die Daten sicher geladen hat
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) update(event.getPlayer());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        String name = teamOf.remove(event.getPlayer().getUniqueId());
        if (name == null) return;
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(name);
        if (team != null) team.unregister();
    }
}
