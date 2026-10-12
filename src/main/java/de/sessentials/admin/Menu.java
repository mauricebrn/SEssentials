package de.sessentials.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Kisten-Menü mit klickbaren Slots. Items lassen sich nicht herausnehmen;
 * {@link Menu.ClickListener} muss einmal registriert sein.
 */
public final class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private Consumer<Player> refresh;

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, title);
    }

    public void set(int slot, ItemStack icon, Consumer<Player> action) {
        inventory.setItem(slot, icon);
        actions.put(slot, action);
    }

    /**
     * Wird nach einem Klick aufgerufen, wenn die Aktion kein anderes Menü geöffnet hat –
     * typischerweise das Menü neu öffnen, damit Status-Anzeigen stimmen.
     */
    public void refreshWith(Consumer<Player> refresh) {
        this.refresh = refresh;
    }

    public int size() {
        return inventory.getSize();
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Item mit Name und Lore, ohne die Standard-Kursivschrift. */
    public static ItemStack icon(Material material, Component name, Component... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(Arrays.stream(lore).map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        });
        return item;
    }

    public static final class ClickListener implements Listener {

        @EventHandler(ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() != menu.inventory || !(event.getWhoClicked() instanceof Player player)) return;
            Consumer<Player> action = menu.actions.get(event.getSlot());
            if (action == null) return;
            action.accept(player);
            if (menu.refresh != null && player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                menu.refresh.accept(player);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
        }
    }
}
