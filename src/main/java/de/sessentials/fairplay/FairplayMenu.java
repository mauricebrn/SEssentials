package de.sessentials.fairplay;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Fairplay im Admin-Panel: Rangliste, Verlauf pro Spieler, Strike entfernen, zurücksetzen. */
public final class FairplayMenu {

    public static final String PERM = FairplayManager.PERM_ADMIN;
    private static final int PER_PAGE = 45;

    private final SEssentials plugin;
    private final FairplayManager fairplay;

    public FairplayMenu(SEssentials plugin, FairplayManager fairplay) {
        this.plugin = plugin;
        this.fairplay = fairplay;
    }

    public void openList(Player player) {
        openList(player, 1);
    }

    private void openList(Player player, int requestedPage) {
        List<FairplayManager.Standing> standings = fairplay.standings();
        int maxPage = Math.max(1, (standings.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(1, Math.min(requestedPage, maxPage));
        Menu menu = new Menu(6, Component.text("Fairplay – Seite " + page + "/" + maxPage, NamedTextColor.DARK_GRAY));
        int start = (page - 1) * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < standings.size(); i++) {
            FairplayManager.Standing s = standings.get(start + i);
            menu.set(i, head(s, Component.empty(),
                            Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » Verlauf und Aktionen", NamedTextColor.GRAY))),
                    p -> openPlayer(p, s.id(), page));
        }
        if (page > 1) menu.set(45, Menu.icon(Material.ARROW, Component.text("« Vorherige Seite", NamedTextColor.GREEN)), p -> openList(p, page - 1));
        menu.set(48, settings(), p -> {});
        menu.set(49, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        if (page < maxPage) menu.set(53, Menu.icon(Material.ARROW, Component.text("Nächste Seite »", NamedTextColor.GREEN)), p -> openList(p, page + 1));
        menu.fillEmpty();
        menu.open(player);
    }

    private void openPlayer(Player player, UUID id, int page) {
        FairplayManager.Standing s = find(id);
        if (s == null) {
            openList(player, page);
            return;
        }
        Menu menu = new Menu(4, Component.text("Fairplay: " + s.name(), NamedTextColor.DARK_GRAY));
        menu.set(4, head(s), p -> {});
        SimpleDateFormat date = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        List<FairplayManager.Violation> history = s.history().reversed();
        for (int i = 0; i < Math.min(9, history.size()); i++) {
            FairplayManager.Violation v = history.get(i);
            menu.set(9 + i, Menu.icon(Material.PAPER, Component.text(fairplay.violationName(v.type()), NamedTextColor.RED),
                    line("Punkte", "-" + v.points()), line("Geldstrafe", v.fine() > 0 ? plugin.bank().format(v.fine()) : "–"),
                    line("Zeit", date.format(new Date(v.time())))), p -> {});
        }
        if (history.isEmpty()) {
            menu.set(13, Menu.icon(Material.LIME_DYE, Component.text("Keine Verstöße", NamedTextColor.GREEN)), p -> {});
        }
        menu.set(29, Menu.icon(Material.MILK_BUCKET, Component.text("Strike entfernen", NamedTextColor.YELLOW),
                line("Aktiv", String.valueOf(s.strikes()))), p -> {
            p.sendMessage(fairplay.msg(fairplay.removeStrike(id) ? "admin-strike-removed" : "admin-no-strike", p, target(s)));
            openPlayer(p, id, page);
        });
        menu.set(33, Menu.icon(Material.TNT, Component.text("Zurücksetzen", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text("Punkte auf " + fairplay.startScore() + ", alle Strikes", NamedTextColor.GRAY),
                Component.text("und der Verlauf werden gelöscht", NamedTextColor.GRAY)), p -> confirmReset(p, s, page));
        menu.set(31, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), p -> openList(p, page));
        menu.fillEmpty();
        menu.open(player);
    }

    private void confirmReset(Player player, FairplayManager.Standing s, int page) {
        Menu menu = new Menu(3, Component.text(s.name() + " zurücksetzen?", NamedTextColor.DARK_GRAY));
        menu.set(11, Menu.icon(Material.LIME_CONCRETE, Component.text("Ja, zurücksetzen", NamedTextColor.RED, TextDecoration.BOLD)), p -> {
            fairplay.reset(s.id());
            p.sendMessage(fairplay.msg("reset", p, target(s)));
            openList(p, page);
        });
        menu.set(15, Menu.icon(Material.RED_CONCRETE, Component.text("Abbrechen", NamedTextColor.GRAY)), p -> openPlayer(p, s.id(), page));
        menu.fillEmpty();
        menu.open(player);
    }

    private FairplayManager.Standing find(UUID id) {
        return fairplay.standings().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    private ItemStack head(FairplayManager.Standing s, Component... extra) {
        var cfg = plugin.getConfig();
        Component[] lore = new Component[3 + extra.length];
        lore[0] = line("Rang", "#" + s.rank());
        lore[1] = line("Punkte", s.score() + "/" + fairplay.startScore());
        lore[2] = line("Strikes", s.strikes() + "/" + cfg.getInt("fairplay.strikes-for-punishment"));
        System.arraycopy(extra, 0, lore, 3, extra.length);
        NamedTextColor color = s.score() <= cfg.getInt("fairplay.strike-threshold") + 10 || s.strikes() > 0 ? NamedTextColor.RED : NamedTextColor.GREEN;
        ItemStack head = Menu.icon(Material.PLAYER_HEAD, Component.text(s.name(), color, TextDecoration.BOLD), lore);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(s.id())));
        return head;
    }

    private ItemStack settings() {
        var cfg = plugin.getConfig();
        String template = cfg.getString("fairplay.punishment-template", "");
        return Menu.icon(Material.COMPARATOR, Component.text("Einstellungen", NamedTextColor.GOLD),
                line("Startwert", String.valueOf(fairplay.startScore())),
                line("Strike bei", "≤ " + cfg.getInt("fairplay.strike-threshold")),
                line("Strafe ab", cfg.getInt("fairplay.strikes-for-punishment") + " Strikes"),
                line("Strafvorlage", template.isBlank() ? "keine" : "#" + template),
                line("Geldstrafe pro Strike", cfg.getLong("fairplay.strike-fine") > 0 ? plugin.bank().format(cfg.getLong("fairplay.strike-fine")) : "keine"),
                line("Strikes verfallen", cfg.getLong("fairplay.strike-expire-days") <= 0 ? "nie" : "nach " + cfg.getLong("fairplay.strike-expire-days") + " Tagen"),
                Component.text("Ändern in config/fairplay.yml", NamedTextColor.DARK_GRAY));
    }

    private static TagResolver target(FairplayManager.Standing s) {
        return TextService.placeholder("target", s.name());
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
