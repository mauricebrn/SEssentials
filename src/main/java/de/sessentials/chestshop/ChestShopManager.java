package de.sessentials.chestshop;

import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chestshops: Schild an einer Kiste/einem Fass. Rechtsklick aufs Schild kauft, Linksklick verkauft;
 * bezahlt wird über die Bank zwischen Kunde und Besitzer. Admin-Shops haben unbegrenzten Bestand.
 * Kiste und Schild sind geschützt (nur Besitzer/Admins), Trichter ziehen nichts heraus.
 */
public final class ChestShopManager implements Listener {

    private static final String PERM_CREATE = "sess.chestshop.create";
    private static final String PERM_ADMIN = "sess.chestshop.admin";
    private static final int MAX_AMOUNT = 36 * 64;
    private static final Pattern BUY = Pattern.compile("(?i)\\b[KB]\\s*(\\d+)");
    private static final Pattern SELL = Pattern.compile("(?i)\\b[VS]\\s*(\\d+)");

    /**
     * @param buy  Preis, zu dem Spieler kaufen; -1 = Shop verkauft nicht
     * @param sell Preis, zu dem Spieler verkaufen; -1 = Shop kauft nicht an
     */
    record Shop(Location sign, Location container, UUID owner, String ownerName, ItemStack item, int amount,
                long buy, long sell, boolean admin) {}

    private final SEssentials plugin;
    private final File file;
    private final AsyncFileWriter writer;
    private final Map<String, Shop> bySign = new HashMap<>();
    private final Map<String, Shop> byContainer = new HashMap<>();

