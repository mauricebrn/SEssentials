package de.sessentials.chestshop;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * GUI-Creator für Chestshops: /shop, während man auf eine Kiste (oder für Gebühren auf einen beliebigen Block)
 * schaut. Typ, Item, Menge und Preise werden im Menü eingestellt; „Erstellen“ setzt das Schild an die
 * angeschaute Seite und verbraucht ein Schild aus dem Inventar (nicht im Kreativmodus).
 */
public final class ShopCreatorMenu {

    private static final String PERM_CREATE = "sess.chestshop.create";
    private static final String PERM_ADMIN = "sess.chestshop.admin";
    private static final int RANGE = 5;

    /** Einstellungen bis zum Klick auf „Erstellen“. */
    private static final class Draft {
        final Block target;
        final BlockFace face;
        final Block container;
        boolean fee;
        boolean admin;
        ItemStack item;
        int amount;
        long buy = 100;
        long sell = -1;
        String label = "";

        Draft(Block target, BlockFace face, Block container) {
            this.target = target;
            this.face = face;
            this.container = container;
        }
    }

    private final SEssentials plugin;
    private final ChestShopManager shops;

    public ShopCreatorMenu(SEssentials plugin, ChestShopManager shops) {
        this.plugin = plugin;
        this.shops = shops;
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("shop")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_CREATE))
                        .executes(ctx -> {
                            start((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Erstellt einen Chestshop per Menü (auf eine Kiste schauen)",
                List.of());
    }

    private void start(Player player) {
        Block target = player.getTargetBlockExact(RANGE);
        BlockFace face = player.getTargetBlockFace(RANGE);
        if (target == null || face == null) {
            player.sendMessage(msg("creator-look-at-block", player));
            return;
        }
        if (face == BlockFace.DOWN || !target.getRelative(face).isEmpty()) {
            player.sendMessage(msg("creator-sign-blocked", player));
            return;
        }
        Block container = ChestShopManager.isShopContainer(target) ? target : null;
        Draft draft = new Draft(target, face, container);
        draft.fee = container == null;
        if (container != null) {
            draft.item = ChestShopManager.shopItem("", (Container) container.getState(), player);
        }
        draft.amount = draft.item == null ? 1 : Math.min(64, draft.item.getMaxStackSize());
        open(player, draft);
    }

    // ------------------------------------------------------------
    // Menü
    // ------------------------------------------------------------

    private void open(Player player, Draft d) {
        Menu menu = new Menu(6, Component.text("Shop erstellen", NamedTextColor.DARK_GRAY));

        // Typ
        if (d.container != null) {
            menu.set(3, Menu.icon(d.fee ? Material.OAK_SIGN : Material.CHEST,
                    Component.text("Typ: " + (d.fee ? "Gebühr" : "Item-Shop"), NamedTextColor.YELLOW, TextDecoration.BOLD),
                    Component.text(d.fee ? "Spieler zahlen, ohne ein Item zu bekommen" : "Items aus der Kiste kaufen/verkaufen", NamedTextColor.GRAY),
                    Component.empty(), hint("Klick", "wechseln")), p -> d.fee = !d.fee);
        } else {
            menu.set(3, Menu.icon(Material.OAK_SIGN, Component.text("Typ: Gebühr", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    Component.text("Für einen Item-Shop auf eine Kiste", NamedTextColor.GRAY),
                    Component.text("oder ein Fass schauen.", NamedTextColor.GRAY)), p -> {});
        }
        if (player.hasPermission(PERM_ADMIN)) {
            menu.set(5, Menu.icon(d.admin ? Material.COMMAND_BLOCK : Material.GRAY_DYE,
                    Component.text("AdminShop: " + (d.admin ? "an" : "aus"), d.admin ? NamedTextColor.RED : NamedTextColor.GRAY, TextDecoration.BOLD),
                    Component.text("Unbegrenzter Bestand, Taler vom/an den Server", NamedTextColor.GRAY),
                    Component.empty(), hint("Klick", "umschalten")), p -> d.admin = !d.admin);
        }

        // Item bzw. Beschreibung
        if (d.fee) {
            menu.set(13, Menu.icon(Material.NAME_TAG, Component.text("Beschreibung", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    line("Aktuell", d.label.isEmpty() ? "Gebühr" : d.label),
                    Component.text("z.B. Farm – steht auf dem Schild", NamedTextColor.GRAY),
                    Component.empty(), hint("Klick", "im Chat eingeben")), p -> promptText(p, d));
        } else {
            ItemStack icon = d.item == null
                    ? Menu.icon(Material.BARRIER, Component.text("Kein Item", NamedTextColor.RED, TextDecoration.BOLD))
                    : d.item.clone();
            icon.editMeta(meta -> meta.lore(List.of(
                    Component.text("Gehandeltes Item", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    hint("Klick", "Item aus der Hand übernehmen").decoration(TextDecoration.ITALIC, false))));
            menu.set(13, icon, p -> {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (!hand.isEmpty()) d.item = hand.asOne();
            });

            stepper(menu, 18, Material.CHEST, "Menge pro Handel", d.amount + "x", new long[]{1, 16, 64},
                    delta -> d.amount = (int) Math.max(1, Math.min(2304, d.amount + delta)),
                    p -> promptNumber(p, d, "creator-prompt-amount", v -> d.amount = (int) Math.max(1, Math.min(2304, v))), null);
        }

        // Preise
        stepper(menu, 27, Material.EMERALD, d.fee ? "Gebühr" : "Kaufpreis (Spieler kauft)", priceText(d.buy), new long[]{1, 10, 100},
                delta -> d.buy = Math.max(d.fee ? 1 : 0, Math.max(0, d.buy) + delta),
                p -> promptNumber(p, d, "creator-prompt-price", v -> d.buy = Math.max(0, v)),
                d.fee ? null : () -> d.buy = -1);
        if (!d.fee) {
            stepper(menu, 36, Material.GOLD_INGOT, "Verkaufspreis (Spieler verkauft)", priceText(d.sell), new long[]{1, 10, 100},
                    delta -> d.sell = Math.max(0, Math.max(0, d.sell) + delta),
                    p -> promptNumber(p, d, "creator-prompt-price", v -> d.sell = Math.max(0, v)),
                    () -> d.sell = -1);
        }

        // Fußleiste
        menu.set(45, Menu.icon(Material.BARRIER, Component.text("Abbrechen", NamedTextColor.RED)), Player::closeInventory);
        menu.set(49, Menu.icon(Material.LIME_CONCRETE, Component.text("Erstellen", NamedTextColor.GREEN, TextDecoration.BOLD),
                Component.text("Setzt das Schild an die angeschaute Seite.", NamedTextColor.GRAY),
                Component.text("Verbraucht ein Schild aus dem Inventar.", NamedTextColor.GRAY)), p -> create(p, d));
        menu.fillEmpty();
        menu.refreshWith(p -> open(p, d));
        menu.open(player);
    }

    /**
     * Zeile mit Beschriftung (Spalte 0), −/+ Buttons und Wert in der Mitte (Spalte 4).
     * Klick auf den Wert = Chat-Eingabe; Rechtsklick = {@code disable} (falls gesetzt).
     */
    private void stepper(Menu menu, int rowStart, Material icon, String label, String value, long[] steps,
                         LongConsumer change, Consumer<Player> prompt, Runnable disable) {
        menu.set(rowStart, Menu.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text("» " + label, NamedTextColor.AQUA, TextDecoration.BOLD)), p -> {});
        for (int i = 0; i < steps.length; i++) {
            long step = steps[i];
            menu.set(rowStart + 3 - i, Menu.icon(Material.RED_STAINED_GLASS_PANE, Component.text("−" + step, NamedTextColor.RED)),
                    p -> change.accept(-step));
            menu.set(rowStart + 5 + i, Menu.icon(Material.LIME_STAINED_GLASS_PANE, Component.text("+" + step, NamedTextColor.GREEN)),
                    p -> change.accept(step));
        }
        List<Component> lore = disable == null
                ? List.of(hint("Klick", "im Chat eingeben"))
                : List.of(hint("Klick", "im Chat eingeben"), hint("Rechtsklick", "ausschalten"));
        menu.setClick(rowStart + 4, Menu.icon(icon, Component.text(label + ": " + value, NamedTextColor.WHITE, TextDecoration.BOLD),
                lore.toArray(Component[]::new)), (p, click) -> {
            if (click.isRightClick() && disable != null) disable.run();
            else prompt.accept(p);
        });
    }

    private String priceText(long price) {
        return price < 0 ? "aus" : plugin.bank().format(price);
    }

    // ------------------------------------------------------------
    // Chat-Eingaben
    // ------------------------------------------------------------

    private void promptNumber(Player player, Draft d, String messageKey, LongConsumer apply) {
        player.closeInventory();
        player.sendMessage(msg(messageKey, player));
        plugin.chatPrompt().ask(player, input -> {
            try {
                apply.accept(Long.parseLong(input.replace(".", "").trim()));
            } catch (NumberFormatException e) {
                player.sendMessage(msg("creator-invalid-number", player));
            }
            open(player, d);
        }, () -> open(player, d));
    }

    private void promptText(Player player, Draft d) {
        player.closeInventory();
        player.sendMessage(msg("creator-prompt-label", player));
        plugin.chatPrompt().ask(player, input -> {
            d.label = input.length() > 15 ? input.substring(0, 15) : input;
            open(player, d);
        }, () -> open(player, d));
    }

    // ------------------------------------------------------------
    // Erstellen
    // ------------------------------------------------------------

    private void create(Player player, Draft d) {
        player.closeInventory();
        if (!d.fee && d.item == null) {
            player.sendMessage(msg("no-item", player));
            return;
        }
        Block signBlock = d.target.getRelative(d.face);
        if (!signBlock.isEmpty() || (d.container != null && !ChestShopManager.isShopContainer(d.container))) {
            player.sendMessage(msg("creator-sign-blocked", player));
            return;
        }
        ItemStack signItem = player.getGameMode() == GameMode.CREATIVE ? null : findSign(player);
        if (player.getGameMode() != GameMode.CREATIVE && signItem == null) {
            player.sendMessage(msg("creator-no-sign", player));
            return;
        }

        Material standing = signItem == null ? Material.OAK_SIGN : signItem.getType();
        BlockState replaced = signBlock.getState();
        placeSign(signBlock, standing, d.face, player);
        // wie normales Platzieren: Schutz-Plugins (Spawn-Schutz, WorldGuard, …) dürfen ablehnen
        BlockPlaceEvent place = new BlockPlaceEvent(signBlock, replaced, d.target,
                signItem != null ? signItem : new ItemStack(standing), player, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(place);
        if (place.isCancelled() || !place.canBuild()) {
            replaced.update(true, false);
            player.sendMessage(msg("creator-place-denied", player));
            return;
        }
        boolean ok = shops.create(player, signBlock, d.fee ? null : d.container, d.fee ? null : d.item, d.amount,
                d.buy, d.fee ? -1 : d.sell, d.admin, d.label, lines -> {
                    Sign sign = (Sign) signBlock.getState();
                    for (int i = 0; i < 4; i++) sign.getSide(Side.FRONT).line(i, lines.get(i));
                    // gewachst: das Schild lässt sich nicht mehr bearbeiten
                    sign.setWaxed(true);
                    sign.update();
                });
        if (!ok) {
            signBlock.setType(Material.AIR);
            return;
        }
        if (signItem != null) signItem.subtract();
    }

    /** Erstes normales Schild im Inventar (keine Hängeschilder). */
    private static ItemStack findSign(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType().name().endsWith("_SIGN") && !item.getType().name().contains("HANGING")) return item;
        }
        return null;
    }

    /** Wandschild an der Seite bzw. Standschild obendrauf, zum Spieler gedreht. */
    private static void placeSign(Block block, Material standing, BlockFace face, Player player) {
        if (face == BlockFace.UP) {
            block.setType(standing, false);
            if (block.getBlockData() instanceof Rotatable rot) {
                rot.setRotation(player.getFacing().getOppositeFace());
                block.setBlockData(rot, false);
            }
        } else {
            Material wall = Material.matchMaterial(standing.name().replace("_SIGN", "_WALL_SIGN"));
            block.setType(wall != null ? wall : Material.OAK_WALL_SIGN, false);
            if (block.getBlockData() instanceof WallSign data) {
                data.setFacing(face);
                block.setBlockData(data, false);
            }
        }
    }

    // ------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------

    private Component msg(String key, Player player) {
        return plugin.text().render(plugin.getConfig().getString("chestshop.messages." + key), player);
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    private static Component hint(String click, String action) {
        return Component.text(click, NamedTextColor.YELLOW).append(Component.text(" » " + action, NamedTextColor.GRAY));
    }
}
