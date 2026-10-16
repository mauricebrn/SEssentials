package de.sessentials.upgrader;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.gamble.SpinAnimation;
import de.sessentials.upgrader.ItemUpgrader.Session;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleConsumer;

/** Menüs des Item-Upgraders: Hauptmenü mit Dreh-Band und der Item-Katalog (Zielauswahl bzw. Admin-Verwaltung). */
final class UpgraderMenu {

    private static final int BET_SLOT = 10, CHANCE_SLOT = 13, TARGET_SLOT = 16, POINTER_SLOT = 22, STRIP_START = 27, SPIN_SLOT = 49;
    /** Felder des Bands; der Anteil grüner Felder entspricht ungefähr der Chance. */
    private static final int RING = 20;
    private static final int MIN_STEPS = 30;
    private static final int PAGE = 45;

    private final SEssentials plugin;
    private final ItemUpgrader upgrader;

    UpgraderMenu(SEssentials plugin, ItemUpgrader upgrader) {
        this.plugin = plugin;
        this.upgrader = upgrader;
    }

    // ------------------------------------------------------------
    // Hauptmenü
    // ------------------------------------------------------------

    void open(Player player) {
        Menu menu = new Menu(6, Component.text("Item-Upgrader", NamedTextColor.DARK_GRAY));
        menu.set(4, Menu.icon(Material.BOOK, Component.text("So geht's", NamedTextColor.AQUA, TextDecoration.BOLD),
                gray("1. Klicke unten in deinem Inventar"),
                gray("   das Item an, das du setzen willst."),
                gray("2. Wähle rechts ein Ziel – oder nutze"),
                gray("   die Schnellwahl unten."),
                gray("3. Upgrade! Gewinnst du, bekommst du das"),
                gray("   Ziel – sonst ist der Einsatz weg.")), p -> {});
        menu.set(POINTER_SLOT, Menu.icon(Material.HOPPER, Component.text("▼", NamedTextColor.GOLD, TextDecoration.BOLD)), p -> {});
        menu.onPlayerInventoryClick((p, slot) -> {
            upgrader.selectBet(p, slot);
            render(menu, p);
        });
        render(menu, player);
        menu.fillEmpty();
        menu.open(player);
    }

