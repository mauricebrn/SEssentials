package de.sessentials.rules;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.rules.RulesManager.Acceptance;
import de.sessentials.rules.RulesManager.Rule;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Regel-Verwaltung: Übersicht, Editor für eine Regel und Rollout-Tracker (wer hat die aktuelle Version akzeptiert?). */
public final class RulesMenu {

    private static final int PER_PAGE = 45;

    private final SEssentials plugin;
    private final RulesManager rules;

    RulesMenu(SEssentials plugin, RulesManager rules) {
        this.plugin = plugin;
        this.rules = rules;
    }

    // ------------------------------------------------------------
    // Übersicht
    // ------------------------------------------------------------

    public void openPanel(Player player) {
        Menu menu = new Menu(6, Component.text("Regel-Verwaltung", NamedTextColor.DARK_GRAY));
        List<Rule> all = rules.all();
        for (int i = 0; i < Math.min(PER_PAGE, all.size()); i++) {
            Rule r = all.get(i);
            menu.setClick(i, Menu.icon(r.visible ? Material.WRITTEN_BOOK : Material.GRAY_DYE,
                    rules.title(r, player).colorIfAbsent(NamedTextColor.WHITE).decorate(TextDecoration.BOLD)
                            .append(Component.text(" (ID " + r.id + ")", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false)),
                    line("Status", visibility(r)),
                    line("Position", Component.text(rules.position(r), NamedTextColor.WHITE)),
                    line("Strafe", penalty(r)),
                    Component.empty(),
                    Component.text(rules.shortText(r, player, 35), NamedTextColor.DARK_GRAY),
                    Component.empty(),
                    hint("Linksklick", "Bearbeiten"),
                    hint("Rechtsklick", "Sichtbarkeit umschalten"),
                    hint("Shift + Links", "nach oben"),
                    hint("Shift + Rechts", "nach unten")), (p, click) -> {
                if (click.isShiftClick()) {
                    rules.move(r, click.isRightClick() ? 1 : -1);
                    openPanel(p);
                } else if (click.isRightClick()) {
                    r.visible = !r.visible;
                    rules.save();
                    openPanel(p);
                } else {
                    openEditor(p, r);
                }
            });
        }

        menu.set(45, Menu.icon(Material.SPYGLASS, Component.text("Vorschau", NamedTextColor.YELLOW, TextDecoration.BOLD),
                Component.text("Zeigt die Übersicht so,", NamedTextColor.GRAY),
                Component.text("wie Spieler sie sehen.", NamedTextColor.GRAY)), p -> {
            p.closeInventory();
            rules.feed(p, true);
        });
        menu.setClick(47, Menu.icon(Material.BELL, Component.text("Akzeptanz-Version", NamedTextColor.GOLD, TextDecoration.BOLD),
                line("Aktuelle Version", Component.text(rules.version(), NamedTextColor.WHITE)),
                Component.empty(),
                Component.text("Nach wichtigen Änderungen erhöhen,", NamedTextColor.GRAY),
                Component.text("damit alle Spieler die Regeln", NamedTextColor.GRAY),
                Component.text("erneut akzeptieren müssen.", NamedTextColor.GRAY),
                Component.empty(),
                hint("Shift + Klick", "Neue Version")), (p, click) -> {
            if (!click.isShiftClick()) {
                p.sendMessage(rules.msg("version-hint", p));
                return;
            }
            rules.bumpVersion();
            p.sendMessage(rules.msg("version-bumped", p, TextService.placeholder("version", String.valueOf(rules.version()))));
            openPanel(p);
        });
        if (player.hasPermission("sess.admin")) {
            menu.set(48, Menu.icon(Material.NETHER_STAR, Component.text("Zum Admin-Panel", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        }
        menu.set(49, Menu.icon(Material.EMERALD, Component.text("+ Neue Regel", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("Erstellt eine neue Regel", NamedTextColor.GRAY),
                Component.text("(zunächst unsichtbar).", NamedTextColor.GRAY)), p -> openEditor(p, rules.create()));
        Stats stats = stats();
        menu.set(51, Menu.icon(Material.PLAYER_HEAD, Component.text("Rollout-Tracker", NamedTextColor.AQUA, TextDecoration.BOLD),
                line("Version " + rules.version(), Component.text(stats.accepted.size() + " / " + stats.total() + " (" + stats.percent() + "%)", NamedTextColor.GREEN)),
                bar(stats),
                Component.empty(),
                hint("Klick", "Tracker öffnen")), p -> openRollout(p, true, 1));
        menu.set(53, Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory);
        menu.fillEmpty();
        menu.open(player);
    }

    // ------------------------------------------------------------
    // Editor
    // ------------------------------------------------------------

    public void openEditor(Player player, Rule r) {
        if (rules.get(r.id) == null) {
            player.sendMessage(rules.msg("gone", player));
            openPanel(player);
            return;
        }
        Menu menu = new Menu(3, Component.text("Regel ID " + r.id + " bearbeiten", NamedTextColor.DARK_GRAY));

        menu.set(10, Menu.icon(Material.NAME_TAG, Component.text("Titel", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Aktuell", rules.title(r, player)), Component.empty(), hint("Klick", "Neuen Titel eingeben")),
                p -> prompt(p, r, "prompt-title", input -> r.title = input));

        menu.set(11, Menu.icon(Material.WRITABLE_BOOK, Component.text("Inhalt ersetzen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Aktuell", Component.text(rules.shortText(r, player, 35), NamedTextColor.DARK_GRAY)), Component.empty(),
                        hint("Klick", "Neuen Inhalt eingeben")),
                p -> prompt(p, r, "prompt-content", input -> r.content = input));

        menu.set(12, Menu.icon(Material.FEATHER, Component.text("Inhalt ergänzen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Component.text("Hängt Text an den Inhalt an –", NamedTextColor.GRAY),
                        Component.text("für Texte länger als eine Chatnachricht.", NamedTextColor.GRAY), Component.empty(),
                        hint("Klick", "Text anhängen")),
                p -> prompt(p, r, "prompt-append", input -> r.content = r.content + " " + input));

        menu.setClick(13, Menu.icon(Material.IRON_SWORD, Component.text("Strafe", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Aktuell", penalty(r)), Component.empty(),
                        hint("Linksklick", "Strafe festlegen"), hint("Rechtsklick", "Strafe entfernen")),
                (p, click) -> {
                    if (click.isRightClick()) {
                        r.penalty = null;
                        rules.save();
                        openEditor(p, r);
                    } else {
                        prompt(p, r, "prompt-penalty", input -> r.penalty = input.equalsIgnoreCase("keine") || input.equalsIgnoreCase("none") ? null : input);
                    }
                });

        menu.setClick(14, Menu.icon(Material.LADDER, Component.text("Reihenfolge", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Position", Component.text(rules.position(r) + " von " + rules.all().size(), NamedTextColor.WHITE)), Component.empty(),
                        hint("Linksklick", "nach oben"), hint("Rechtsklick", "nach unten")),
                (p, click) -> {
                    rules.move(r, click.isRightClick() ? 1 : -1);
                    openEditor(p, r);
                });

        menu.set(16, Menu.icon(r.visible ? Material.LIME_DYE : Material.GRAY_DYE, visibility(r).decorate(TextDecoration.BOLD),
                        Component.empty(), hint("Klick", "Sichtbar / Unsichtbar")),
                p -> {
                    r.visible = !r.visible;
                    rules.save();
                    openEditor(p, r);
                });

        menu.set(18, Menu.icon(Material.ARROW, Component.text("« Zurück", NamedTextColor.GRAY, TextDecoration.BOLD)), this::openPanel);

        menu.set(22, Menu.icon(Material.SPYGLASS, Component.text("Vorschau", NamedTextColor.AQUA, TextDecoration.BOLD),
                        Component.text("Zeigt die vollständige", NamedTextColor.GRAY),
                        Component.text("Regel im Chat.", NamedTextColor.GRAY)),
                p -> {
                    p.closeInventory();
                    rules.show(p, r, false, true);
                    p.sendMessage(rules.msg("back-to-editor", p, TextService.placeholder("id", String.valueOf(r.id))));
                });

        menu.setClick(26, Menu.icon(Material.TNT, Component.text("Löschen", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("Löscht diese Regel endgültig.", NamedTextColor.GRAY), Component.empty(),
                        hint("Shift + Klick", "Löschen")),
                (p, click) -> {
                    if (!click.isShiftClick()) {
                        p.sendMessage(rules.msg("delete-hint", p));
                        return;
                    }
                    rules.delete(r);
                    p.sendMessage(rules.msg("deleted", p, TextService.placeholder("id", String.valueOf(r.id))));
                    openPanel(p);
                });

        menu.fillEmpty();
        menu.open(player);
    }

    private void prompt(Player player, Rule r, String messageKey, Consumer<String> apply) {
        player.closeInventory();
        player.sendMessage(rules.msg(messageKey, player));
        plugin.chatPrompt().ask(player, input -> {
            if (rules.get(r.id) == null) {
                player.sendMessage(rules.msg("gone", player));
                return;
            }
            apply.accept(input);
            rules.save();
            player.sendMessage(rules.msg("saved", player));
            openEditor(player, r);
        }, () -> {
            player.sendMessage(rules.msg("cancelled", player));
            if (rules.get(r.id) != null) openEditor(player, r);
        });
    }

    // ------------------------------------------------------------
    // Rollout-Tracker
    // ------------------------------------------------------------

    /** Alle bekannten Spieler, aufgeteilt nach akzeptiert / ausstehend (nach Name sortiert). */
    private record Stats(List<OfflinePlayer> accepted, List<OfflinePlayer> pending) {
        int total() {
            return accepted.size() + pending.size();
        }

        int percent() {
            return total() == 0 ? 0 : Math.round(accepted.size() * 100f / total());
        }
    }

    private Stats stats() {
        List<OfflinePlayer> acc = new ArrayList<>(), pend = new ArrayList<>();
        Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(p -> p.getName() != null)
                .sorted(Comparator.comparing(OfflinePlayer::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(p -> (rules.hasAccepted(p.getUniqueId()) ? acc : pend).add(p));
        return new Stats(acc, pend);
    }

    /** {@code showAccepted}: akzeptierte oder ausstehende Spieler zeigen. */
    public void openRollout(Player player, boolean showAccepted, int page) {
        Stats stats = stats();
        List<OfflinePlayer> shown = showAccepted ? stats.accepted : stats.pending;
        int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.max(1, Math.min(page, pages));
        int version = rules.version();
        Menu menu = new Menu(6, Component.text("Rollout v" + version + " » " + (showAccepted ? "Akzeptiert" : "Ausstehend")
                + " (" + current + "/" + pages + ")", NamedTextColor.DARK_GRAY));

        int start = (current - 1) * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < shown.size(); i++) {
            OfflinePlayer target = shown.get(start + i);
            Component online = target.isOnline() ? Component.text("Online", NamedTextColor.GREEN) : Component.text("Offline", NamedTextColor.GRAY);
            Acceptance a = rules.acceptance(target.getUniqueId());
            List<Component> lore = showAccepted
                    ? List.of(Component.text("✔ Akzeptiert", NamedTextColor.GREEN),
                    line("Am", Component.text(RulesManager.DATE.format(Instant.ofEpochMilli(a.time())), NamedTextColor.WHITE)),
                    line("Status", online))
                    : List.of(Component.text("✘ Ausstehend", NamedTextColor.RED),
                    a == null ? Component.text("Noch nie akzeptiert", NamedTextColor.GRAY)
                            : line("Zuletzt", Component.text("Version " + a.version(), NamedTextColor.WHITE)),
                    line("Status", online));
            ItemStack head = Menu.icon(Material.PLAYER_HEAD, Component.text(target.getName(), NamedTextColor.WHITE), lore.toArray(Component[]::new));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(target));
            menu.set(i, head, p -> {});
        }

        long onlinePending = Bukkit.getOnlinePlayers().stream().filter(rules::needsAccept).count();
        List<Component> statLore = new ArrayList<>(List.of(
                line("Version", Component.text(version, NamedTextColor.WHITE)),
                line("Akzeptiert", Component.text(stats.accepted.size() + " / " + stats.total() + " (" + stats.percent() + "%)", NamedTextColor.GREEN)),
                bar(stats),
                line("Ausstehend", Component.text(stats.pending.size() + " (davon online: " + onlinePending + ")", NamedTextColor.RED)),
                Component.empty(),
                Component.text("Frühere Versionen:", NamedTextColor.GRAY)));
        int listed = 0;
        for (int v = version - 1; v >= 1 && listed < 5; v--, listed++) {
            statLore.add(Component.text(" v" + v + ": " + rules.confirmations().getOrDefault(v, 0) + " Bestätigungen", NamedTextColor.DARK_GRAY));
        }
        if (listed == 0) statLore.add(Component.text(" keine", NamedTextColor.DARK_GRAY));
        menu.set(49, Menu.icon(Material.BOOK, Component.text("Statistik", NamedTextColor.AQUA, TextDecoration.BOLD),
                statLore.toArray(Component[]::new)), p -> {});

        menu.set(45, Menu.icon(Material.ARROW, Component.text("« Zurück", NamedTextColor.GRAY, TextDecoration.BOLD)), this::openPanel);
        menu.set(47, Menu.icon(Material.HOPPER, Component.text("Filter", NamedTextColor.YELLOW, TextDecoration.BOLD),
                line("Aktuell", showAccepted ? Component.text("Akzeptiert", NamedTextColor.GREEN) : Component.text("Ausstehend", NamedTextColor.RED)),
                Component.empty(), hint("Klick", showAccepted ? "Ausstehende zeigen" : "Akzeptierte zeigen")),
                p -> openRollout(p, !showAccepted, 1));
        if (current > 1) {
            menu.set(48, Menu.icon(Material.ARROW, Component.text("‹ Vorherige Seite", NamedTextColor.YELLOW, TextDecoration.BOLD)),
                    p -> openRollout(p, showAccepted, current - 1));
        }
        if (current < pages) {
            menu.set(50, Menu.icon(Material.ARROW, Component.text("Nächste Seite ›", NamedTextColor.YELLOW, TextDecoration.BOLD)),
                    p -> openRollout(p, showAccepted, current + 1));
        }
        menu.set(51, Menu.icon(Material.BELL, Component.text("Erinnern", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("Schickt allen Online-Spielern,", NamedTextColor.GRAY),
                Component.text("die noch nicht akzeptiert haben,", NamedTextColor.GRAY),
                Component.text("einen Hinweis.", NamedTextColor.GRAY),
                Component.empty(),
                line("Betrifft", Component.text(onlinePending + " Spieler", NamedTextColor.WHITE)),
                Component.empty(), hint("Klick", "Erinnern")), p -> {
            int count = rules.remindAll();
            p.sendMessage(rules.msg("reminded", p, TextService.placeholder("count", String.valueOf(count))));
        });
        menu.set(53, Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory);
        menu.fillEmpty();
        menu.open(player);
    }

    // ------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------

    private Component visibility(Rule r) {
        int number = rules.number(r);
        return r.visible ? Component.text("Sichtbar (§" + number + ")", NamedTextColor.GREEN) : Component.text("Unsichtbar", NamedTextColor.GRAY);
    }

    private static Component penalty(Rule r) {
        return r.penalty == null ? Component.text("keine", NamedTextColor.GRAY) : Component.text(r.penalty, NamedTextColor.RED);
    }

    /** Fortschrittsbalken aus 20 Strichen. */
    private static Component bar(Stats stats) {
        int full = stats.total() == 0 ? 0 : Math.round(stats.accepted.size() * 20f / stats.total());
        return Component.text("|".repeat(full), NamedTextColor.GREEN).append(Component.text("|".repeat(20 - full), NamedTextColor.DARK_GRAY));
    }

    private static Component line(String label, Component value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(value);
    }

    private static Component hint(String click, String action) {
        return Component.text(click, NamedTextColor.YELLOW).append(Component.text(" » " + action, NamedTextColor.GRAY));
    }
}
