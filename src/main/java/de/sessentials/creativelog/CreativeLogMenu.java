package de.sessentials.creativelog;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.creativelog.CreativeLog.Entry;
import de.sessentials.creativelog.CreativeLog.PlayerLog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/** CreativeLog im Admin-Panel: Spieler mit Log, zuletzt aktive zuerst; Klick zeigt den Log im Chat. */
public final class CreativeLogMenu {

    public static final String PERM = CreativeLog.PERM_VIEW;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
    private static final int PER_PAGE = 45;

    private final SEssentials plugin;
    private final CreativeLog log;

    public CreativeLogMenu(SEssentials plugin, CreativeLog log) {
        this.plugin = plugin;
        this.log = log;
    }

    public void open(Player viewer) {
        List<PlayerLog> players = log.logs().stream()
                .filter(l -> l.latest() != null)
                .sorted(Comparator.comparingLong((PlayerLog l) -> l.latest().time()).reversed())
                .toList();
        Menu menu = new Menu(6, Component.text("CreativeLog", NamedTextColor.DARK_GRAY));

        for (int i = 0; i < Math.min(players.size(), PER_PAGE); i++) {
            PlayerLog l = players.get(i);
            Entry last = l.latest();
            Player online = Bukkit.getPlayer(l.id);
            boolean creative = online != null && online.getGameMode() == GameMode.CREATIVE;
            String lastText = last.text().length() > 40 ? last.text().substring(0, 37) + "..." : last.text();
            ItemStack head = Menu.icon(Material.PLAYER_HEAD, Component.text(l.name, NamedTextColor.WHITE, TextDecoration.BOLD),
                    creative ? Component.text("● Gerade im Kreativmodus", NamedTextColor.GREEN)
                            : Component.text(online != null ? "● Online" : "○ Offline", NamedTextColor.GRAY),
                    line("Einträge", String.valueOf(l.entries.size())),
                    line("Zuletzt", TIME.format(Instant.ofEpochMilli(last.time()))),
                    Component.text(lastText, NamedTextColor.DARK_GRAY),
                    Component.empty(),
                    Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » Log im Chat anzeigen", NamedTextColor.GRAY)));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(l.id)));
            menu.set(i, head, p -> {
                p.closeInventory();
                log.show(p, l.name, 1);
            });
        }

        boolean on = log.enabled();
        menu.set(45, Menu.icon(on ? Material.LIME_DYE : Material.GRAY_DYE,
                Component.text("CreativeLog", on ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                on ? Component.text("Status: An", NamedTextColor.GREEN) : Component.text("Status: Aus", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » umschalten", NamedTextColor.GRAY))), p -> {
            plugin.getConfig().set("creativelog.enabled", !on);
            plugin.saveConfig();
            log.reload();
        });
        menu.set(49, Menu.icon(Material.NETHER_STAR, Component.text("Zum Admin-Panel", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        if (players.size() > PER_PAGE) {
            menu.set(53, Menu.icon(Material.PAPER, Component.text("+" + (players.size() - PER_PAGE) + " weitere", NamedTextColor.GRAY),
                    Component.text("/creativelog <spieler>", NamedTextColor.DARK_GRAY)), p -> {});
        }
        menu.fillEmpty();
        menu.refreshWith(this::open);
        menu.open(viewer);
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