    public ChestShopManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shops.yml");
        this.writer = new AsyncFileWriter("Shops", plugin.getLogger());
        load();
    }

    public void shutdown() {
        writer.shutdown();
    }

    // ------------------------------------------------------------
    // Erstellen
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        Shop existing = bySign.get(key(event.getBlock().getLocation()));
        if (existing != null) {
            // Shop-Schilder werden nicht umgeschrieben – abbauen und neu erstellen
            event.setCancelled(true);
            return;
        }
        String header = plain(event.line(0)).trim();
        boolean admin = header.equalsIgnoreCase("[adminshop]");
        if (!admin && !header.equalsIgnoreCase("[shop]")) return;

        if (!player.hasPermission(admin ? PERM_ADMIN : PERM_CREATE)) {
            reject(event, player, "no-permission");
            return;
        }
        Block containerBlock = attachedContainer(event.getBlock());
        if (containerBlock == null) {
            reject(event, player, "no-container");
            return;
        }
        Shop other = shopForContainer(containerBlock);
        if (other != null && !other.owner().equals(player.getUniqueId())) {
            reject(event, player, "container-taken", TextService.placeholder("owner", other.ownerName()));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(plain(event.line(1)).trim());
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount < 1 || amount > MAX_AMOUNT) {
            reject(event, player, "invalid-amount");
            return;
        }

        String priceLine = plain(event.line(2));
        long buy = price(BUY, priceLine), sell = price(SELL, priceLine);
        if (buy < 0 && sell < 0) {
            reject(event, player, "invalid-price");
            return;
        }

        ItemStack item = shopItem(plain(event.line(3)).trim(), (Container) containerBlock.getState(), player);
        if (item == null) {
            reject(event, player, "no-item");
            return;
        }

        Shop shop = new Shop(event.getBlock().getLocation(), containerBlock.getLocation(),
                admin ? new UUID(0, 0) : player.getUniqueId(), admin ? "Server" : player.getName(),
                item, amount, buy, sell, admin);
        List<Component> lines = signLines(shop);
        for (int i = 0; i < 4; i++) event.line(i, lines.get(i));
        add(shop);
        save();
        player.sendMessage(msg(admin ? "created-admin" : "created", player, tags(shop)));
    }

    private void reject(SignChangeEvent event, Player player, String key, TagResolver... extra) {
        event.line(0, Component.text("[Shop]", NamedTextColor.RED));
        player.sendMessage(msg(key, player, extra));
    }

    /** Container, an dem das Schild hängt (Wandschild) bzw. auf dem es steht. */
    private static Block attachedContainer(Block sign) {
        Block target = sign.getBlockData() instanceof WallSign wall
                ? sign.getRelative(wall.getFacing().getOppositeFace())
                : sign.getRelative(BlockFace.DOWN);
        return isShopContainer(target) ? target : null;
    }

    /** Nur über den Typ – kein Block-State, damit Explosionen und Trichter günstig bleiben. */
    private static boolean isShopContainer(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }

    private static long price(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Item aus Zeile 4, sonst das erste Item in der Kiste, sonst das in der Hand. */
    private static ItemStack shopItem(String name, Container container, Player player) {
        if (!name.isEmpty()) {
            Material m = Material.matchMaterial(name.replace(' ', '_'));
            return m != null && m.isItem() ? new ItemStack(m) : null;
        }
        for (ItemStack item : container.getInventory().getContents()) {
            if (item != null && !item.isEmpty()) return item.asOne();
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        return hand.isEmpty() ? null : hand.asOne();
    }

    private List<Component> signLines(Shop shop) {
        List<String> prices = new ArrayList<>();
        if (shop.buy() >= 0) prices.add("K " + shop.buy());
        if (shop.sell() >= 0) prices.add("V " + shop.sell());
        return List.of(
                Component.text(shop.admin() ? "[AdminShop]" : "[Shop]", shop.admin() ? NamedTextColor.DARK_RED : NamedTextColor.DARK_BLUE, TextDecoration.BOLD),
                Component.text(shop.ownerName(), NamedTextColor.BLACK),
                Component.text(String.join(" | ", prices), NamedTextColor.DARK_GREEN),
                Component.text(shop.amount() + "x ", NamedTextColor.BLACK).append(shop.item().effectiveName().color(NamedTextColor.BLACK)));
    }

    // ------------------------------------------------------------
    // Handeln
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        Shop shop = bySign.get(key(block.getLocation()));
        if (shop != null) {
            boolean ownerOrAdmin = isOwner(player, shop) || player.hasPermission(PERM_ADMIN);
            // Besitzer: Sneak + Linksklick baut ab
            if (ownerOrAdmin && player.isSneaking() && event.getAction() == Action.LEFT_CLICK_BLOCK) return;
            event.setCancelled(true);
            if (isOwner(player, shop)) {
                player.sendMessage(msg("info", player, tags(shop)));
                player.sendMessage(msg("owner-hint", player));
            } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                buy(player, shop);
            } else if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
                sell(player, shop);
            }
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && isShopContainer(block)) {
            Shop owned = shopForContainer(block);
            if (owned != null && !isOwner(player, owned) && !player.hasPermission(PERM_ADMIN)) {
                event.setCancelled(true);
                player.sendMessage(msg("protected", player, tags(owned)));
            }
        }
    }

    private void buy(Player player, Shop shop) {
        TagResolver tags = tags(shop);
        if (shop.buy() < 0) {
            player.sendMessage(msg("no-buy", player, tags));
            return;
        }
        Inventory stock = shop.admin() ? null : inventory(shop);
        if (!shop.admin() && (stock == null || count(stock.getStorageContents(), shop.item()) < shop.amount())) {
            player.sendMessage(msg("out-of-stock", player, tags));
            return;
        }
        if (!fits(player.getInventory().getStorageContents(), shop.item(), shop.amount())) {
            player.sendMessage(msg("inventory-full", player, tags));
            return;
        }
        boolean paid = shop.admin()
                ? plugin.bank().withdraw(player.getUniqueId(), shop.buy())
                : plugin.bank().transfer(player.getUniqueId(), shop.owner(), shop.buy());
        if (!paid) {
            player.sendMessage(msg("not-enough-money", player, tags, money(shop.buy())));
            return;
        }
        if (stock != null) remove(stock, shop.item(), shop.amount());
        give(player, shop.item(), shop.amount());
        player.sendMessage(msg("bought", player, tags, money(shop.buy())));
        notifyOwner(shop, "owner-bought", player);
    }

    private void sell(Player player, Shop shop) {
        TagResolver tags = tags(shop);
        if (shop.sell() < 0) {
            player.sendMessage(msg("no-sell", player, tags));
            return;
        }
        if (count(player.getInventory().getStorageContents(), shop.item()) < shop.amount()) {
            player.sendMessage(msg("not-enough-items", player, tags));
            return;
        }
        Inventory stock = shop.admin() ? null : inventory(shop);
        if (!shop.admin() && (stock == null || !fits(stock.getStorageContents(), shop.item(), shop.amount()))) {
            player.sendMessage(msg("shop-full", player, tags));
            return;
        }
        if (shop.admin()) {
            plugin.bank().deposit(player.getUniqueId(), shop.sell());
        } else if (!plugin.bank().transfer(shop.owner(), player.getUniqueId(), shop.sell())) {
            player.sendMessage(msg("owner-not-enough-money", player, tags, money(shop.sell())));
            return;
        }
        remove(player.getInventory(), shop.item(), shop.amount());
        if (stock != null) stock.addItem(stacks(shop.item(), shop.amount()));
        player.sendMessage(msg("sold", player, tags, money(shop.sell())));
        notifyOwner(shop, "owner-sold", player);
    }

    private void notifyOwner(Shop shop, String key, Player customer) {
        if (shop.admin()) return;
        Player owner = Bukkit.getPlayer(shop.owner());
        if (owner == null) return;
        long price = key.equals("owner-bought") ? shop.buy() : shop.sell();
        owner.sendMessage(msg(key, owner, tags(shop), money(price), TextService.placeholder("customer", customer.getName())));
    }

    private Inventory inventory(Shop shop) {
        Block block = shop.container().getBlock();
        return block.getState(false) instanceof Container container ? container.getInventory() : null;
    }

    // ------------------------------------------------------------
    // Item-Hilfen (nur exakt gleiche Items zählen)
    // ------------------------------------------------------------

    private static int count(ItemStack[] contents, ItemStack template) {
        int n = 0;
        for (ItemStack item : contents) if (item != null && item.isSimilar(template)) n += item.getAmount();
        return n;
    }

    private static boolean fits(ItemStack[] contents, ItemStack template, int amount) {
        int max = template.getMaxStackSize(), free = 0;
        for (ItemStack item : contents) {
            if (item == null || item.isEmpty()) free += max;
            else if (item.isSimilar(template)) free += Math.max(0, max - item.getAmount());
            if (free >= amount) return true;
        }
        return false;
    }

    private static void remove(Inventory inventory, ItemStack template, int amount) {
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !item.isSimilar(template)) continue;
            int take = Math.min(amount, item.getAmount());
            amount -= take;
            if (take == item.getAmount()) contents[i] = null;
            else item.setAmount(item.getAmount() - take);
        }
        inventory.setStorageContents(contents);
    }

    private static ItemStack[] stacks(ItemStack template, int amount) {
        List<ItemStack> list = new ArrayList<>();
        int max = template.getMaxStackSize();
        while (amount > 0) {
            int n = Math.min(max, amount);
            list.add(template.asQuantity(n));
            amount -= n;
        }
        return list.toArray(ItemStack[]::new);
    }

    private static void give(Player player, ItemStack template, int amount) {
        player.getInventory().addItem(stacks(template, amount)).values()
                .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
    }

    // ------------------------------------------------------------
    // Schutz
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Shop shop = bySign.get(key(block.getLocation()));
        if (shop == null && isShopContainer(block)) shop = shopForContainer(block);
        if (shop == null) return;
        Player player = event.getPlayer();
        if (!isOwner(player, shop) && !player.hasPermission(PERM_ADMIN)) {
            event.setCancelled(true);
            player.sendMessage(msg("protected", player, tags(shop)));
            return;
        }
        // Kiste oder Schild abgebaut: alle Shops dieses Containers entfernen
        for (Shop s : List.copyOf(bySign.values())) {
            if (s == shop || (isShopContainer(block) && sameContainer(s, block))) remove(s);
        }
        save();
        player.sendMessage(msg("removed", player));
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        // aus Shop-Kisten darf nichts abgezogen werden (Auffüllen per Trichter ist erlaubt)
        if (isShopInventory(event.getSource())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isShopBlock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isShopBlock);
    }

    private boolean isShopBlock(Block block) {
        return bySign.containsKey(key(block.getLocation())) || (isShopContainer(block) && shopForContainer(block) != null);
    }

    /** Läuft bei jedem Trichter-Transfer: schneller Ausstieg ohne Shops bzw. ohne Shop an dieser Stelle. */
    private boolean isShopInventory(Inventory inventory) {
        if (byContainer.isEmpty()) return false;
        Location loc = inventory.getLocation();
        if (loc == null) return false;
        if (byContainer.containsKey(key(loc))) return true;
        // Doppelkisten: Shop kann an der anderen Hälfte hängen
        Block block = loc.getBlock();
        return block.getType() != Material.BARREL && isShopContainer(block) && shopForContainer(block) != null;
    }

    private static boolean isOwner(Player player, Shop shop) {
        return !shop.admin() && shop.owner().equals(player.getUniqueId());
    }

    /** Shop dieses Containers – bei Doppelkisten auch über die andere Hälfte. */
    private Shop shopForContainer(Block block) {
        for (Location half : halves(block)) {
            Shop shop = byContainer.get(key(half));
            if (shop != null) return shop;
        }
        return null;
    }

    private boolean sameContainer(Shop shop, Block block) {
        String shopKey = key(shop.container());
        return halves(block).stream().anyMatch(l -> key(l).equals(shopKey));
    }

    private static List<Location> halves(Block block) {
        if (block.getState(false) instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder(false);
            if (holder instanceof DoubleChest dc && dc.getLeftSide(false) instanceof Chest l && dc.getRightSide(false) instanceof Chest r) {
                return List.of(l.getLocation(), r.getLocation());
            }
        }
        return List.of(block.getLocation());
    }

    // ------------------------------------------------------------
    // Nachrichten
    // ------------------------------------------------------------

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("chestshop.messages." + key), player, extra);
    }

    private TagResolver tags(Shop shop) {
        return TagResolver.resolver(
                TextService.placeholder("owner", shop.ownerName()),
                TextService.placeholder("amount", String.valueOf(shop.amount())),
                Placeholder.component("item", shop.item().effectiveName()),
                TextService.placeholder("buy", shop.buy() < 0 ? "–" : plugin.bank().format(shop.buy())),
                TextService.placeholder("sell", shop.sell() < 0 ? "–" : plugin.bank().format(shop.sell())),
                TextService.placeholder("stock", shop.admin() ? "∞" : stock(shop)));
    }

    private String stock(Shop shop) {
        Inventory inv = inventory(shop);
        return inv == null ? "0" : String.valueOf(count(inv.getStorageContents(), shop.item()));
    }

    private TagResolver money(long amount) {
        return TextService.placeholder("price", plugin.bank().format(amount));
    }

    private static String plain(Component line) {
        return line == null ? "" : PlainTextComponentSerializer.plainText().serialize(line);
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private static String key(Location loc) {
        return loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void add(Shop shop) {
        bySign.put(key(shop.sign()), shop);
        byContainer.put(key(shop.container()), shop);
    }

    private void remove(Shop shop) {
        bySign.remove(key(shop.sign()));
        byContainer.remove(key(shop.container()), shop);
        // weitere Schilder am selben Container bleiben im Index
        bySign.values().stream().filter(s -> key(s.container()).equals(key(shop.container())))
                .findFirst().ifPresent(s -> byContainer.put(key(s.container()), s));
    }

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("shops");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            Location sign = s.getLocation("sign"), container = s.getLocation("container");
            ItemStack item = s.getItemStack("item");
            if (sign == null || container == null || item == null) {
                plugin.getLogger().warning("Chestshop " + id + " übersprungen (Welt nicht geladen oder Daten fehlen).");
                continue;
            }
            add(new Shop(sign, container, UUID.fromString(s.getString("owner")), s.getString("owner-name", "?"),
                    item, s.getInt("amount"), s.getLong("buy", -1), s.getLong("sell", -1), s.getBoolean("admin")));
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        int i = 0;
        for (Shop shop : bySign.values()) {
            String path = "shops." + (i++) + ".";
            yml.set(path + "sign", shop.sign());
            yml.set(path + "container", shop.container());
            yml.set(path + "owner", shop.owner().toString());
            yml.set(path + "owner-name", shop.ownerName());
            yml.set(path + "item", shop.item());
            yml.set(path + "amount", shop.amount());
            yml.set(path + "buy", shop.buy());
            yml.set(path + "sell", shop.sell());
            yml.set(path + "admin", shop.admin());
        }
        writer.write(file, yml.saveToString());
    }

    /** Zahl der Shops – für die Admin-Kachel. */
    public int count() {
        return bySign.size();
    }
}