    private void render(Menu menu, Player player) {
        Session s = upgrader.session(player);
        ItemValues values = upgrader.values();
        double chance = upgrader.chance(s);

        if (s.bet == null) {
            menu.set(BET_SLOT, Menu.icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, Component.text("Einsatz", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    gray("Klicke ein Item in deinem Inventar an.")), p -> {});
        } else {
            menu.set(BET_SLOT, withLore(s.bet.clone(),
                    line("Wert", ItemUpgrader.format(values.inputValue(s.bet))),
                    s.bet.getEnchantments().isEmpty() ? null : Component.text("Verzauberungen zählen nicht mit.", NamedTextColor.DARK_GRAY),
                    gray("Anderes Item im Inventar anklicken zum Wechseln.")), p -> {});
        }

        menu.set(CHANCE_SLOT, Menu.icon(chance > 0 ? Material.EXPERIENCE_BOTTLE : Material.GLASS_BOTTLE,
                Component.text("Chance: " + (chance > 0 ? ItemUpgrader.percent(chance) : "–"), chanceColor(chance), TextDecoration.BOLD),
                line("Einsatz-Wert", s.bet == null ? "–" : ItemUpgrader.format(values.inputValue(s.bet))),
                line("Ziel-Wert", s.target == null ? "–" : ItemUpgrader.format(upgrader.targetValue(s)))), p -> {});

        if (s.target == null) {
            menu.set(TARGET_SLOT, Menu.icon(Material.ITEM_FRAME, Component.text("Ziel wählen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    gray("Klick » Item-Katalog")), p -> openCatalog(p, 0, "", Mode.TARGET));
        } else {
            menu.setClick(TARGET_SLOT, withLore(new ItemStack(s.target, s.amount),
                    line("Wert", ItemUpgrader.format(upgrader.targetValue(s))),
                    Component.empty(),
                    hint("Linksklick", "anderes Ziel"),
                    hint("Rechtsklick", "Menge +1"),
                    hint("Shift-Rechtsklick", "Menge −1")), (p, click) -> {
                if (click == ClickType.RIGHT) upgrader.changeAmount(p, 1);
                else if (click == ClickType.SHIFT_RIGHT) upgrader.changeAmount(p, -1);
                else {
                    openCatalog(p, 0, "", Mode.TARGET);
                    return;
                }
                render(menu, p);
            });
        }

        if (!s.spinning) drawStrip(menu, chance, s.offset);

        int[] multipliers = {2, 4, 8};
        for (int i = 0; i < multipliers.length; i++) {
            int m = multipliers[i];
            menu.set(45 + i, Menu.icon(Material.GOLD_NUGGET, Component.text("×" + m, NamedTextColor.GOLD, TextDecoration.BOLD),
                    gray("Ziel mit " + m + "-fachem Wert")), p -> {
                upgrader.quickMultiplier(p, m);
                render(menu, p);
            });
        }
        double[] chances = {0.3, 0.5, 0.7};
        for (int i = 0; i < chances.length; i++) {
            double c = chances[i];
            menu.set(51 + i, Menu.icon(Material.LIME_DYE, Component.text(ItemUpgrader.percent(c), NamedTextColor.GREEN, TextDecoration.BOLD),
                    gray("Ziel für etwa " + ItemUpgrader.percent(c) + " Chance")), p -> {
                upgrader.quickChance(p, c);
                render(menu, p);
            });
        }
        menu.set(SPIN_SLOT, Menu.icon(s.spinning ? Material.CLOCK : Material.ANVIL,
                Component.text(s.spinning ? "Dreht …" : "Upgrade!", NamedTextColor.GREEN, TextDecoration.BOLD),
                s.bet != null && s.target != null ? gray("Bei Misserfolg ist der Einsatz weg.") : gray("Wähle Einsatz und Ziel.")),
                p -> spin(p, menu));
    }

    private void spin(Player player, Menu menu) {
        ItemUpgrader.Outcome outcome = upgrader.spin(player);
        if (outcome == null) {
            render(menu, player);
            return;
        }
        Session s = upgrader.session(player);
        int greens = greens(outcome.chance());
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int land = outcome.success() ? r.nextInt(greens) : greens + r.nextInt(RING - greens);
        int from = s.offset;
        int steps = MIN_STEPS + Math.floorMod(land - from - MIN_STEPS, RING);
        render(menu, player);
        SpinAnimation.run(plugin, steps, i -> {
            drawStrip(menu, outcome.chance(), from + i);
            player.getWorld().playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.6f);
        }, () -> {
            s.offset = land;
            upgrader.finish(player, outcome);
            if (player.isOnline()) render(menu, player);
        });
    }

    private static int greens(double chance) {
        return (int) Math.clamp(Math.round(chance * RING), 1, RING - 1);
    }

    /** Band aus grünen (Gewinn) und roten Feldern, {@code offset} = Feld unter dem Zeiger. */
    private static void drawStrip(Menu menu, double chance, int offset) {
        int greens = greens(chance);
        for (int i = 0; i < 9; i++) {
            int cell = Math.floorMod(offset + i - 4, RING);
            ItemStack pane = chance <= 0
                    ? Menu.icon(Material.GRAY_STAINED_GLASS_PANE, Component.text("–", NamedTextColor.GRAY))
                    : cell < greens
                    ? Menu.icon(Material.LIME_STAINED_GLASS_PANE, Component.text("Gewinn", NamedTextColor.GREEN))
                    : Menu.icon(Material.RED_STAINED_GLASS_PANE, Component.text("Verloren", NamedTextColor.RED));
            menu.set(STRIP_START + i, pane, p -> {});
        }
    }

    // ------------------------------------------------------------
    // Katalog
    // ------------------------------------------------------------

    /** Katalog: Zielauswahl, Verwaltung (Sperrliste, Grundwert) oder Einsatz-/Gewinn-Werte. */
    enum Mode {
        TARGET("Upgrader – Ziel wählen"), ADMIN("Upgrader – Verwaltung"), VALUES("Upgrader – Werte");

        final String title;

        Mode(String title) {
            this.title = title;
        }
    }

    void openCatalog(Player player, int page, String query, Mode mode) {
        ItemValues values = upgrader.values();
        String q = query.toLowerCase(Locale.ROOT).replace(' ', '_');
        List<Material> items = (mode == Mode.TARGET ? values.sortedOutput() : ItemValues.allItems()).stream()
                .filter(m -> mode != Mode.TARGET || !upgrader.blacklisted(m))
                .filter(m -> q.isEmpty() || m.name().toLowerCase(Locale.ROOT).contains(q))
                .toList();
        int pages = Math.max(1, (items.size() + PAGE - 1) / PAGE);
        int current = Math.clamp(page, 0, pages - 1);
        Session s = upgrader.session(player);

        Menu menu = new Menu(6, Component.text(mode.title, NamedTextColor.DARK_GRAY));
        for (int i = 0; i < PAGE && current * PAGE + i < items.size(); i++) {
            Material m = items.get(current * PAGE + i);
            List<Component> lore = switch (mode) {
                case TARGET -> {
                    List<Component> l = new ArrayList<>();
                    l.add(line("Wert", ItemUpgrader.format(values.outputValue(m))));
                    if (s.bet != null) l.add(line("Chance (1 Stück)", ItemUpgrader.percent(upgrader.chance(values.inputValue(s.bet), values.outputValue(m)))));
                    l.add(Component.empty());
                    l.add(hint("Linksklick", "als Ziel wählen"));
                    l.add(hint("Rechtsklick", "Menge eingeben"));
                    yield l;
                }
                case ADMIN -> {
                    List<Component> l = new ArrayList<>();
                    l.add(line("Wert", values.known(m) ? ItemUpgrader.format(values.value(m)) : "keiner"));
                    if (upgrader.overridden(m)) l.add(Component.text("Wert von Hand gesetzt", NamedTextColor.GOLD));
                    l.add(upgrader.blacklisted(m) ? Component.text("Gesperrt", NamedTextColor.RED) : Component.text("Erlaubt", NamedTextColor.GREEN));
                    l.add(Component.empty());
                    l.add(hint("Linksklick", upgrader.blacklisted(m) ? "freigeben" : "sperren"));
                    l.add(hint("Rechtsklick", "Wert setzen"));
                    yield l;
                }
                case VALUES -> {
                    List<Component> l = new ArrayList<>();
                    l.add(line("Grundwert", values.known(m) ? ItemUpgrader.format(values.value(m)) : "keiner"));
                    l.add(sideLine("Einsatz-Wert", values.knownInput(m), values.inputValue(m), upgrader.sideOverridden(m, true)));
                    l.add(sideLine("Gewinn-Wert", values.knownOutput(m), values.outputValue(m), upgrader.sideOverridden(m, false)));
                    if (upgrader.blacklisted(m)) l.add(Component.text("Gesperrt (Verwaltung)", NamedTextColor.RED));
                    l.add(Component.empty());
                    l.add(hint("Linksklick", "Einsatz-Wert setzen"));
                    l.add(hint("Rechtsklick", "Gewinn-Wert setzen"));
                    l.add(hint("Shift-Klick", "beide zurücksetzen"));
                    yield l;
                }
            };
            menu.setClick(i, withLore(new ItemStack(m), lore.toArray(Component[]::new)), (p, click) -> {
                switch (mode) {
                    case ADMIN -> adminClick(p, m, click, current, query);
                    case VALUES -> valuesClick(p, m, click, current, query);
                    case TARGET -> {
                        if (click.isRightClick()) askAmount(p, m);
                        else {
                            upgrader.selectTarget(p, m, 1);
                            open(p);
                        }
                    }
                }
            });
        }

        boolean admin = mode != Mode.TARGET;
        if (current > 0) menu.set(45, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)),
                p -> openCatalog(p, current - 1, query, mode));
        menu.set(47, Menu.icon(Material.OAK_SIGN, Component.text("Suchen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                line("Suche", query.isEmpty() ? "–" : query), gray("Englischer Item-Name, z.B. diamond")), p -> askSearch(p, mode));
        menu.set(49, Menu.icon(admin ? Material.ARROW : Material.BARRIER,
                        Component.text(admin ? "Zum Admin-Panel" : "Zum Upgrader", admin ? NamedTextColor.GRAY : NamedTextColor.RED)),
                p -> {
                    if (admin) plugin.adminPanel().open(p);
                    else open(p);
                });
        menu.set(51, Menu.icon(Material.PAPER, Component.text("Seite " + (current + 1) + " / " + pages, NamedTextColor.WHITE),
                gray(items.size() + " Items")), p -> {});
        if (current < pages - 1) menu.set(53, Menu.icon(Material.ARROW, Component.text("Weiter", NamedTextColor.GRAY)),
                p -> openCatalog(p, current + 1, query, mode));
        menu.fillEmpty();
        menu.open(player);
    }

    private void adminClick(Player player, Material m, ClickType click, int page, String query) {
        if (!click.isRightClick()) {
            upgrader.toggleBlacklist(m);
            openCatalog(player, page, query, Mode.ADMIN);
            return;
        }
        askValue(player, "prompt-value", value -> upgrader.setOverride(m, value), () -> openCatalog(player, page, query, Mode.ADMIN));
    }

    /** Links = Einsatz-Wert, Rechts = Gewinn-Wert, Shift = beide Überschreibungen entfernen. */
    private void valuesClick(Player player, Material m, ClickType click, int page, String query) {
        Runnable reopen = () -> openCatalog(player, page, query, Mode.VALUES);
        if (click.isShiftClick()) {
            upgrader.setSideOverride(m, true, 0);
            upgrader.setSideOverride(m, false, 0);
            reopen.run();
            return;
        }
        boolean input = click.isLeftClick();
        askValue(player, input ? "prompt-input-value" : "prompt-output-value", value -> upgrader.setSideOverride(m, input, value), reopen);
    }

    /** Wert per Chat: Zahl > 0 oder "reset" (= 0, entfernt die Überschreibung). */
    private void askValue(Player player, String messageKey, DoubleConsumer apply, Runnable reopen) {
        player.closeInventory();
        player.sendMessage(upgrader.msg(messageKey, player));
        plugin.chatPrompt().ask(player, input -> {
            if (input.equalsIgnoreCase("reset")) {
                apply.accept(0);
            } else {
                double value = parse(input);
                if (value <= 0) player.sendMessage(upgrader.msg("invalid-number", player));
                else apply.accept(value);
            }
            reopen.run();
        }, reopen);
    }

    private static Component sideLine(String label, boolean known, double value, boolean manual) {
        Component line = line(label, known ? ItemUpgrader.format(value) : "keiner");
        return manual ? line.append(Component.text(" (von Hand)", NamedTextColor.GOLD)) : line;
    }

    private void askAmount(Player player, Material m) {
        player.closeInventory();
        player.sendMessage(upgrader.msg("prompt-amount", player));
        plugin.chatPrompt().ask(player, input -> {
            double amount = parse(input);
            if (amount < 1) player.sendMessage(upgrader.msg("invalid-number", player));
            else upgrader.selectTarget(player, m, (int) Math.min(amount, m.getMaxStackSize()));
            open(player);
        }, () -> open(player));
    }

    private void askSearch(Player player, Mode mode) {
        player.closeInventory();
        player.sendMessage(upgrader.msg("prompt-search", player));
        plugin.chatPrompt().ask(player, input -> openCatalog(player, 0, input.equals("-") ? "" : input.trim(), mode),
                () -> openCatalog(player, 0, "", mode));
    }

    private static double parse(String input) {
        try {
            return Double.parseDouble(input.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------
    // Anzeige
    // ------------------------------------------------------------

    /** Item mit zusätzlichen Lore-Zeilen ({@code null}-Zeilen werden ausgelassen), Name bleibt der des Items. */
    private static ItemStack withLore(ItemStack item, Component... lines) {
        item.editMeta(meta -> {
            List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            for (Component line : lines) if (line != null) lore.add(line.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        });
        return item;
    }

    private static NamedTextColor chanceColor(double chance) {
        return chance <= 0 ? NamedTextColor.GRAY : chance >= 0.5 ? NamedTextColor.GREEN : chance >= 0.2 ? NamedTextColor.YELLOW : NamedTextColor.RED;
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component hint(String click, String action) {
        return Component.text(click, NamedTextColor.YELLOW).append(Component.text(" » " + action, NamedTextColor.GRAY));
    }
}
