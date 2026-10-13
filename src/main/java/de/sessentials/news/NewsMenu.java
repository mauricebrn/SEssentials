package de.sessentials.news;

import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import de.sessentials.admin.Menu;
import de.sessentials.news.NewsManager.News;
import de.sessentials.news.NewsManager.Status;
import de.sessentials.text.Durations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** News-Verwaltung: Übersicht aller News und Editor für eine News. */
public final class NewsMenu {

    private static final int PER_PAGE = 45;

    private final SEssentials plugin;
    private final NewsManager news;

    NewsMenu(SEssentials plugin, NewsManager news) {
        this.plugin = plugin;
        this.news = news;
    }

    // ------------------------------------------------------------
    // Übersicht
    // ------------------------------------------------------------

    public void openPanel(Player player) {
        Menu menu = new Menu(6, Component.text("News-Verwaltung", NamedTextColor.DARK_GRAY));
        long now = System.currentTimeMillis();
        List<News> all = news.all();
        for (int i = 0; i < Math.min(PER_PAGE, all.size()); i++) {
            News n = all.get(i);
            Status status = n.status(now);
            menu.setClick(i, Menu.icon(statusIcon(status),
                    plugin.text().render(n.title, player).colorIfAbsent(NamedTextColor.WHITE).decorate(TextDecoration.BOLD)
                            .append(Component.text(" (Nr. " + n.id + ")", NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, false)),
                    line("Status", statusLabel(status)),
                    line("Priorität", news.priority(n, player)),
                    line("Erstellt", Component.text(NewsManager.DATE.format(Instant.ofEpochMilli(n.created)), NamedTextColor.WHITE)),
                    line("Start", time(n.start, "sofort")),
                    line("Ende", time(n.expires, "unbegrenzt")),
                    Component.empty(),
                    Component.text(news.shortText(n, player, 35), NamedTextColor.DARK_GRAY),
                    Component.empty(),
                    hint("Linksklick", "Bearbeiten"),
                    hint("Rechtsklick", "Sichtbarkeit umschalten")), (p, click) -> {
                if (click.isRightClick()) {
                    n.visible = !n.visible;
                    news.save();
                    openPanel(p);
                } else {
                    openEditor(p, n);
                }
            });
        }

        menu.set(45, Menu.icon(Material.SPYGLASS, Component.text("Feed-Vorschau", NamedTextColor.YELLOW, TextDecoration.BOLD),
                Component.text("Zeigt den Feed so,", NamedTextColor.GRAY),
                Component.text("wie Spieler ihn sehen.", NamedTextColor.GRAY)), p -> {
            p.closeInventory();
            news.feed(p, false);
        });
        if (player.hasPermission("sess.admin")) {
            menu.set(48, Menu.icon(Material.NETHER_STAR, Component.text("Zum Admin-Panel", NamedTextColor.GRAY)),
                    plugin.adminPanel()::open);
        }
        menu.set(49, Menu.icon(Material.EMERALD, Component.text("+ Neue News", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("Erstellt einen neuen Entwurf.", NamedTextColor.GRAY)), p -> openEditor(p, news.create()));
        menu.set(53, Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory);
        menu.fillEmpty();
        menu.open(player);
    }

    // ------------------------------------------------------------
    // Editor
    // ------------------------------------------------------------

    public void openEditor(Player player, News n) {
        if (news.get(n.id) == null) {
            player.sendMessage(news.msg("messages.not-found", player));
            openPanel(player);
            return;
        }
        Menu menu = new Menu(3, Component.text("News Nr. " + n.id + " bearbeiten", NamedTextColor.DARK_GRAY));

        menu.set(10, Menu.icon(Material.NAME_TAG, Component.text("Titel", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Aktuell", plugin.text().render(n.title, player)), Component.empty(), hint("Klick", "Neuen Titel eingeben")),
                p -> prompt(p, n, "prompt-title", input -> n.title = input));

        menu.set(11, Menu.icon(Material.WRITABLE_BOOK, Component.text("Inhalt ersetzen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Aktuell", Component.text(news.shortText(n, player, 35), NamedTextColor.DARK_GRAY)), Component.empty(),
                        hint("Klick", "Neuen Inhalt eingeben")),
                p -> prompt(p, n, "prompt-content", input -> n.content = input));

        menu.set(12, Menu.icon(Material.FEATHER, Component.text("Inhalt ergänzen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Component.text("Hängt Text an den Inhalt an –", NamedTextColor.GRAY),
                        Component.text("für Texte länger als eine Chatnachricht.", NamedTextColor.GRAY), Component.empty(),
                        hint("Klick", "Text anhängen")),
                p -> prompt(p, n, "prompt-append", input -> n.content = n.content + " " + input));

        menu.setClick(13, Menu.icon(Material.NETHER_STAR, Component.text("Priorität", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Aktuell", news.priority(n, player)), Component.empty(),
                        hint("Linksklick", "erhöhen"), hint("Rechtsklick", "senken")),
                (p, click) -> {
                    n.priority = NewsManager.clampPriority(n.priority + (click.isRightClick() ? -1 : 1));
                    news.save();
                    openEditor(p, n);
                });

        menu.setClick(14, Menu.icon(Material.COMPASS, Component.text("Startzeit", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Aktuell", time(n.start, "sofort")), Component.empty(),
                        hint("Linksklick", "Startzeit setzen"), hint("Rechtsklick", "sofort")),
                (p, click) -> {
                    if (click.isRightClick()) {
                        n.start = null;
                        news.save();
                        openEditor(p, n);
                    } else {
                        promptDuration(p, n, "prompt-start", d -> n.start = System.currentTimeMillis() + d.toMillis());
                    }
                });

        menu.setClick(15, Menu.icon(Material.CLOCK, Component.text("Laufzeit", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        line("Läuft ab", time(n.expires, "unbegrenzt")), Component.empty(),
                        hint("Linksklick", "Laufzeit setzen (ab Start)"), hint("Rechtsklick", "unbegrenzt")),
                (p, click) -> {
                    if (click.isRightClick()) {
                        n.expires = null;
                        news.save();
                        openEditor(p, n);
                    } else {
                        promptDuration(p, n, "prompt-end", d -> {
                            long base = n.start != null && n.start > System.currentTimeMillis() ? n.start : System.currentTimeMillis();
                            n.expires = base + d.toMillis();
                        });
                    }
                });

        Status status = n.status(System.currentTimeMillis());
        menu.set(16, Menu.icon(n.visible ? Material.LIME_DYE : Material.GRAY_DYE,
                        n.visible ? Component.text("Veröffentlicht", NamedTextColor.GREEN, TextDecoration.BOLD)
                                : Component.text("Entwurf", NamedTextColor.GRAY, TextDecoration.BOLD),
                        line("Status", statusLabel(status)), Component.empty(), hint("Klick", "Veröffentlichen / Entwurf")),
                p -> {
                    n.visible = !n.visible;
                    news.save();
                    openEditor(p, n);
                });

        menu.set(18, Menu.icon(Material.ARROW, Component.text("« Zurück", NamedTextColor.GRAY, TextDecoration.BOLD)), this::openPanel);

        menu.set(22, Menu.icon(Material.SPYGLASS, Component.text("Vorschau", NamedTextColor.AQUA, TextDecoration.BOLD),
                        Component.text("Zeigt die vollständige", NamedTextColor.GRAY),
                        Component.text("Nachricht im Chat.", NamedTextColor.GRAY)),
                p -> {
                    p.closeInventory();
                    news.show(p, n, false);
                    p.sendMessage(news.msg("messages.back-to-editor", p, TextService.placeholder("id", String.valueOf(n.id))));
                });

        menu.setClick(26, Menu.icon(Material.TNT, Component.text("Löschen", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("Löscht diese News endgültig.", NamedTextColor.GRAY), Component.empty(),
                        hint("Shift + Klick", "Löschen")),
                (p, click) -> {
                    if (!click.isShiftClick()) {
                        p.sendMessage(news.msg("messages.delete-hint", p));
                        return;
                    }
                    news.delete(n);
                    p.sendMessage(news.msg("messages.deleted", p, TextService.placeholder("id", String.valueOf(n.id))));
                    openPanel(p);
                });

        menu.fillEmpty();
        menu.open(player);
    }

    // ------------------------------------------------------------
    // Chat-Eingaben
    // ------------------------------------------------------------

    private void prompt(Player player, News n, String messageKey, Consumer<String> apply) {
        ask(player, n, messageKey, input -> {
            apply.accept(input);
            return true;
        });
    }

    private void promptDuration(Player player, News n, String messageKey, Consumer<Duration> apply) {
        ask(player, n, messageKey, input -> {
            Duration d = Durations.parse(input);
            if (d == null) {
                player.sendMessage(news.msg("messages.invalid-duration", player));
                return false;
            }
            apply.accept(d);
            return true;
        });
    }

    /** Fragt im Chat; {@code handler} liefert {@code false} bei ungültiger Eingabe – dann wird erneut gefragt. */
    private void ask(Player player, News n, String messageKey, Predicate<String> handler) {
        player.closeInventory();
        player.sendMessage(news.msg("messages." + messageKey, player));
        plugin.chatPrompt().ask(player, input -> {
            if (news.get(n.id) == null) {
                player.sendMessage(news.msg("messages.not-found", player));
                return;
            }
            if (!handler.test(input)) {
                ask(player, n, messageKey, handler);
                return;
            }
            news.save();
            player.sendMessage(news.msg("messages.saved", player));
            openEditor(player, n);
        }, () -> cancelled(player, n));
    }

    private void cancelled(Player player, News n) {
        player.sendMessage(news.msg("messages.cancelled", player));
        if (news.get(n.id) != null) openEditor(player, n);
    }

    // ------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------

    private static Material statusIcon(Status status) {
        return switch (status) {
            case ACTIVE -> Material.PAPER;
            case DRAFT -> Material.GRAY_DYE;
            case SCHEDULED -> Material.LIGHT_BLUE_DYE;
            case EXPIRED -> Material.ORANGE_DYE;
        };
    }

    private static Component statusLabel(Status status) {
        NamedTextColor color = switch (status) {
            case ACTIVE -> NamedTextColor.GREEN;
            case DRAFT -> NamedTextColor.GRAY;
            case SCHEDULED -> NamedTextColor.AQUA;
            case EXPIRED -> NamedTextColor.GOLD;
        };
        return Component.text(status.label, color);
    }

    private static Component time(Long millis, String fallback) {
        return millis == null
                ? Component.text(fallback, NamedTextColor.GREEN)
                : Component.text(NewsManager.DATE.format(Instant.ofEpochMilli(millis)), NamedTextColor.WHITE);
    }

    private static Component line(String label, Component value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(value);
    }

    private static Component hint(String click, String action) {
        return Component.text(click, NamedTextColor.YELLOW).append(Component.text(" » " + action, NamedTextColor.GRAY));
    }
}
