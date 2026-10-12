package de.sessentials.admin;

import de.sessentials.SEssentials;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Zentrales Admin-Panel (/admin). Jede Anwendung ist eine Kachel und erscheint nur
 * mit ihrem eigenen Recht. Neue Anwendungen werden über {@link #addApp} eingehängt.
 */
public final class AdminPanel {

    /**
     * @param visible wer die Kachel sieht (meist ein Recht)
     * @param icon   Kachel, wird bei jedem Öffnen neu erzeugt (z.B. mit aktuellem Status)
     * @param action Klick auf die Kachel – Aktion ausführen oder Untermenü öffnen
     */
    private record App(Predicate<Player> visible, Function<Player, ItemStack> icon, Consumer<Player> action) {}

    private final SEssentials plugin;
    private final List<App> apps = new ArrayList<>();

    public AdminPanel(SEssentials plugin) {
        this.plugin = plugin;
    }

    public void addApp(String permission, Function<Player, ItemStack> icon, Consumer<Player> action) {
        addApp(p -> p.hasPermission(permission), icon, action);
    }

    public void addApp(Predicate<Player> visible, Function<Player, ItemStack> icon, Consumer<Player> action) {
        apps.add(new App(visible, icon, action));
    }

    public void open(Player player) {
        List<App> visible = apps.stream().filter(app -> app.visible().test(player)).toList();
        Component title = plugin.text().render(plugin.getConfig().getString("admin.title"), player);
        Menu menu = new Menu((visible.size() + 8) / 9, title);
        for (int i = 0; i < Math.min(visible.size(), menu.size()); i++) {
            App app = visible.get(i);
            menu.set(i, app.icon().apply(player), app.action());
        }
        menu.refreshWith(this::open);
        menu.open(player);
    }
}
