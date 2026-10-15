package de.sessentials.upgrader;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Item-Upgrader: ein Item aus dem eigenen Inventar setzen, ein wertvolleres Ziel wählen und drehen.
 * Chance = Hausfaktor × Einsatzwert / Zielwert (begrenzt). Das Ergebnis steht beim Klick fest; ein Gewinn
 * wird sofort gespeichert und nach der Animation – oder beim nächsten Join – ausgezahlt.
 */
public final class ItemUpgrader implements Listener {

    public static final String PERM = "sess.upgrader";
    public static final String PERM_ADMIN = "sess.upgrader.admin";

    /** Auswahl eines Spielers. Der Einsatz bleibt bis zum Drehen im Inventar und wird dann gegen die Kopie geprüft. */
    static final class Session {
        int betSlot = -1;
        ItemStack bet;
        Material target;
        int amount = 1;
        boolean spinning;
        /** Feld des Bands unter dem Zeiger, damit das nächste Drehen dort weitermacht. */
        int offset;

        void clearBet() {
            betSlot = -1;
            bet = null;
        }
    }

    record Outcome(boolean success, double chance, ItemStack reward) {}

    private final SEssentials plugin;
    private final File pendingFile;
    private final AsyncFileWriter writer;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Gewonnene, noch nicht ausgezahlte Items (Disconnect/Absturz während der Animation). */
    private final Map<UUID, List<ItemStack>> pending = new HashMap<>();
    private final UpgraderMenu menu;
    private ItemValues values;
    private Set<Material> blacklist = EnumSet.noneOf(Material.class);

    public ItemUpgrader(SEssentials plugin) {
        this.plugin = plugin;
        this.pendingFile = new File(plugin.getDataFolder(), "upgrader-pending.yml");
        this.writer = new AsyncFileWriter("Upgrader", plugin.getLogger());
        this.menu = new UpgraderMenu(plugin, this);
        loadPending();
        reload();
    }

