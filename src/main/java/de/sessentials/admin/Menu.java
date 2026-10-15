package de.sessentials.admin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Kisten-Menü mit klickbaren Slots. Items lassen sich nicht herausnehmen;
 * {@link Menu.ClickListener} muss einmal registriert sein.
 */
public final class Menu implements InventoryHolder {

    /** Eine Zeile in {@link #sectioned}: Beschriftung links, Kacheln rechts daneben. */
    public record Section(ItemStack label, List<Entry> entries) {}

    public record Entry(ItemStack icon, Consumer<Player> action) {}

    private static final int FIRST_ENTRY_COLUMN = 2;

    private final Inventory inventory;
    private final Map<Integer, BiConsumer<Player, ClickType>> actions = new HashMap<>();
    private Consumer<Player> refresh;
    private BiConsumer<Player, Integer> playerInventoryClick;

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, title);
    }

    public void set(int slot, ItemStack icon, Consumer<Player> action) {
        setClick(slot, icon, (player, click) -> action.accept(player));
    }

    /** Wie {@link #set(int, ItemStack, Consumer)}, aber die Aktion unterscheidet Links-/Rechts-/Shift-Klick. */
    public void setClick(int slot, ItemStack icon, BiConsumer<Player, ClickType> action) {
        inventory.setItem(slot, icon);
        actions.put(slot, action);
    }

    /** Füllt alle leeren Slots mit einer Glasscheibe ohne Tooltip. */
    public void fillEmpty() {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        filler.editMeta(meta -> meta.setHideTooltip(true));
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
    }

    /**
     * Wird nach einem Klick aufgerufen, wenn die Aktion kein anderes Menü geöffnet hat –
     * typischerweise das Menü neu öffnen, damit Status-Anzeigen stimmen.
     */
    public void refreshWith(Consumer<Player> refresh) {
        this.refresh = refresh;
    }

    /**
     * Klicks ins eigene Inventar (unten), solange das Menü offen ist – z.B. um dort ein Item auszuwählen.
     * Bekommt den Slot im Spielerinventar; das Item wird dabei nicht bewegt.
     */
    public void onPlayerInventoryClick(BiConsumer<Player, Integer> action) {
        this.playerInventoryClick = action;
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

    /**
     * Menü mit einer Zeile pro Abschnitt (Beschriftung in Spalte 0, Kacheln ab Spalte 2, bei Bedarf
     * umgebrochen), Rahmen oben (sofern Platz) und unten und optionalem Button unten in der Mitte.
     * Abschnitte ohne Kacheln werden ausgelassen; mehr als 5 Zeilen Inhalt werden abgeschnitten.
     */
    public static Menu sectioned(Component title, List<Section> sections, Entry footer) {
        List<Section> visible = sections.stream().filter(s -> !s.entries().isEmpty()).toList();
        int perRow = 9 - FIRST_ENTRY_COLUMN;
        int contentRows = visible.stream().mapToInt(s -> (s.entries().size() + perRow - 1) / perRow).sum();
        contentRows = Math.max(1, contentRows);
        // oberer Rahmen nur, wenn er noch in 6 Reihen passt
        int top = contentRows + 2 <= 6 ? 1 : 0;
        int rows = Math.min(6, contentRows + 1 + top);
        Menu menu = new Menu(rows, title);

        int row = top;
        for (Section section : visible) {
            if (row >= rows - 1) break;
            menu.inventory.setItem(row * 9, section.label());
            int col = FIRST_ENTRY_COLUMN;
            for (Entry entry : section.entries()) {
                if (col > 8) {
                    row++;
                    col = FIRST_ENTRY_COLUMN;
                    if (row >= rows - 1) break;
                }
                menu.set(row * 9 + col++, entry.icon(), entry.action());
            }
            row++;
        }
        if (footer != null) menu.set((rows - 1) * 9 + 4, footer.icon(), footer.action());
        menu.fillEmpty();
        return menu;
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
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() == player.getInventory() && menu.playerInventoryClick != null) {
                menu.playerInventoryClick.accept(player, event.getSlot());
                return;
            }
            if (event.getClickedInventory() != menu.inventory) return;
            BiConsumer<Player, ClickType> action = menu.actions.get(event.getSlot());
            if (action == null) return;
            action.accept(player, event.getClick());
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
