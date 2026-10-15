package de.sessentials.cost;

import de.sessentials.bank.BankExchange;
import de.sessentials.bank.BankManager;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/**
 * Kosten eines Befehls aus der Config: vom Bankkonto ({@code type: money}) oder in Items
 * ({@code type: item}, z.B. 5 Diamanten). Bei Items zählen nur unveränderte (keine umbenannten oder verzauberten).
 * Alles Bezahlte landet im Server-Topf ({@link BankManager#pot()}), Items mit ihrem Bank-Kurs.
 */
public final class Cost {

    private final boolean money;
    private final Material item;
    private final long amount;
    /** Wert für den Server-Topf; bei Items nach Bank-Kurs (0, wenn das Item keinen Kurs hat). */
    private final long potValue;
    private final BankManager bank;

    private Cost(boolean money, Material item, long amount, long potValue, BankManager bank) {
        this.money = money;
        this.item = item;
        this.amount = amount;
        this.potValue = potValue;
        this.bank = bank;
    }

    /**
     * Liest {@code <path>.type}, {@code .item} und {@code .amount}. Ein ungültiges Item fällt auf DIAMOND zurück,
     * damit ein Tippfehler nie zu kostenlosen Befehlen führt. Ältere Configs ohne {@code type}, aber mit
     * {@code item} bleiben bei Item-Kosten.
     */
    public static Cost fromConfig(ConfigurationSection cfg, String path, BankManager bank, BankExchange exchange, Logger log) {
        String type = cfg.isSet(path + ".type") ? cfg.getString(path + ".type")
                : cfg.isSet(path + ".item") ? "item" : cfg.getString(path + ".type");
        long amount = Math.max(0, cfg.getLong(path + ".amount"));
        if ("money".equalsIgnoreCase(type)) return new Cost(true, null, amount, amount, bank);

        String itemName = cfg.getString(path + ".item");
        Material item = itemName == null ? null : Material.matchMaterial(itemName);
        if (item == null || !item.isItem()) {
            log.warning(path + ".item '" + itemName + "' ist kein Item – nutze DIAMOND.");
            item = Material.DIAMOND;
        }
        int count = (int) Math.min(amount, Integer.MAX_VALUE);
        return new Cost(false, item, count, exchange.worth(item) * count, bank);
    }

    public boolean isFree() {
        return amount == 0;
    }

    /**
     * {@code <price>} (z.B. "5x Diamant" oder "500 Taler"), dazu {@code <amount>} und {@code <item>}
     * (Item-Name in der Sprache des Spielers bzw. Währungsname).
     */
    public TagResolver tags() {
        Component name = money ? Component.text(bank.currencyName(amount)) : Component.translatable(item.translationKey());
        Component price = money ? Component.text(bank.format(amount)) : Component.text(amount + "x ").append(name);
        return TagResolver.resolver(
                TextService.placeholder("amount", String.valueOf(amount)),
                Placeholder.component("item", name),
                Placeholder.component("price", price));
    }

    public boolean canAfford(Player player) {
        if (isFree()) return true;
        return money ? bank.balance(player.getUniqueId()) >= amount
                : player.getInventory().containsAtLeast(new ItemStack(item), (int) amount);
    }

    /** Zieht die Kosten ab. {@code false}, wenn der Spieler nicht genug hat (dann wird nichts abgezogen). */
    public boolean take(Player player) {
        if (isFree()) return true;
        if (money) return bank.payServer(player.getUniqueId(), amount);
        if (!canAfford(player)) return false;
        player.getInventory().removeItem(new ItemStack(item, (int) amount));
        bank.addToPot(potValue);
        return true;
    }

    /** Gibt abgezogene Kosten zurück; Items, die nicht ins Inventar passen, fallen auf den Boden. */
    public void refund(Player player) {
        if (isFree()) return;
        bank.addToPot(-potValue);
        if (money) {
            bank.deposit(player.getUniqueId(), amount);
            return;
        }
        player.getInventory().addItem(new ItemStack(item, (int) amount)).values()
                .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
    }
}
