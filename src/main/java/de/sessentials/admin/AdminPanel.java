package de.sessentials.admin;

import de.sessentials.SEssentials;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Zentrales Admin-Panel (/admin). Jede Anwendung ist eine Kachel in einer Kategorie-Zeile und
 * erscheint nur mit ihrem eigenen Recht. Neue Anwendungen werden über {@link #addApp} eingehängt.
 */
public final class AdminPanel {

    /** Kategorien in Anzeigereihenfolge (eine Zeile pro Kategorie). */
    public enum Category {
        MODERATION("Moderation", Material.RED_STAINED_GLASS_PANE, NamedTextColor.RED),
        PLAYERS("Spieler & Teleport", Material.LIME_STAINED_GLASS_PANE, NamedTextColor.GREEN),
        SERVER("Server", Material.ORANGE_STAINED_GLASS_PANE, NamedTextColor.GOLD),
        SETTINGS("Einstellungen", Material.LIGHT_BLUE_STAINED_GLASS_PANE, NamedTextColor.AQUA);

        private final String label;
        private final Material marker;
        private final TextColor color;

        Category(String label, Material marker, TextColor color) {
            this.label = label;
            this.marker = marker;
            this.color = color;
        }

        public ItemStack labelItem() {
            return Menu.icon(marker, Component.text("» " + label, color, TextDecoration.BOLD));
        }
    }

    /**
     * @param visible wer die Kachel sieht (meist ein Recht)
     * @param icon   Kachel, wird bei jedem Öffnen neu erzeugt (z.B. mit aktuellem Status)
     * @param action Klick auf die Kachel – Aktion ausführen oder Untermenü öffnen
     */
    private record App(Category category, Predicate<Player> visible, Function<Player, ItemStack> icon, Consumer<Player> action) {}

    private final SEssentials plugin;
    private final List<App> apps = new ArrayList<>();

    public AdminPanel(SEssentials plugin) {
        this.plugin = plugin;
    }

    public void addApp(Category category, String permission, Function<Player, ItemStack> icon, Consumer<Player> action) {
        addApp(category, p -> p.hasPermission(permission), icon, action);
    }

    public void addApp(Category category, Predicate<Player> visible, Function<Player, ItemStack> icon, Consumer<Player> action) {
        apps.add(new App(category, visible, icon, action));
    }

    public void open(Player player) {
        List<Menu.Section> sections = new ArrayList<>();
        for (Category category : Category.values()) {
            List<Menu.Entry> entries = apps.stream()
                    .filter(app -> app.category() == category && app.visible().test(player))
                    .map(app -> new Menu.Entry(app.icon().apply(player), app.action()))
                    .toList();
            sections.add(new Menu.Section(category.labelItem(), entries));
        }
        Component title = plugin.text().render(plugin.getConfig().getString("admin.title"), player);
        Menu menu = Menu.sectioned(title, sections,
                new Menu.Entry(Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory));
        menu.refreshWith(this::open);
        menu.open(player);
    }
}
