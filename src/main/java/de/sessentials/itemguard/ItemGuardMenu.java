package de.sessentials.itemguard;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** ItemGuard im Admin-Panel: Schalter, Liste verbotener Items und Sofort-Scan. Änderungen landen in der config.yml. */
public final class ItemGuardMenu {

    public static final String PERM = "sess.itemguard.admin";
    private static final int LIST_FIRST = 9, LIST_SIZE = 36;

    private final SEssentials plugin;
    private final ItemGuard guard;

    public ItemGuardMenu(SEssentials plugin, ItemGuard guard) {
        this.plugin = plugin;
        this.guard = guard;
    }

    public void open(Player player) {
        Menu menu = new Menu(6, Component.text("ItemGuard", NamedTextColor.DARK_GRAY));

        // Schalter
        toggle(menu, 1, guard.enabled() ? Material.LIME_DYE : Material.GRAY_DYE, "ItemGuard", guard.enabled(),
                "Scannt beim Join und regelmäßig", () -> guard.set("enabled", !guard.enabled()));
        toggle(menu, 3, Material.EGG, "Spawn-Eier verbieten", guard.spawnEggs(),
                "Entfernt alle Spawn-Eier und blockiert das Benutzen", () -> guard.set("block-spawn-eggs", !guard.spawnEggs()));
        toggle(menu, 5, Material.ENDER_CHEST, "Enderchest scannen", guard.enderChest(),
                "Durchsucht auch die Enderchest", () -> guard.set("scan-enderchest", !guard.enderChest()));
        toggle(menu, 7, Material.SHULKER_BOX, "Shulkerkisten scannen", guard.shulkers(),
                "Durchsucht Shulkerkisten in Inventar und Enderchest", () -> guard.set("scan-shulkers", !guard.shulkers()));

        // Verbotene Items
        List<Material> banned = guard.banned();
        for (int i = 0; i < Math.min(banned.size(), LIST_SIZE); i++) {
            Material m = banned.get(i);
            ItemStack icon = Menu.icon(m.isItem() ? m : Material.BARRIER,
                    Component.translatable(m.translationKey(), NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text(m.name(), NamedTextColor.DARK_GRAY),
                    Component.empty(),
                    hint("Rechtsklick", "nicht mehr verbieten"));
            menu.setClick(LIST_FIRST + i, icon, (p, click) -> {
                if (!click.isRightClick()) return;
                List<Material> updated = new ArrayList<>(guard.banned());
                updated.remove(m);
                guard.setBanned(updated);
            });
        }

        // Fußleiste
        menu.set(45, Menu.icon(Material.HOPPER, Component.text("Item aus der Hand verbieten", NamedTextColor.YELLOW, TextDecoration.BOLD),
                Component.text("Nimmt den Typ des Items in deiner Hand", NamedTextColor.GRAY),
                Component.text("in die Liste auf.", NamedTextColor.GRAY)), p -> {
            Material type = p.getInventory().getItemInMainHand().getType();
            if (type.isAir() || guard.banned().contains(type)) return;
            List<Material> updated = new ArrayList<>(guard.banned());
            updated.add(type);
            guard.setBanned(updated);
        });
        menu.set(47, Menu.icon(Material.SPYGLASS, Component.text("Alle jetzt scannen", NamedTextColor.AQUA, TextDecoration.BOLD),
                Component.text("Spieler mit sess.itemguard.bypass", NamedTextColor.GRAY),
                Component.text("werden übersprungen.", NamedTextColor.GRAY)), p -> guard.scanAll());
        menu.set(49, Menu.icon(Material.NETHER_STAR, Component.text("Zum Admin-Panel", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.set(51, Menu.icon(Material.PAPER, Component.text("Statistik", NamedTextColor.WHITE, TextDecoration.BOLD),
                Component.text("Entfernt seit Serverstart: ", NamedTextColor.GRAY)
                        .append(Component.text(guard.removedSinceStart() + " Items", NamedTextColor.WHITE)),
                Component.text("Verbotene Items: ", NamedTextColor.GRAY)
                        .append(Component.text(String.valueOf(banned.size()), NamedTextColor.WHITE))), p -> {});
        if (banned.size() > LIST_SIZE) {
            menu.set(53, Menu.icon(Material.BARRIER, Component.text("+" + (banned.size() - LIST_SIZE) + " weitere", NamedTextColor.RED),
                    Component.text("Nur in der config.yml sichtbar", NamedTextColor.GRAY)), p -> {});
        }
        menu.fillEmpty();
        menu.refreshWith(this::open);
        menu.open(player);
    }

    private static void toggle(Menu menu, int slot, Material icon, String label, boolean on, String description, Runnable action) {
        menu.set(slot, Menu.icon(icon, Component.text(label, on ? NamedTextColor.GREEN : NamedTextColor.GRAY, TextDecoration.BOLD),
                on ? Component.text("Status: An", NamedTextColor.GREEN) : Component.text("Status: Aus", NamedTextColor.GRAY),
                Component.text(description, NamedTextColor.DARK_GRAY),
                Component.empty(),
                hint("Klick", "umschalten")), p -> action.run());
    }

    private static Component hint(String click, String action) {
        return Component.text(click, NamedTextColor.YELLOW).append(Component.text(" » " + action, NamedTextColor.GRAY));
    }
}
