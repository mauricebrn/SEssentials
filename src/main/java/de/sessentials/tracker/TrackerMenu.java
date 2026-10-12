package de.sessentials.tracker;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.tracker.ItemTracker.History;
import de.sessentials.tracker.ItemTracker.Snapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.ToLongFunction;

/** Item-Tracker im Admin-Panel: Server-Bestände, Spieler-Rangliste und Detailansicht mit Verlauf. */
public final class TrackerMenu {

    public static final String PERM = "sess.tracker";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
    private static final int ITEM_SLOTS = 18;
    private static final int PLAYER_FIRST_SLOT = 18, PLAYERS_PER_PAGE = 27;
    private static final int HISTORY_LINES = 20;
    private static final long DAY = Duration.ofDays(1).toMillis(), WEEK = Duration.ofDays(7).toMillis();

    private final SEssentials plugin;
    private final ItemTracker tracker;

    public TrackerMenu(SEssentials plugin, ItemTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    // ------------------------------------------------------------
    // Übersicht
    // ------------------------------------------------------------

    public void openOverview(Player viewer, int requestedPage) {
        long now = System.currentTimeMillis();
        List<Snapshot> serverHistory = tracker.serverHistory();
        Snapshot current = serverHistory.isEmpty() ? null : serverHistory.getLast();
        Snapshot dayAgo = ItemTracker.atOrBefore(serverHistory, now - DAY);
        Snapshot weekAgo = ItemTracker.atOrBefore(serverHistory, now - WEEK);

        List<History> ranked = tracker.players().stream()
                .filter(h -> h.latest() != null)
                .sorted(Comparator.comparingLong((History h) -> tracker.value(h.latest())).reversed())
                .toList();
        int pages = Math.max(1, (ranked.size() + PLAYERS_PER_PAGE - 1) / PLAYERS_PER_PAGE);
        int page = Math.max(1, Math.min(requestedPage, pages));

        Menu menu = new Menu(6, Component.text("Item-Tracker – Seite " + page + "/" + pages, NamedTextColor.DARK_GRAY));

        // Zeilen 1–2: Server-Bestand pro Item
        List<Material> items = tracker.items();
        for (int i = 0; i < Math.min(items.size(), ITEM_SLOTS); i++) {
            Material m = items.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(line("Auf dem Server", number(current == null ? 0 : current.total(m))));
            lore.add(Component.text("seit 24 h: ", NamedTextColor.GRAY).append(delta(current, dayAgo, s -> s.total(m))));
            lore.add(Component.text("seit 7 Tagen: ", NamedTextColor.GRAY).append(delta(current, weekAgo, s -> s.total(m))));
            long worth = plugin.bankExchange().worth(m);
            if (worth > 0 && current != null) lore.add(line("Wert", plugin.bank().format(worth * current.total(m))));
            lore.add(Component.empty());
            lore.add(hint("Klick", "Verlauf im Chat"));
            menu.set(i, Menu.icon(m, Component.translatable(m.translationKey(), NamedTextColor.WHITE, TextDecoration.BOLD),
                    lore.toArray(Component[]::new)), p -> {
                p.closeInventory();
                sendHistory(p, Component.translatable(m.translationKey()).append(Component.text(" – Server")), serverHistory, s -> s.total(m), false);
            });
        }

        // Zeilen 3–5: Spieler nach Wert
        int start = (page - 1) * PLAYERS_PER_PAGE;
        for (int i = 0; i < PLAYERS_PER_PAGE && start + i < ranked.size(); i++) {
            History h = ranked.get(start + i);
            Snapshot latest = h.latest();
            Snapshot hDay = ItemTracker.atOrBefore(h.snapshots(), now - DAY);
            List<Component> lore = new ArrayList<>();
            lore.add(line("Wert", plugin.bank().format(tracker.value(latest))));
            lore.add(Component.text("seit 24 h: ", NamedTextColor.GRAY).append(delta(latest, hDay, tracker::value)));
            lore.add(line("Kontostand", plugin.bank().format(plugin.bank().balance(h.id()))));
            lore.add(line("Erfasst", TIME.format(Instant.ofEpochMilli(latest.time()))));
            lore.add(Component.empty());
            lore.add(hint("Klick", "Details"));
            menu.set(PLAYER_FIRST_SLOT + i, head(h, Component.text((start + i + 1) + ". " + h.name(), NamedTextColor.WHITE, TextDecoration.BOLD), lore),
                    p -> openPlayer(p, h.id(), page));
        }

        // Fußleiste
        menu.set(45, Menu.icon(Material.BOOK, Component.text("Server-Verlauf (Wert)", NamedTextColor.AQUA, TextDecoration.BOLD),
                historyLore(serverHistory, tracker::value, true).toArray(Component[]::new)), p -> {});
        menu.set(46, Menu.icon(Material.CLOCK, Component.text("Jetzt erfassen", NamedTextColor.GREEN, TextDecoration.BOLD),
                line("Letzte Erfassung", tracker.lastCapture() == 0 ? "–" : TIME.format(Instant.ofEpochMilli(tracker.lastCapture()))),
                line("Erfasste Spieler", number(ranked.size())),
                Component.text("Erfasst alle Online-Spieler sofort.", NamedTextColor.GRAY)), p -> tracker.captureAll());
        if (page > 1) menu.set(48, Menu.icon(Material.ARROW, Component.text("« Vorherige Seite", NamedTextColor.GREEN)), p -> openOverview(p, page - 1));
        menu.set(49, Menu.icon(Material.NETHER_STAR, Component.text("Zum Admin-Panel", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        if (page < pages) menu.set(50, Menu.icon(Material.ARROW, Component.text("Nächste Seite »", NamedTextColor.GREEN)), p -> openOverview(p, page + 1));
        if (!tracker.enabled()) {
            menu.set(53, Menu.icon(Material.BARRIER, Component.text("Tracker deaktiviert", NamedTextColor.RED),
                    Component.text("tracker.enabled in der Config", NamedTextColor.GRAY)), p -> {});
        }
        menu.fillEmpty();
        menu.refreshWith(p -> openOverview(p, page));
        menu.open(viewer);
    }

    // ------------------------------------------------------------
    // Spieler-Details
    // ------------------------------------------------------------

    private void openPlayer(Player viewer, UUID id, int backPage) {
        History h = tracker.player(id);
        if (h == null || h.latest() == null) {
            openOverview(viewer, backPage);
            return;
        }
        long now = System.currentTimeMillis();
        Snapshot latest = h.latest();
        Snapshot dayAgo = ItemTracker.atOrBefore(h.snapshots(), now - DAY);
        Snapshot weekAgo = ItemTracker.atOrBefore(h.snapshots(), now - WEEK);

        Menu menu = new Menu(6, Component.text("Item-Tracker – " + h.name(), NamedTextColor.DARK_GRAY));
        menu.set(4, head(h, Component.text(h.name(), NamedTextColor.WHITE, TextDecoration.BOLD), List.of(
                line("Wert", plugin.bank().format(tracker.value(latest))),
                Component.text("seit 24 h: ", NamedTextColor.GRAY).append(delta(latest, dayAgo, tracker::value)),
                Component.text("seit 7 Tagen: ", NamedTextColor.GRAY).append(delta(latest, weekAgo, tracker::value)),
                line("Kontostand", plugin.bank().format(plugin.bank().balance(id))),
                line("Online", Bukkit.getPlayer(id) != null ? "ja" : "nein"),
                line("Erfasst", TIME.format(Instant.ofEpochMilli(latest.time()))))), p -> {});

        List<Material> items = tracker.items();
        for (int i = 0; i < Math.min(items.size(), ITEM_SLOTS); i++) {
            Material m = items.get(i);
            menu.set(9 + i, Menu.icon(m, Component.translatable(m.translationKey(), NamedTextColor.WHITE, TextDecoration.BOLD),
                    line("Inventar", number(latest.inventory().getOrDefault(m, 0))),
                    line("Enderchest", number(latest.enderChest().getOrDefault(m, 0))),
                    line("Gesamt", number(latest.total(m))),
                    Component.text("seit 24 h: ", NamedTextColor.GRAY).append(delta(latest, dayAgo, s -> s.total(m))),
                    Component.text("seit 7 Tagen: ", NamedTextColor.GRAY).append(delta(latest, weekAgo, s -> s.total(m))),
                    Component.empty(),
                    hint("Klick", "Verlauf im Chat")), p -> {
                p.closeInventory();
                sendHistory(p, Component.translatable(m.translationKey()).append(Component.text(" – " + h.name())), h.snapshots(), s -> s.total(m), false);
            });
        }

        menu.set(31, Menu.icon(Material.BOOK, Component.text("Verlauf (Wert)", NamedTextColor.AQUA, TextDecoration.BOLD),
                historyLore(h.snapshots(), tracker::value, true).toArray(Component[]::new)), p -> {});
        menu.set(49, Menu.icon(Material.ARROW, Component.text("« Zurück", NamedTextColor.GRAY)), p -> openOverview(p, backPage));
        menu.fillEmpty();
        menu.open(viewer);
    }

    // ------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------

    /** Verlauf als Lore-Zeilen, neueste zuerst: "12.10. 14:00  1.234 Taler (+120)". */
    private List<Component> historyLore(List<Snapshot> history, ToLongFunction<Snapshot> metric, boolean money) {
        List<Component> lines = new ArrayList<>();
        if (history.isEmpty()) {
            lines.add(Component.text("Noch keine Erfassung.", NamedTextColor.GRAY));
            return lines;
        }
        for (int i = history.size() - 1; i >= 0 && lines.size() < HISTORY_LINES; i--) {
            Snapshot s = history.get(i);
            Snapshot before = i > 0 ? history.get(i - 1) : null;
            long v = metric.applyAsLong(s);
            lines.add(Component.text(TIME.format(Instant.ofEpochMilli(s.time())) + "  ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(money ? plugin.bank().format(v) : number(v), NamedTextColor.WHITE))
                    .append(Component.text(" "))
                    .append(before == null ? Component.empty() : delta(s, before, metric)));
        }
        return lines;
    }

    private void sendHistory(Player player, Component title, List<Snapshot> history, ToLongFunction<Snapshot> metric, boolean money) {
        player.sendMessage(Component.text("────── ", NamedTextColor.DARK_GRAY).append(title.color(NamedTextColor.GOLD))
                .append(Component.text(" ──────", NamedTextColor.DARK_GRAY)));
        List<Component> lines = historyLore(history, metric, money);
        for (int i = lines.size() - 1; i >= 0; i--) player.sendMessage(lines.get(i));
    }

    /** Veränderung zwischen zwei Erfassungen: grün +, rot −, grau ±0; ohne Vergleichswert „–“. */
    private Component delta(Snapshot now, Snapshot before, ToLongFunction<Snapshot> metric) {
        if (now == null || before == null) return Component.text("–", NamedTextColor.DARK_GRAY);
        long d = metric.applyAsLong(now) - metric.applyAsLong(before);
        if (d == 0) return Component.text("±0", NamedTextColor.GRAY);
        return Component.text((d > 0 ? "+" : "") + number(d), d > 0 ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private ItemStack head(History h, Component name, List<Component> lore) {
        ItemStack head = Menu.icon(Material.PLAYER_HEAD, name, lore.toArray(Component[]::new));
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(h.id())));
        return head;
    }

    private static String number(long n) {
        return NumberFormat.getIntegerInstance(Locale.GERMANY).format(n);
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component hint(String click, String action) {
        return Component.text(click, NamedTextColor.YELLOW).append(Component.text(" » " + action, NamedTextColor.GRAY));
    }
}
