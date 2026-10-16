package de.sessentials.clan;

import de.sessentials.SEssentials;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Die Clan-Truhe als Block (Endertruhe mit Markierung) nahe dem Clan-Home: Rechtsklick öffnet das gemeinsame
 * Inventar, Abbauen gibt das Truhen-Item zurück. Aus der Ferne geöffnet ({@link #open} mit {@code remote})
 * kann – je nach {@code clans.chest.remote-deposit} – nur entnommen werden.
 */
final class ClanChest implements Listener {

    private final SEssentials plugin;
    private final ClanManager clans;
    private final ClanCommands commands;
    private final NamespacedKey key;
    /** Spieler, die die Truhe gerade aus der Ferne offen haben. */
    private final Set<UUID> remoteViewers = new HashSet<>();

    ClanChest(SEssentials plugin, ClanManager clans, ClanCommands commands) {
        this.plugin = plugin;
        this.clans = clans;
        this.commands = commands;
        this.key = new NamespacedKey(plugin, "clan-chest");
    }

    ItemStack item() {
        ItemStack item = new ItemStack(Material.ENDER_CHEST);
        item.editMeta(meta -> {
            meta.displayName(plugin.text().render(plugin.getConfig().getString("clans.chest.item-name")).decoration(TextDecoration.ITALIC, false));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isChestItem(ItemStack item) {
        return item != null && item.getType() == Material.ENDER_CHEST
                && item.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    void open(Player player, Clan clan, boolean remote) {
        if (remote) remoteViewers.add(player.getUniqueId());
        else remoteViewers.remove(player.getUniqueId());
        player.openInventory(clans.chest(clan));
    }

    // ------------------------------------------------------------
    // Block
    // ------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!isChestItem(event.getItemInHand())) return;
        Player player = event.getPlayer();
        Clan clan = clans.of(player.getUniqueId());
        String error = null;
        if (clan == null) error = "no-clan";
        else if (!clan.chestBought) error = "chest-not-bought";
        else if (clan.chestBlock != null) error = "chest-already-placed";
        else if (clan.home == null) error = "no-home";
        else {
            double max = plugin.getConfig().getDouble("clans.chest.max-distance-from-home");
            Location at = event.getBlockPlaced().getLocation().toCenterLocation();
            if (at.getWorld() != clan.home.getWorld() || at.distance(clan.home) > max) error = "chest-too-far";
        }
        if (error != null) {
            event.setCancelled(true);
            player.sendMessage(commands.msg(error, player));
            return;
        }
        clans.setChestBlock(clan, event.getBlockPlaced().getLocation());
        player.sendMessage(commands.msg("chest-placed", player));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.ENDER_CHEST) return;
        Clan clan = clans.chestAt(block.getLocation());
        if (clan == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!clan.members.contains(player.getUniqueId())) {
            player.sendMessage(commands.msg("chest-foreign", player, commands.clanTags(clan)));
            return;
        }
        open(player, clan, false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Clan clan = clans.chestAt(event.getBlock().getLocation());
        if (clan == null) return;
        Player player = event.getPlayer();
        if (!clan.members.contains(player.getUniqueId()) && !player.hasPermission(ClanCommands.PERM_ADMIN)) {
            event.setCancelled(true);
            player.sendMessage(commands.msg("chest-foreign", player, commands.clanTags(clan)));
            return;
        }
        event.setDropItems(false);
        clans.setChestBlock(clan, null);
        // Inhalt bleibt im virtuellen Inventar; nur das Truhen-Item kommt zurück
        player.getInventory().addItem(item()).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        player.sendMessage(commands.msg("chest-picked-up", player));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protect(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protect(event.blockList());
    }

    private void protect(List<Block> blocks) {
        blocks.removeIf(b -> b.getType() == Material.ENDER_CHEST && clans.chestAt(b.getLocation()) != null);
    }

    // ------------------------------------------------------------
    // Inventar: Fernzugriff ohne Einlagern
    // ------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Clan.ChestHolder)) return;
        if (!remoteViewers.contains(event.getWhoClicked().getUniqueId()) || remoteDeposit()) return;
        Inventory top = event.getView().getTopInventory();
        boolean inTop = event.getClickedInventory() == top;
        boolean allowed = switch (event.getAction()) {
            // Entnehmen, Wegwerfen, Shift-Klick aus der Truhe heraus
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, DROP_ALL_SLOT, DROP_ONE_SLOT,
                 DROP_ALL_CURSOR, DROP_ONE_CURSOR, COLLECT_TO_CURSOR, NOTHING -> true;
            case MOVE_TO_OTHER_INVENTORY -> inTop;
            // im eigenen Inventar ist alles erlaubt, solange nichts in die Truhe wandert
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR, HOTBAR_SWAP -> !inTop;
            default -> false;
        };
        if (!allowed) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player p) p.sendMessage(commands.msg("chest-remote-no-deposit", p));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Clan.ChestHolder)) return;
        if (!remoteViewers.contains(event.getWhoClicked().getUniqueId()) || remoteDeposit()) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Clan.ChestHolder)) return;
        remoteViewers.remove(event.getPlayer().getUniqueId());
        clans.save();
    }

    private boolean remoteDeposit() {
        return plugin.getConfig().getBoolean("clans.chest.remote-deposit");
    }
}
