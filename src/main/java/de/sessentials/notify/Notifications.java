package de.sessentials.notify;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Persönliche Benachrichtigungs-Einstellungen der Admins (Reports, Client-Detector, Spy für /msg und /mail).
 * Gespeichert in den Spielerdaten, einstellbar über die Kachel „Benachrichtigungen“ im Admin-Panel;
 * Spy zusätzlich mit /socialspy.
 */
public final class Notifications {

    public static final String PERM_REPORTS = "sess.report.notify";
    public static final String PERM_DETECTOR = "sess.detector.notify";
    public static final String PERM_SPY = "sess.spy";

    /** Welche Joins der Client-Detector meldet. Reihenfolge = aufsteigend gesprächig. */
    public enum DetectorLevel {
        OFF("Aus"),
        HACK("Nur Hack-Clients"),
        MODDED("Alle Mod-Clients"),
        ALL("Alle Joins");

        public final String label;

        DetectorLevel(String label) {
            this.label = label;
        }

        DetectorLevel next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final SEssentials plugin;
    private final NamespacedKey reportsKey;
    private final NamespacedKey detectorKey;
    private final NamespacedKey spyKey;

    public Notifications(SEssentials plugin) {
        this.plugin = plugin;
        this.reportsKey = new NamespacedKey(plugin, "notify-reports");
        this.detectorKey = new NamespacedKey(plugin, "notify-detector");
        this.spyKey = new NamespacedKey(plugin, "notify-spy");
    }

    public boolean wantsReports(Player player) {
        return player.hasPermission(PERM_REPORTS)
                && player.getPersistentDataContainer().getOrDefault(reportsKey, PersistentDataType.BOOLEAN, true);
    }

    public DetectorLevel detectorLevel(Player player) {
        if (!player.hasPermission(PERM_DETECTOR)) return DetectorLevel.OFF;
        String stored = player.getPersistentDataContainer().get(detectorKey, PersistentDataType.STRING);
        try {
            return stored != null ? DetectorLevel.valueOf(stored) : DetectorLevel.HACK;
        } catch (IllegalArgumentException e) {
            return DetectorLevel.HACK;
        }
    }

    /** Spy ist standardmäßig aus und muss bewusst eingeschaltet werden. */
    public boolean wantsSpy(Player player) {
        return player.hasPermission(PERM_SPY)
                && player.getPersistentDataContainer().getOrDefault(spyKey, PersistentDataType.BOOLEAN, false);
    }

    private void toggleSpy(Player player) {
        boolean on = !wantsSpy(player);
        player.getPersistentDataContainer().set(spyKey, PersistentDataType.BOOLEAN, on);
        player.sendMessage(spyMsg(on ? "on" : "off", player));
    }

    /**
     * Kopie einer privaten Nachricht ({@code kind} = "msg" oder "mail") an alle Spione – außer an Absender
     * und Empfänger selbst. Der Text bleibt reiner Text.
     */
    public void spy(String kind, String from, String to, String message) {
        TagResolver tags = TagResolver.resolver(
                TextService.placeholder("sender", from),
                TextService.placeholder("target", to),
                Placeholder.unparsed("message", message));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(from) || p.getName().equalsIgnoreCase(to) || !wantsSpy(p)) continue;
            p.sendMessage(spyMsg(kind, p, tags));
        }
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("socialspy")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_SPY))
                        .executes(ctx -> {
                            toggleSpy((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Private Nachrichten (/msg, /mail) mitlesen – an/aus",
                List.of("spy"));
    }

    private Component spyMsg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("spy.messages." + key, ""), player, extra);
    }

    /** Sieht der Spieler die Kachel „Benachrichtigungen“? */
    public boolean canConfigure(Player player) {
        return player.hasPermission(PERM_REPORTS) || player.hasPermission(PERM_DETECTOR) || player.hasPermission(PERM_SPY);
    }

    public void openMenu(Player player) {
        Menu menu = new Menu(1, Component.text("Benachrichtigungen"));
        PersistentDataContainer data = player.getPersistentDataContainer();
        if (player.hasPermission(PERM_REPORTS)) {
            boolean on = wantsReports(player);
            menu.set(0, Menu.icon(Material.WRITABLE_BOOK, Component.text("Reports", NamedTextColor.RED),
                            on ? Component.text("Status: An", NamedTextColor.GREEN) : Component.text("Status: Aus", NamedTextColor.GRAY),
                            Component.text("Neue /report-Meldungen im Chat", NamedTextColor.DARK_GRAY)),
                    p -> data.set(reportsKey, PersistentDataType.BOOLEAN, !on));
        }
        if (player.hasPermission(PERM_DETECTOR)) {
            DetectorLevel level = detectorLevel(player);
            menu.set(1, Menu.icon(Material.SPYGLASS, Component.text("Client-Detector", NamedTextColor.AQUA),
                            Component.text("Meldet: " + level.label, NamedTextColor.YELLOW),
                            Component.text("Klicken zum Wechseln", NamedTextColor.DARK_GRAY)),
                    p -> data.set(detectorKey, PersistentDataType.STRING, level.next().name()));
        }
        if (player.hasPermission(PERM_SPY)) {
            boolean on = wantsSpy(player);
            menu.set(2, Menu.icon(Material.ENDER_EYE, Component.text("Spy (/msg, /mail)", NamedTextColor.LIGHT_PURPLE),
                            on ? Component.text("Status: An", NamedTextColor.GREEN) : Component.text("Status: Aus", NamedTextColor.GRAY),
                            Component.text("Private Nachrichten mitlesen", NamedTextColor.DARK_GRAY)),
                    p -> data.set(spyKey, PersistentDataType.BOOLEAN, !on));
        }
        menu.set(8, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.refreshWith(this::openMenu);
        menu.open(player);
    }
}
