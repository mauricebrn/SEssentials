package de.sessentials.bank;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Umrechnung zwischen Items und Kontostand nach den Werten in der Config.
 * Nur unveränderte Items zählen (keine umbenannten oder verzauberten).
 */
public final class BankExchange {

    /** Blöcke zu Barren/Items: 1 Block = 9 Items. */
    private static final Map<Material, Material> BLOCKS = Map.ofEntries(
            Map.entry(Material.DIAMOND, Material.DIAMOND_BLOCK),
            Map.entry(Material.EMERALD, Material.EMERALD_BLOCK),
            Map.entry(Material.IRON_INGOT, Material.IRON_BLOCK),
            Map.entry(Material.GOLD_INGOT, Material.GOLD_BLOCK),
            Map.entry(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK),
            Map.entry(Material.COPPER_INGOT, Material.COPPER_BLOCK),
            Map.entry(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK),
            Map.entry(Material.REDSTONE, Material.REDSTONE_BLOCK),
            Map.entry(Material.COAL, Material.COAL_BLOCK),
            Map.entry(Material.RAW_IRON, Material.RAW_IRON_BLOCK),
            Map.entry(Material.RAW_GOLD, Material.RAW_GOLD_BLOCK),
            Map.entry(Material.RAW_COPPER, Material.RAW_COPPER_BLOCK));
    private static final int ITEMS_PER_BLOCK = 9;

    public enum WithdrawMode {
        /** In allen eingestellten Materialien, größte Werte zuerst. */
        ORIGINAL,
        /** Nur im Basis-Material (erstes Material der Liste). */
        BASE
    }

    private final Material base;
    /** Eingestellte Materialien mit Wert pro Item (ohne Blöcke), unabhängig von multiple-materials. */
    private final Map<Material, Long> rates;
    /** Material → Wert pro Item, inkl. Blöcke falls erlaubt. */
    private final Map<Material, Long> depositValues;
    /** Stückelung für Auszahlungen, größte zuerst. */
    private final List<Map.Entry<Material, Long>> payout;
    private final boolean withdrawEnabled;
    private final WithdrawMode withdrawMode;

    private BankExchange(Material base, Map<Material, Long> rates, Map<Material, Long> depositValues,
                         List<Map.Entry<Material, Long>> payout, boolean withdrawEnabled, WithdrawMode withdrawMode) {
        this.base = base;
        this.rates = rates;
        this.depositValues = depositValues;
        this.payout = payout;
        this.withdrawEnabled = withdrawEnabled;
        this.withdrawMode = withdrawMode;
    }

