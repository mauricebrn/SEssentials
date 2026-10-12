package de.sessentials.notify;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Persönliche Benachrichtigungs-Einstellungen der Admins (Reports + Client-Detector).
 * Gespeichert in den Spielerdaten, einstellbar über die Kachel „Benachrichtigungen“ im Admin-Panel.
 */
public final class Notifications {

    public static final String PERM_REPORTS = "sess.report.notify";
    public static final String PERM_DETECTOR = "sess.detector.notify";

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

    public Notifications(SEssentials plugin) {
        this.plugin = plugin;
        this.reportsKey = new NamespacedKey(plugin, "notify-reports");
        this.detectorKey = new NamespacedKey(plugin, "notify-detector");
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

    /** Sieht der Spieler die Kachel „Benachrichtigungen“? */
    public boolean canConfigure(Player player) {
        return player.hasPermission(PERM_REPORTS) || player.hasPermission(PERM_DETECTOR);
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
        menu.set(8, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.refreshWith(this::openMenu);
        menu.open(player);
    }
}
