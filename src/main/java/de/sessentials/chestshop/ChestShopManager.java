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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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

    /** Zeile 4 bei Gebühren-Shops: Bezahlen ohne Item, z.B. für die Nutzung einer Farm. */
    private static final Set<String> FEE_WORDS = Set.of("gebühr", "gebuehr", "eintritt", "nutzung", "fee", "-");
    /** Schutz vor doppelt abgebuchten Gebühren durch Doppelklick. */
    private static final long FEE_COOLDOWN_MILLIS = 2000;

    /**
     * @param container Kiste/Fass; {@code null} bei Gebühren-Shops
     * @param item      gehandeltes Item; {@code null} = Gebühren-Shop (nur bezahlen, kein Item)
     * @param buy       Preis, zu dem Spieler kaufen bzw. die Gebühr; -1 = Shop verkauft nicht
     * @param sell      Preis, zu dem Spieler verkaufen; -1 = Shop kauft nicht an
     * @param label     Beschreibung eines Gebühren-Shops (z.B. „Farm“), sonst leer
     */
    record Shop(Location sign, Location container, UUID owner, String ownerName, ItemStack item, int amount,
                long buy, long sell, boolean admin, String label) {

        boolean isFee() {
            return item == null;
        }
    }

    private final SEssentials plugin;
    private final File file;
    private final AsyncFileWriter writer;
    private final Map<String, Shop> bySign = new HashMap<>();
    private final Map<String, Shop> byContainer = new HashMap<>();
    private final Map<String, Long> lastFee = new HashMap<>();

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
        if (bySign.containsKey(key(event.getBlock().getLocation()))) {
            // Shop-Schilder werden nicht umgeschrieben – abbauen und neu erstellen
            event.setCancelled(true);
            return;
        }
        String header = plain(event.line(0)).trim();
        boolean admin = header.equalsIgnoreCase("[adminshop]");
        if (!admin && !header.equalsIgnoreCase("[shop]")) return;

        String itemLine = plain(event.line(3)).trim();
        if (FEE_WORDS.contains(itemLine.toLowerCase(Locale.ROOT))) {
            long price = price(BUY, plain(event.line(2)));
            if (price < 1) {
                reject(event, player, "invalid-fee-price");
                return;
            }
            createFromSign(event, player, null, null, 1, price, -1, admin, plain(event.line(1)).trim());
            return;
        }

        Block containerBlock = attachedContainer(event.getBlock());
        if (containerBlock == null) {
            reject(event, player, "no-container");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(plain(event.line(1)).trim());
        } catch (NumberFormatException e) {
            amount = 0;
        }
        String priceLine = plain(event.line(2));
        long buy = price(BUY, priceLine), sell = price(SELL, priceLine);
        ItemStack item = shopItem(itemLine, (Container) containerBlock.getState(), player);
        if (item == null) {
            reject(event, player, "no-item");
            return;
        }
        createFromSign(event, player, containerBlock, item, amount, buy, sell, admin, "");
    }

    private void createFromSign(SignChangeEvent event, Player player, Block container, ItemStack item, int amount,
                                long buy, long sell, boolean admin, String label) {
        boolean ok = create(player, event.getBlock(), container, item, amount, buy, sell, admin, label, lines -> {
            for (int i = 0; i < 4; i++) event.line(i, lines.get(i));
        });
        if (!ok) event.line(0, Component.text("[Shop]", NamedTextColor.RED));
    }

    private void reject(SignChangeEvent event, Player player, String key) {
        event.line(0, Component.text("[Shop]", NamedTextColor.RED));
        player.sendMessage(msg(key, player));
    }

    /**
     * Prüft und registriert einen Shop – gemeinsam für Schild-Eingabe und GUI-Creator.
     * Schreibt die Schildzeilen über {@code writeLines}; meldet Fehler und Erfolg selbst.
     *
     * @param container Kiste/Fass; {@code null} bei Gebühren-Shops ({@code item == null})
     * @return {@code true}, wenn der Shop angelegt wurde
     */
    boolean create(Player player, Block signBlock, Block container, ItemStack item, int amount, long buy, long sell,
                   boolean admin, String label, Consumer<List<Component>> writeLines) {
        if (!player.hasPermission(admin ? PERM_ADMIN : PERM_CREATE)) {
            player.sendMessage(msg("no-permission", player));
            return false;
        }
        boolean fee = item == null;
        if (fee && buy < 1) {
            player.sendMessage(msg("invalid-fee-price", player));
            return false;
        }
        if (!fee) {
            if (container == null || !isShopContainer(container)) {
                player.sendMessage(msg("no-container", player));
                return false;
            }
            Shop other = shopForContainer(container);
            if (other != null && !other.owner().equals(player.getUniqueId())) {
                player.sendMessage(msg("container-taken", player, TextService.placeholder("owner", other.ownerName())));
                return false;
            }
            if (amount < 1 || amount > MAX_AMOUNT) {
                player.sendMessage(msg("invalid-amount", player));
                return false;
            }
            if (buy < 0 && sell < 0) {
                player.sendMessage(msg("invalid-price", player));
                return false;
            }
        }
        Shop shop = new Shop(signBlock.getLocation(), fee ? null : container.getLocation(),
                admin ? new UUID(0, 0) : player.getUniqueId(), admin ? "Server" : player.getName(),
                fee ? null : item.asOne(), fee ? 1 : amount, buy, fee ? -1 : sell, admin,
                label.length() > 15 ? label.substring(0, 15) : label);
        writeLines.accept(signLines(shop));
        add(shop);
        save();
        player.sendMessage(fee ? msg("created-fee", player, tags(shop), money(buy))
                : msg(admin ? "created-admin" : "created", player, tags(shop)));
        return true;
    }

    /** Container, an dem das Schild hängt (Wandschild) bzw. auf dem es steht. */
    private static Block attachedContainer(Block sign) {
        Block target = supportOf(sign);
        return isShopContainer(target) ? target : null;
    }

    /** Nur über den Typ – kein Block-State, damit Explosionen und Trichter günstig bleiben. */
    static boolean isShopContainer(Block block) {
        Material type = block.getType();
        return type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL;
    }

    private static long price(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Item aus Zeile 4, sonst das erste Item in der Kiste, sonst das in der Hand. */
    static ItemStack shopItem(String name, Container container, Player player) {
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
        Component header = Component.text(shop.admin() ? "[AdminShop]" : "[Shop]",
                shop.admin() ? NamedTextColor.DARK_RED : NamedTextColor.DARK_BLUE, TextDecoration.BOLD);
        if (shop.isFee()) {
            return List.of(header,
                    Component.text(shop.ownerName(), NamedTextColor.BLACK),
                    Component.text("K " + shop.buy(), NamedTextColor.DARK_GREEN),
                    Component.text(feeLabel(shop), NamedTextColor.BLACK));
        }
        List<String> prices = new ArrayList<>();
        if (shop.buy() >= 0) prices.add("K " + shop.buy());
        if (shop.sell() >= 0) prices.add("V " + shop.sell());
        return List.of(header,
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
                player.sendMessage(msg(shop.isFee() ? "fee-info" : "info", player, tags(shop), money(shop.buy())));
                player.sendMessage(msg("owner-hint", player));
            } else if (shop.isFee()) {
                if (event.getAction() == Action.RIGHT_CLICK_BLOCK) payFee(player, shop);
                else player.sendMessage(msg("fee-info", player, tags(shop), money(shop.buy())));
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
                ? plugin.bank().payServer(player.getUniqueId(), shop.buy())
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

    private void payFee(Player player, Shop shop) {
        String key = player.getUniqueId() + "@" + key(shop.sign());
        long now = System.currentTimeMillis();
        Long last = lastFee.get(key);
        if (last != null && now - last < FEE_COOLDOWN_MILLIS) return;
        lastFee.values().removeIf(t -> now - t >= FEE_COOLDOWN_MILLIS);

        boolean paid = shop.admin()
                ? plugin.bank().payServer(player.getUniqueId(), shop.buy())
                : plugin.bank().transfer(player.getUniqueId(), shop.owner(), shop.buy());
        if (!paid) {
            player.sendMessage(msg("not-enough-money", player, tags(shop), money(shop.buy())));
            return;
        }
        lastFee.put(key, now);
        player.sendMessage(msg("fee-paid", player, tags(shop), money(shop.buy())));
        notifyOwner(shop, "owner-fee-paid", player);
    }

    private void notifyOwner(Shop shop, String key, Player customer) {
        if (shop.admin()) return;
        Player owner = Bukkit.getPlayer(shop.owner());
        if (owner == null) return;
        long price = key.equals("owner-sold") ? shop.sell() : shop.buy();
        owner.sendMessage(msg(key, owner, tags(shop), money(price), TextService.placeholder("customer", customer.getName())));
    }

    private Inventory inventory(Shop shop) {
        if (shop.container() == null) return null;
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
        if (shop == null) shop = signSupportedBy(block);
        if (shop == null) return;
        Player player = event.getPlayer();
        if (!isOwner(player, shop) && !player.hasPermission(PERM_ADMIN)) {
            event.setCancelled(true);
            player.sendMessage(msg("protected", player, tags(shop)));
            return;
        }
        // Kiste oder Schild abgebaut: alle Shops dieses Containers entfernen
        for (Shop s : List.copyOf(bySign.values())) {
            if (s == shop || (isShopContainer(block) && sameContainer(s, block))
                    || block.equals(supportOf(s.sign().getBlock()))) remove(s);
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
        return bySign.containsKey(key(block.getLocation())) || (isShopContainer(block) && shopForContainer(block) != null)
                || signSupportedBy(block) != null;
    }

    /** Shop-Schild, das an diesem Block hängt bzw. darauf steht – sonst würde es beim Abbau abfallen. */
    private Shop signSupportedBy(Block block) {
        if (bySign.isEmpty()) return null;
        for (BlockFace face : List.of(BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block neighbour = block.getRelative(face);
            Shop shop = bySign.get(key(neighbour.getLocation()));
            if (shop != null && block.equals(supportOf(neighbour))) return shop;
        }
        return null;
    }

    /** Block, an dem ein Schild hängt (Wandschild) bzw. auf dem es steht. */
    private static Block supportOf(Block sign) {
        return sign.getBlockData() instanceof WallSign wall
                ? sign.getRelative(wall.getFacing().getOppositeFace())
                : sign.getRelative(BlockFace.DOWN);
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
        if (shop.container() == null) return false;
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
                Placeholder.component("item", shop.isFee() ? Component.text(feeLabel(shop)) : shop.item().effectiveName()),
                TextService.placeholder("buy", shop.buy() < 0 ? "–" : plugin.bank().format(shop.buy())),
                TextService.placeholder("sell", shop.sell() < 0 ? "–" : plugin.bank().format(shop.sell())),
                TextService.placeholder("stock", shop.admin() || shop.isFee() ? "∞" : stock(shop)));
    }

    private static String feeLabel(Shop shop) {
        return shop.label().isEmpty() ? "Gebühr" : shop.label();
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
        if (shop.container() != null) byContainer.put(key(shop.container()), shop);
    }

    private void remove(Shop shop) {
        bySign.remove(key(shop.sign()));
        if (shop.container() == null) return;
        String containerKey = key(shop.container());
        byContainer.remove(containerKey, shop);
        // weitere Schilder am selben Container bleiben im Index
        bySign.values().stream().filter(s -> s.container() != null && key(s.container()).equals(containerKey))
                .findFirst().ifPresent(s -> byContainer.put(containerKey, s));
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
            boolean fee = s.getBoolean("fee");
            if (sign == null || (!fee && (container == null || item == null))) {
                plugin.getLogger().warning("Chestshop " + id + " übersprungen (Welt nicht geladen oder Daten fehlen).");
                continue;
            }
            add(new Shop(sign, container, UUID.fromString(s.getString("owner")), s.getString("owner-name", "?"),
                    fee ? null : item, s.getInt("amount"), s.getLong("buy", -1), s.getLong("sell", -1), s.getBoolean("admin"),
                    s.getString("label", "")));
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
            yml.set(path + "fee", shop.isFee());
            yml.set(path + "label", shop.label());
        }
        writer.write(file, yml.saveToString());
    }

    /** Zahl der Shops – für die Admin-Kachel. */
    public int count() {
        return bySign.size();
    }
}