    public static BankExchange fromConfig(FileConfiguration cfg, Logger log) {
        Map<Material, Long> materials = new LinkedHashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("bank.materials");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                long value = sec.getLong(key);
                if (m == null || !m.isItem() || value <= 0) {
                    log.warning("bank.materials." + key + " ist kein Item oder hat keinen Wert > 0 – übersprungen.");
                    continue;
                }
                materials.put(m, value);
            }
        }
        if (materials.isEmpty()) {
            log.warning("bank.materials ist leer – nutze DIAMOND mit Wert 100.");
            materials.put(Material.DIAMOND, 100L);
        }
        Material base = materials.keySet().iterator().next();
        Map<Material, Long> rates = Map.copyOf(materials);
        if (!cfg.getBoolean("bank.multiple-materials")) materials.keySet().retainAll(List.of(base));

        boolean allowBlocks = cfg.getBoolean("bank.allow-blocks");
        Map<Material, Long> values = new LinkedHashMap<>(materials);
        if (allowBlocks) {
            materials.forEach((m, v) -> {
                Material block = BLOCKS.get(m);
                if (block != null) values.put(block, v * ITEMS_PER_BLOCK);
            });
        }

        String modeName = cfg.getString("bank.withdraw.mode", "original");
        WithdrawMode mode = "base".equalsIgnoreCase(modeName) ? WithdrawMode.BASE : WithdrawMode.ORIGINAL;
        List<Map.Entry<Material, Long>> payout = values.entrySet().stream()
                .filter(e -> mode == WithdrawMode.ORIGINAL || e.getKey() == base || e.getKey() == BLOCKS.get(base))
                .sorted(Map.Entry.<Material, Long>comparingByValue(Comparator.reverseOrder()))
                .map(e -> Map.entry(e.getKey(), e.getValue()))
                .toList();

        return new BankExchange(base, rates, Map.copyOf(values), payout, cfg.getBoolean("bank.withdraw.enabled"), mode);
    }

    public Material base() {
        return base;
    }

    /** Einzahlbare Materialien mit Wert pro Item, für die Anzeige der Kurse. */
    public Map<Material, Long> values() {
        return depositValues;
    }

    /**
     * Wert eines Items nach den Bank-Kursen (Blöcke = 9 Items), unabhängig davon, ob es eingezahlt werden kann –
     * für Auswertungen wie den Item-Tracker. 0 = kein Kurs.
     */
    public long worth(Material material) {
        Long rate = rates.get(material);
        if (rate != null) return rate;
        for (Map.Entry<Material, Material> e : BLOCKS.entrySet()) {
            if (e.getValue() == material && rates.containsKey(e.getKey())) return rates.get(e.getKey()) * ITEMS_PER_BLOCK;
        }
        return 0;
    }

    public boolean withdrawEnabled() {
        return withdrawEnabled;
    }

    /** Kleinster auszahlbarer Betrag. */
    public long smallestUnit() {
        return payout.isEmpty() ? 0 : payout.getLast().getValue();
    }

    private Long valueOf(ItemStack item) {
        if (item == null || item.isEmpty() || item.hasItemMeta()) return null;
        return depositValues.get(item.getType());
    }

    /** Wert aller einzahlbaren Items im Inventar (ohne sie zu entfernen). */
    public long inventoryValue(Player player) {
        long total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            Long v = valueOf(item);
            if (v != null) total += v * item.getAmount();
        }
        return total;
    }

    /** Entfernt alle einzahlbaren Items aus dem Inventar und liefert ihren Wert. */
    public long takeAll(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        long total = 0;
        for (int i = 0; i < contents.length; i++) {
            Long v = valueOf(contents[i]);
            if (v == null) continue;
            total += v * contents[i].getAmount();
            contents[i] = null;
        }
        inv.setStorageContents(contents);
        return total;
    }

    /** Entfernt den Stack in der Haupthand, falls einzahlbar, und liefert seinen Wert (0 = nicht einzahlbar). */
    public long takeHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        Long v = valueOf(hand);
        if (v == null) return 0;
        long total = v * hand.getAmount();
        player.getInventory().setItemInMainHand(null);
        return total;
    }

    /**
     * Stückelt {@code amount} in Items (größte Werte zuerst). Liefert die Items und den tatsächlich
     * ausgezahlten Betrag, der wegen der kleinsten Einheit niedriger als {@code amount} sein kann.
     */
    public Payout split(long amount) {
        List<ItemStack> items = new ArrayList<>();
        long rest = amount;
        for (Map.Entry<Material, Long> e : payout) {
            long count = rest / e.getValue();
            if (count <= 0) continue;
            rest -= count * e.getValue();
            int max = e.getKey().getMaxStackSize();
            while (count > 0) {
                int stack = (int) Math.min(max, count);
                items.add(new ItemStack(e.getKey(), stack));
                count -= stack;
            }
        }
        return new Payout(items, amount - rest);
    }

    public record Payout(List<ItemStack> items, long value) {}

    /** Gibt Items; was nicht passt, fällt vor die Füße. */
    public static void give(Player player, List<ItemStack> items) {
        player.getInventory().addItem(items.toArray(ItemStack[]::new)).values()
                .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
    }
}
