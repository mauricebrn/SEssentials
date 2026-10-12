package de.sessentials.detector;

import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import de.sessentials.notify.Notifications.DetectorLevel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Erkennt beim Join den Client anhand von Brand und registrierten Plugin-Channels.
 * Hack-Clients werden über konfigurierbare Signaturen erkannt – das erwischt nur Clients,
 * die sich nicht tarnen. Kostet eine Prüfung pro Join, keine Tick-Last.
 */
public final class ClientDetector implements Listener {

    private static final String REPORTER = "Client-Detector";

    enum Kind { VANILLA, MODDED, HACK }

    private final SEssentials plugin;
    private volatile boolean enabled;
    private volatile long delayTicks;
    private volatile boolean reportModded;
    private volatile List<String> signatures = List.of();

    public ClientDetector(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Ohne explizite Defaults lesen: fehlende Keys in älteren Configs fallen auf die config.yml im JAR zurück. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("client-detector.enabled");
        delayTicks = Math.max(1L, cfg.getLong("client-detector.check-delay-ticks"));
        reportModded = cfg.getBoolean("client-detector.report-modded");
        signatures = cfg.getStringList("client-detector.hack-signatures").stream()
                .map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isBlank()).toList();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        // Brand und Channels kommen in der Konfigurationsphase; kurz warten, damit späte Registrierungen dabei sind
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) check(player);
        }, delayTicks);
    }

    private void check(Player player) {
        String brand = player.getClientBrandName();
        String brandText = brand == null || brand.isBlank() ? "unbekannt" : brand;
        Set<String> channels = new TreeSet<>(player.getListeningPluginChannels());

        String match = findSignature(brandText, channels);
        Kind kind;
        if (match != null) kind = Kind.HACK;
        // Vanilla registriert keine Plugin-Channels; "vanilla" mit Channels ist ein getarnter Mod-Client
        else if (brandText.equalsIgnoreCase("vanilla") && channels.isEmpty()) kind = Kind.VANILLA;
        else kind = Kind.MODDED;

        List<String> details = new ArrayList<>();
        details.add("Client-Brand: " + brandText);
        details.add("Sprache: " + player.locale());
        details.add("UUID: " + player.getUniqueId());
        if (match != null) details.add("Signatur-Treffer: " + match);
        details.add("Channels (" + channels.size() + "):");
        for (String channel : channels) details.add("  " + channel);

        int reportId = -1;
        if (kind == Kind.HACK) {
            reportId = plugin.reports().createSystemReport(REPORTER, player.getName(), "Hack-Client erkannt: " + match, details);
        } else if (kind == Kind.MODDED && reportModded) {
            reportId = plugin.reports().createSystemReport(REPORTER, player.getName(), "Mod-Client: " + brandText, details);
        }

        notifyAdmins(player, kind, brandText, channels, match, reportId);
    }

    private String findSignature(String brand, Set<String> channels) {
        String lowerBrand = brand.toLowerCase(Locale.ROOT);
        for (String sig : signatures) {
            if (lowerBrand.contains(sig)) return sig + " (Brand)";
            for (String channel : channels) {
                if (channel.toLowerCase(Locale.ROOT).contains(sig)) return sig + " (Channel " + channel + ")";
            }
        }
        return null;
    }

    private void notifyAdmins(Player player, Kind kind, String brand, Set<String> channels, String match, int reportId) {
        DetectorLevel required = switch (kind) {
            case HACK -> DetectorLevel.HACK;
            case MODDED -> DetectorLevel.MODDED;
            case VANILLA -> DetectorLevel.ALL;
        };
        String key = switch (kind) {
            case HACK -> "hack";
            case MODDED -> "modded";
            case VANILLA -> "vanilla";
        };
        TagResolver tags = TagResolver.resolver(
                TextService.placeholder("client", brand),
                TextService.placeholder("channels", channels.isEmpty() ? "keine" : String.join("\n", channels)),
                TextService.placeholder("match", match == null ? "" : match),
                TextService.placeholder("id", reportId < 0 ? "-" : String.valueOf(reportId)));
        Component msg = plugin.text().render(plugin.getConfig().getString("client-detector.messages." + key), player, tags);

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (plugin.notifications().detectorLevel(admin).ordinal() >= required.ordinal()) admin.sendMessage(msg);
        }
        if (kind == Kind.HACK) plugin.getLogger().warning(player.getName() + ": Hack-Client erkannt – " + match + " (Brand " + brand + ")");
    }
}
