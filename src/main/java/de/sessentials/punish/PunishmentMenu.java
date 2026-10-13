package de.sessentials.punish;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.punish.PunishmentManager.Punishment;
import de.sessentials.punish.PunishmentManager.Type;
import de.sessentials.text.Durations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Strafen im Admin-Panel: aktive Strafen (Rechtsklick hebt auf) und Übersicht der Vorlagen. */
public final class PunishmentMenu {

    public static final String PERM = "sess.punish.history";
    private static final int PER_PAGE = 45;

    private final SEssentials plugin;
    private final PunishmentManager punishments;
    private final PunishCommands commands;

    public PunishmentMenu(SEssentials plugin, PunishmentManager punishments, PunishCommands commands) {
        this.plugin = plugin;
        this.punishments = punishments;
        this.commands = commands;
    }

    public void open(Player viewer) {
        List<Punishment> active = punishments.active();
        Menu menu = new Menu(6, Component.text("Strafen – aktiv: " + active.size(), NamedTextColor.DARK_GRAY));
        long now = System.currentTimeMillis();

        for (int i = 0; i < Math.min(PER_PAGE, active.size()); i++) {
            Punishment p = active.get(i);
            String remaining = p.permanent() ? "permanent" : Durations.format(Duration.ofMillis(p.expires - now));
            Material icon = switch (p.type) {
                case BAN -> Material.PLAYER_HEAD;
                case MUTE -> Material.PAPER;
                case IPBAN -> Material.BARRIER;
            };
            ItemStack item = Menu.icon(icon,
                    Component.text(p.type.label + ": ", color(p.type), TextDecoration.BOLD)
                            .append(Component.text(p.targetName != null ? p.targetName : p.ip, NamedTextColor.WHITE)),
                    line("Grund", p.reason),
                    line("Verbleibend", remaining),
                    line("Von", p.actor),
                    line("Am", PunishmentManager.DATE.format(Instant.ofEpochMilli(p.created))),
                    line("ID", "#" + p.id + (p.template != null ? " (Vorlage " + p.template + ")" : "")),
                    Component.empty(),
                    Component.text("Rechtsklick", NamedTextColor.YELLOW).append(Component.text(" » aufheben", NamedTextColor.GRAY)));
            if (p.type == Type.BAN && p.target != null) {
                item.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(p.target)));
            }
            menu.setClick(i, item, (clicker, click) -> {
                if (!click.isRightClick()) return;
                if (!clicker.hasPermission(PunishCommands.perm(p.type))) {
                    clicker.sendMessage(punishments.msg("no-permission", clicker));
                    return;
                }
                Punishment revoked = punishments.revoke(p, clicker.getName());
                if (revoked != null) commands.revokeAnnounce(clicker, revoked, clicker.getName());
            });
        }

        menu.set(45, Menu.icon(Material.BOOK, Component.text("Vorlagen (/punish <spieler> <id>)", NamedTextColor.AQUA, TextDecoration.BOLD),
                templateLore().toArray(Component[]::new)), p -> {});
        menu.set(49, Menu.icon(Material.NETHER_STAR, Component.text("Zum Admin-Panel", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.set(53, Menu.icon(Material.OAK_SIGN, Component.text("Befehle", NamedTextColor.YELLOW, TextDecoration.BOLD),
                Component.text("/ban, /mute, /ipban <spieler> [dauer|perm] [grund]", NamedTextColor.GRAY),
                Component.text("/unban, /unmute, /unbanip <spieler>", NamedTextColor.GRAY),
                Component.text("/history <spieler>", NamedTextColor.GRAY),
                Component.text("Dauer z.B. 30m, 2h, 7d, 1d12h, 1mo", NamedTextColor.DARK_GRAY)), p -> {});
        menu.fillEmpty();
        menu.refreshWith(this::open);
        menu.open(viewer);
    }

    private List<Component> templateLore() {
        List<Component> lines = new ArrayList<>();
        ConfigurationSection templates = plugin.getConfig().getConfigurationSection("punishments.templates");
        if (templates == null || templates.getKeys(false).isEmpty()) {
            lines.add(Component.text("Keine Vorlagen in der Config.", NamedTextColor.GRAY));
            return lines;
        }
        for (String id : templates.getKeys(false)) {
            ConfigurationSection t = templates.getConfigurationSection(id);
            if (t == null) continue;
            lines.add(Component.text(id + ": ", NamedTextColor.WHITE)
                    .append(Component.text(t.getString("type", "?") + ", " + t.getString("duration", "permanent"), NamedTextColor.GOLD))
                    .append(Component.text(" – " + t.getString("reason", ""), NamedTextColor.GRAY)));
        }
        return lines;
    }

    private static NamedTextColor color(Type type) {
        return switch (type) {
            case BAN -> NamedTextColor.RED;
            case MUTE -> NamedTextColor.GOLD;
            case IPBAN -> NamedTextColor.DARK_RED;
        };
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
