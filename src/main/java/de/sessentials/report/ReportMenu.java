package de.sessentials.report;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.report.ReportManager.Report;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Report-Übersicht (Liste mit Seiten) und Detailansicht mit Aktionen. */
public final class ReportMenu {

    private static final int PER_PAGE = 45;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final SEssentials plugin;
    private final ReportManager reports;

    ReportMenu(SEssentials plugin, ReportManager reports) {
        this.plugin = plugin;
        this.reports = reports;
    }

    public void openList(Player player, int requestedPage) {
        List<Report> open = reports.openReports();
        int maxPage = Math.max(1, (open.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(1, Math.min(requestedPage, maxPage));

        Menu menu = new Menu(6, Component.text("Reports – Seite " + page + "/" + maxPage, NamedTextColor.DARK_GRAY));
        int start = (page - 1) * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < open.size(); i++) {
            Report r = open.get(start + i);
            List<Component> lore = info(r);
            lore.add(Component.empty());
            lore.add(Component.text("Klicken für Aktionen", NamedTextColor.YELLOW));
            // automatische Meldungen (Client-Detector) als Fernrohr, Spieler-Reports als Buch
            Material icon = r.details.isEmpty() ? Material.BOOK : Material.SPYGLASS;
            menu.set(i, Menu.icon(icon, Component.text("Report #" + r.id, NamedTextColor.RED, TextDecoration.BOLD),
                            lore.toArray(Component[]::new)),
                    p -> openDetail(p, r.id, page));
        }

        if (page > 1) {
            menu.set(45, Menu.icon(Material.ARROW, Component.text("« Vorherige Seite", NamedTextColor.GREEN)),
                    p -> openList(p, page - 1));
        }
        if (player.hasPermission("sess.admin")) {
            menu.set(48, Menu.icon(Material.NETHER_STAR, Component.text("Zum Admin-Panel", NamedTextColor.GRAY)),
                    plugin.adminPanel()::open);
        }
        menu.set(49, Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory);
        if (page < maxPage) {
            menu.set(53, Menu.icon(Material.ARROW, Component.text("Nächste Seite »", NamedTextColor.GREEN)),
                    p -> openList(p, page + 1));
        }
        menu.open(player);
    }

    private void openDetail(Player player, int id, int page) {
        Report r = reports.findOpen(id);
        if (r == null) {
            gone(player, page);
            return;
        }
        Menu menu = new Menu(3, Component.text("Report #" + r.id, NamedTextColor.DARK_GRAY));
        menu.set(4, Menu.icon(Material.BOOK, Component.text("Informationen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                info(r).toArray(Component[]::new)), p -> {});

        menu.set(11, Menu.icon(Material.COMPASS, Component.text("Teleportieren", NamedTextColor.AQUA, TextDecoration.BOLD),
                line("Teleportiere dich zu", r.reported)), p -> {
            Report current = reports.findOpen(id);
            if (current == null) {
                gone(p, page);
                return;
            }
            Player target = Bukkit.getPlayerExact(current.reported);
            if (target == null) {
                p.sendMessage(reports.msg("offline", p, ReportManager.tags(current)));
                return;
            }
            p.closeInventory();
            p.teleportAsync(target.getLocation());
            p.sendMessage(reports.msg("teleported", p, ReportManager.tags(current)));
        });

        menu.set(13, Menu.icon(Material.LIME_DYE, Component.text("Als erledigt markieren", NamedTextColor.GREEN, TextDecoration.BOLD)), p -> {
            Report current = reports.findOpen(id);
            if (current == null) {
                gone(p, page);
                return;
            }
            reports.resolve(current);
            p.sendMessage(reports.msg("resolved", p, ReportManager.tags(current)));
            openList(p, page);
        });

        menu.set(15, Menu.icon(Material.RED_DYE, Component.text("Report löschen", NamedTextColor.RED, TextDecoration.BOLD)), p -> {
            Report current = reports.findOpen(id);
            if (current == null) {
                gone(p, page);
                return;
            }
            reports.delete(current);
            p.sendMessage(reports.msg("deleted", p, ReportManager.tags(current)));
            openList(p, page);
        });

        menu.set(22, Menu.icon(Material.ARROW, Component.text("« Zurück zur Liste", NamedTextColor.GRAY)), p -> openList(p, page));
        menu.open(player);
    }

    /** Report wurde inzwischen von jemand anderem bearbeitet. */
    private void gone(Player player, int page) {
        player.sendMessage(reports.msg("gone", player));
        openList(player, page);
    }

    private static List<Component> info(Report r) {
        List<Component> lines = new ArrayList<>();
        lines.add(line("Gemeldet von", r.reporter));
        lines.add(line("Spieler", r.reported));
        lines.add(line("Grund", r.reason));
        lines.add(line("Zeit", TIME.format(Instant.ofEpochMilli(r.time))));
        for (String detail : r.details) lines.add(Component.text(detail, NamedTextColor.GRAY));
        return lines;
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
