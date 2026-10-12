package de.sessentials.cost;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/**
 * Item-Kosten aus der Config ({@code item} + {@code amount}), z.B. 5 Diamanten.
 * Nur unveränderte Items zählen (keine umbenannten oder verzauberten).
 */
public final class ItemCost {

    private final Material item;
    private final int amount;

    private ItemCost(Material item, int amount) {
        this.item = item;
        this.amount = amount;
    }

    /**
     * Liest {@code <path>.item} und {@code <path>.amount}. Ein ungültiges Item fällt auf DIAMOND zurück,
     * damit ein Tippfehler nie zu kostenlosen Befehlen führt.
     */
    public static ItemCost fromConfig(ConfigurationSection cfg, String path, Logger log) {
        String itemName = cfg.getString(path + ".item");
        Material item = itemName == null ? null : Material.matchMaterial(itemName);
        if (item == null || !item.isItem()) {
            log.warning(path + ".item '" + itemName + "' ist kein Item – nutze DIAMOND.");
            item = Material.DIAMOND;
        }
        return new ItemCost(item, Math.max(0, cfg.getInt(path + ".amount")));
    }

    public boolean isFree() {
        return amount == 0;
    }

    /** Platzhalter {@code <amount>} und {@code <item>} (Item-Name in der Sprache des Spielers). */
    public TagResolver tags() {
        return TagResolver.resolver(
                Placeholder.unparsed("amount", String.valueOf(amount)),
                Placeholder.component("item", Component.translatable(item.translationKey())));
    }

    public boolean canAfford(Player player) {
        return isFree() || player.getInventory().containsAtLeast(new ItemStack(item), amount);
    }

    /** Zieht die Kosten ab. {@code false}, wenn der Spieler nicht genug hat (dann wird nichts abgezogen). */
    public boolean take(Player player) {
        if (!canAfford(player)) return false;
        if (!isFree()) player.getInventory().removeItem(new ItemStack(item, amount));
        return true;
    }

    /** Gibt abgezogene Kosten zurück; was nicht ins Inventar passt, fällt auf den Boden. */
    public void refund(Player player) {
        if (isFree()) return;
        player.getInventory().addItem(new ItemStack(item, amount)).values()
                .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
    }
}