    public void reload() {
        YamlConfiguration base = new YamlConfiguration();
        try (InputStream in = plugin.getResource("upgrader-values.yml")) {
            if (in != null) base = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("upgrader-values.yml nicht lesbar: " + e.getMessage());
        }
        values = ItemValues.compute(base, plugin.getConfig().getConfigurationSection("upgrader.value-overrides"), plugin.getLogger());
        plugin.getLogger().info("Item-Upgrader: " + values.sorted().size() + " von " + ItemValues.allItems().size()
                + " Items haben einen Wert (Rest: /upgrader admin → Rechtsklick).");
        Set<Material> list = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("upgrader.blacklist")) {
            Material m = Material.matchMaterial(name);
            if (m == null) plugin.getLogger().warning("upgrader.blacklist: " + name + " ist kein Item – übersprungen.");
            else list.add(m);
        }
        blacklist = list;
    }

    public void shutdown() {
        savePending();
        writer.shutdown();
    }

    ItemValues values() {
        return values;
    }

    boolean blacklisted(Material m) {
        return blacklist.contains(m);
    }

    Session session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new Session());
    }

    // ------------------------------------------------------------
    // Regeln
    // ------------------------------------------------------------

    /** Nachrichten-Schlüssel, warum das Item nicht gesetzt werden kann, oder {@code null}. */
    String betProblem(ItemStack item) {
        if (item == null || item.getType().isAir()) return "bet-empty";
        if (blacklisted(item.getType())) return "bet-blacklisted";
        if (!values.known(item.getType())) return "bet-no-value";
        ItemMeta meta = item.getItemMeta();
        // umbenannte oder von Plugins markierte Items sind keine normalen Items mit Normalwert
        if (meta.hasDisplayName() || meta.hasLore() || item.hasData(DataComponentTypes.CUSTOM_MODEL_DATA)
                || !meta.getPersistentDataContainer().isEmpty()) return "bet-modified";
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof Container c
                && !c.getInventory().isEmpty()) return "bet-container";
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) return "bet-container";
        return null;
    }

    boolean targetAllowed(Material m) {
        return values.known(m) && !blacklisted(m);
    }

    double chance(double betValue, double targetValue) {
        if (betValue <= 0 || targetValue <= 0) return 0;
        var cfg = plugin.getConfig();
        double min = cfg.getDouble("upgrader.min-chance", 0.001), max = cfg.getDouble("upgrader.max-chance", 0.9);
        return Math.clamp(cfg.getDouble("upgrader.house-factor", 0.9) * betValue / targetValue, min, Math.max(min, max));
    }

    double targetValue(Session s) {
        return s.target == null ? 0 : values.value(s.target) * s.amount;
    }

    /** Aktuelle Chance der Auswahl, 0 wenn Einsatz oder Ziel fehlt. */
    double chance(Session s) {
        return s.bet == null || s.target == null ? 0 : chance(values.value(s.bet), targetValue(s));
    }

    void selectBet(Player player, int slot) {
        Session s = session(player);
        if (s.spinning) return;
        ItemStack item = player.getInventory().getItem(slot);
        String problem = betProblem(item);
        if (problem != null) {
            if (item != null && !item.getType().isAir()) player.sendMessage(msg(problem, player));
            return;
        }
        s.betSlot = slot;
        s.bet = item.clone();
    }

    void selectTarget(Player player, Material target, int amount) {
        Session s = session(player);
        if (s.spinning || !targetAllowed(target)) return;
        s.target = target;
        s.amount = Math.clamp(amount, 1, target.getMaxStackSize());
    }

    void changeAmount(Player player, int delta) {
        Session s = session(player);
        if (s.spinning || s.target == null) return;
        s.amount = Math.clamp(s.amount + delta, 1, s.target.getMaxStackSize());
    }

    /**
     * Schnellwahl: sucht das Ziel (Item und Menge), dessen Wert {@code wanted} am nächsten kommt –
     * nicht das gesetzte Item selbst.
     */
    void quickPick(Player player, double wanted) {
        Session s = session(player);
        if (s.spinning) return;
        if (s.bet == null) {
            player.sendMessage(msg("bet-empty", player));
            return;
        }
        Material best = null;
        int bestAmount = 1;
        double bestError = Double.MAX_VALUE;
        for (Material m : values.sorted()) {
            if (blacklisted(m) || m == s.bet.getType()) continue;
            double each = values.value(m);
            int amount = (int) Math.clamp(Math.round(wanted / each), 1, m.getMaxStackSize());
            double error = Math.abs(each * amount - wanted) / wanted;
            // bei gleichem Abstand lieber weniger, aber wertvollere Items
            if (error < bestError - 1e-9 || Math.abs(error - bestError) <= 1e-9 && amount < bestAmount) {
                best = m;
                bestAmount = amount;
                bestError = error;
            }
        }
        if (best != null) {
            s.target = best;
            s.amount = bestAmount;
        }
    }

    void quickMultiplier(Player player, double multiplier) {
        Session s = session(player);
        if (s.bet != null) quickPick(player, values.value(s.bet) * multiplier);
        else player.sendMessage(msg("bet-empty", player));
    }

    void quickChance(Player player, double chance) {
        Session s = session(player);
        if (s.bet != null) quickPick(player, values.value(s.bet) * plugin.getConfig().getDouble("upgrader.house-factor", 0.9) / chance);
        else player.sendMessage(msg("bet-empty", player));
    }

    // ------------------------------------------------------------
    // Drehen
    // ------------------------------------------------------------

    /**
     * Prüft die Auswahl, nimmt den Einsatz und lost aus. Ein Gewinn wird vor der Animation gespeichert.
     * @return Ergebnis oder {@code null} (dann wurde dem Spieler der Grund geschrieben)
     */
    Outcome spin(Player player) {
        Session s = session(player);
        if (s.spinning) return null;
        if (s.bet == null) {
            player.sendMessage(msg("bet-empty", player));
            return null;
        }
        if (s.target == null || !targetAllowed(s.target)) {
            player.sendMessage(msg("target-empty", player));
            return null;
        }
        ItemStack now = s.betSlot >= 0 ? player.getInventory().getItem(s.betSlot) : null;
        // das Item muss noch genau so im selben Slot liegen – sonst neu auswählen
        if (now == null || !now.equals(s.bet)) {
            s.clearBet();
            player.sendMessage(msg("bet-changed", player));
            return null;
        }
        String problem = betProblem(now);
        if (problem != null) {
            s.clearBet();
            player.sendMessage(msg(problem, player));
            return null;
        }
        double chance = chance(s);
        player.getInventory().setItem(s.betSlot, null);
        boolean success = ThreadLocalRandom.current().nextDouble() < chance;
        ItemStack reward = new ItemStack(s.target, s.amount);
        if (success) {
            pending.computeIfAbsent(player.getUniqueId(), id -> new ArrayList<>()).add(reward.clone());
            savePending();
        }
        plugin.getLogger().info("Upgrader: " + player.getName() + " setzt " + now.getAmount() + "x " + now.getType()
                + " auf " + s.amount + "x " + s.target + " (" + percent(chance) + ") – " + (success ? "Gewinn" : "verloren"));
        s.clearBet();
        s.spinning = true;
        return new Outcome(success, chance, reward);
    }

    /** Nach der Animation: auszahlen, Nachricht und Effekte für Umstehende. */
    void finish(Player player, Outcome outcome) {
        Session s = session(player);
        s.spinning = false;
        if (!player.isOnline()) return; // Gewinn bleibt gespeichert und kommt beim nächsten Join
        TagResolver tags = TagResolver.resolver(
                TextService.placeholder("amount", String.valueOf(outcome.reward().getAmount())),
                TextService.placeholder("chance", percent(outcome.chance())),
                Placeholder.component("item", Component.translatable(outcome.reward().getType().translationKey())));
        Location at = player.getLocation().add(0, 1, 0);
        if (outcome.success()) {
            deliver(player);
            player.sendMessage(msg("won", player, tags));
            player.getWorld().playSound(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, at, 60, 0.4, 0.6, 0.4, 0.3);
        } else {
            player.sendMessage(msg("lost", player, tags));
            player.getWorld().playSound(at, Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
            player.getWorld().spawnParticle(Particle.SMOKE, at, 30, 0.3, 0.4, 0.3, 0.02);
        }
    }

    /** Zahlt gespeicherte Gewinne aus; was nicht ins Inventar passt, fällt auf den Boden. */
    private void deliver(Player player) {
        List<ItemStack> items = pending.remove(player.getUniqueId());
        if (items == null) return;
        savePending();
        for (ItemStack item : items) {
            player.getInventory().addItem(item).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!pending.containsKey(player.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !pending.containsKey(player.getUniqueId())) return;
            deliver(player);
            player.sendMessage(msg("delivered", player));
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && !s.spinning) sessions.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------
    // Admin
    // ------------------------------------------------------------

    void toggleBlacklist(Material m) {
        List<String> list = new ArrayList<>(plugin.getConfig().getStringList("upgrader.blacklist"));
        if (!list.removeIf(n -> m == Material.matchMaterial(n))) list.add(m.name());
        plugin.getConfig().set("upgrader.blacklist", list);
        plugin.saveConfig();
        reload();
    }

    /** {@code value <= 0} entfernt die Überschreibung. */
    void setOverride(Material m, double value) {
        plugin.getConfig().set("upgrader.value-overrides." + m.name(), value > 0 ? value : null);
        plugin.saveConfig();
        reload();
    }

    boolean overridden(Material m) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("upgrader.value-overrides");
        return sec != null && sec.contains(m.name());
    }

    // ------------------------------------------------------------
    // Befehl
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("upgrader")
                        .requires(src -> src.getSender().hasPermission(PERM))
                        .executes(ctx -> {
                            if (ctx.getSource().getExecutor() instanceof Player p) menu.open(p);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("admin")
                                .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_ADMIN))
                                .executes(ctx -> {
                                    menu.openCatalog((Player) ctx.getSource().getExecutor(), 0, "", true);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(Commands.literal("value")
                                .executes(ctx -> {
                                    if (ctx.getSource().getExecutor() instanceof Player p) showValue(p, p.getInventory().getItemInMainHand());
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("item", ArgumentTypes.itemStack()).executes(ctx -> {
                                    showValue(ctx.getSource().getSender(), ctx.getArgument("item", ItemStack.class));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .build(),
                "Item-Upgrader: setze ein Item und gewinne ein wertvolleres",
                List.of("upgrade"));
    }

    public void openAdmin(Player player) {
        menu.openCatalog(player, 0, "", true);
    }

    private void showValue(CommandSender sender, ItemStack item) {
        Player p = sender instanceof Player pl ? pl : null;
        if (item == null || item.getType().isAir() || !values.known(item.getType())) {
            sender.sendMessage(msg("value-unknown", p));
            return;
        }
        sender.sendMessage(msg("value", p,
                TextService.placeholder("value", format(values.value(item.getType()))),
                TextService.placeholder("total", format(values.value(item))),
                TextService.placeholder("amount", String.valueOf(item.getAmount())),
                Placeholder.component("item", Component.translatable(item.getType().translationKey()))));
    }

    // ------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------

    static String format(double value) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
        f.setMaximumFractionDigits(value < 10 ? 2 : 0);
        return f.format(value);
    }

    static String percent(double chance) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
        f.setMaximumFractionDigits(chance < 0.01 ? 2 : 1);
        return f.format(chance * 100) + " %";
    }

    Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("upgrader.messages." + key, ""), player, extra);
    }

    private void loadPending() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(pendingFile);
        ConfigurationSection sec = yml.getConfigurationSection("pending");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            List<ItemStack> items = new ArrayList<>();
            for (Object o : sec.getList(id, List.of())) if (o instanceof ItemStack i) items.add(i);
            if (!items.isEmpty()) pending.put(UUID.fromString(id), items);
        }
    }

    private void savePending() {
        YamlConfiguration yml = new YamlConfiguration();
        pending.forEach((id, items) -> yml.set("pending." + id, items));
        writer.write(pendingFile, yml.saveToString());
    }
}
