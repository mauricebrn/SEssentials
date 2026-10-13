package de.sessentials.itemguard;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ItemGuard: entfernt nicht survival-taugliche Items (Liste in der Config, optional alle Spawn-Eier) aus Inventar,
 * Enderchest und Shulkerkisten darin, meldet den Spieler automatisch im Report-System und blockiert Spawn-Eier
 * schon beim Benutzen. Spieler mit {@code sess.itemguard.bypass} werden nicht gescannt.
 */
public final class ItemGuard implements Listener {

    static final String PERM_BYPASS = "sess.itemguard.bypass";
    static final String PERM_SCAN = "sess.itemguard.scan";
    private static final String REPORTER = "ItemGuard";

    private final SEssentials plugin;
    private volatile boolean enabled;
    private volatile boolean spawnEggs;
    private volatile boolean enderChest;
    private volatile boolean shulkers;
    private volatile Set<Material> banned = Set.of();
    private BukkitTask task;
    private int removedSinceStart;

    public ItemGuard(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Ohne explizite Defaults lesen: fehlende Keys in älteren Configs fallen auf die config.yml im JAR zurück. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("itemguard.enabled");
        spawnEggs = cfg.getBoolean("itemguard.block-spawn-eggs");
        enderChest = cfg.getBoolean("itemguard.scan-enderchest");
        shulkers = cfg.getBoolean("itemguard.scan-shulkers");
        Set<Material> parsed = new LinkedHashSet<>();
        for (String name : cfg.getStringList("itemguard.banned")) {
            Material m = Material.matchMaterial(name);
            if (m == null) plugin.getLogger().warning("itemguard.banned: '" + name + "' ist kein Material – übersprungen.");
            else parsed.add(m);
        }
        banned = Set.copyOf(parsed);

        if (task != null) task.cancel();
        task = null;
        if (enabled) {
            long ticks = Math.max(1L, cfg.getLong("itemguard.interval-seconds")) * 20L;
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::scanAll, ticks, ticks);
        }
    }

    // ------------------------------------------------------------
    // Einstellungen (für das Admin-Menü; werden in der config.yml gespeichert)
    // ------------------------------------------------------------

    public boolean enabled() { return enabled; }
    public boolean spawnEggs() { return spawnEggs; }
    public boolean enderChest() { return enderChest; }
    public boolean shulkers() { return shulkers; }
    public int removedSinceStart() { return removedSinceStart; }

    /** Verbotene Items in Config-Reihenfolge. */
    public List<Material> banned() {
        return List.copyOf(banned);
    }

    void set(String key, Object value) {
        plugin.getConfig().set("itemguard." + key, value);
        plugin.saveConfig();
        reload();
    }

    void setBanned(List<Material> materials) {
        set("banned", materials.stream().map(Material::name).toList());
    }

    // ------------------------------------------------------------
    // Scannen
    // ------------------------------------------------------------

    public void scanAll() {
        if (!enabled) return;
        for (Player player : Bukkit.getOnlinePlayers()) scan(player);
    }

    /** Entfernt verbotene Items; liefert die entfernten Mengen pro Material (leer = nichts gefunden). */
    public Map<Material, Integer> scan(Player player) {
        Map<Material, Integer> found = new EnumMap<>(Material.class);
        if (!enabled || player.hasPermission(PERM_BYPASS)) return found;
        clean(player.getInventory(), found);
        if (enderChest) clean(player.getEnderChest(), found);
        if (!found.isEmpty()) punish(player, found, "Illegale Items entfernt");
        return found;
    }

    private void clean(Inventory inventory, Map<Material, Integer> found) {
        ItemStack[] contents = inventory.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.isEmpty()) continue;
            if (isBanned(item.getType())) {
                found.merge(item.getType(), item.getAmount(), Integer::sum);
                contents[i] = null;
                changed = true;
            } else if (shulkers && cleanShulker(item, found)) {
                changed = true;
            }
        }
        if (changed) inventory.setContents(contents);
    }

    /** Eine Ebene tief: verbotene Items in Shulkerkisten. {@code true}, wenn etwas entfernt wurde. */
    private boolean cleanShulker(ItemStack item, Map<Material, Integer> found) {
        if (!Tag.SHULKER_BOXES.isTagged(item.getType())
                || !(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) return false;
        ItemStack[] inner = box.getInventory().getContents();
        boolean changed = false;
        for (int i = 0; i < inner.length; i++) {
            if (inner[i] != null && isBanned(inner[i].getType())) {
                found.merge(inner[i].getType(), inner[i].getAmount(), Integer::sum);
                inner[i] = null;
                changed = true;
            }
        }
        if (!changed) return false;
        box.getInventory().setContents(inner);
        meta.setBlockState(box);
        item.setItemMeta(meta);
        return true;
    }

    private boolean isBanned(Material type) {
        return banned.contains(type) || (spawnEggs && type.name().endsWith("_SPAWN_EGG"));
    }

    /** Spieler informieren, Report anlegen, Admins benachrichtigen, in der Konsole protokollieren. */
    private void punish(Player player, Map<Material, Integer> found, String reason) {
        removedSinceStart += found.values().stream().mapToInt(Integer::intValue).sum();
        List<String> details = new ArrayList<>();
        found.forEach((m, n) -> details.add(n + "x " + m.name()));
        // Grund enthält die Items, damit unterschiedliche Funde eigene Reports bekommen
        int id = plugin.reports().createSystemReport(REPORTER, player.getName(),
                reason + ": " + String.join(", ", found.keySet().stream().map(Material::name).toList()), details);

        Component items = Component.join(JoinConfiguration.commas(true), found.entrySet().stream()
                .map(e -> Component.text(e.getValue() + "x ").append(Component.translatable(e.getKey().translationKey())))
                .toList());
        TagResolver tags = TagResolver.resolver(Placeholder.component("items", items),
                TextService.placeholder("id", String.valueOf(id)),
                TextService.placeholder("target", player.getName()));
        player.sendMessage(msg("removed", player, tags));

        Component notify = msg("notify", player, tags);
        Sound sound = Sound.sound(org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.MASTER, 1f, 1f);
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (plugin.notifications().wantsReports(admin)) {
                admin.sendMessage(notify);
                admin.playSound(sound);
            }
        }
        plugin.getLogger().warning("ItemGuard: " + player.getName() + " – " + String.join(", ", details) + " entfernt (Report #" + id + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) scan(player);
        }, 10L);
    }

    /** Spawn-Eier sofort beim Benutzen blockieren statt erst beim nächsten Scan. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        if (!enabled || !spawnEggs || event.getItem() == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!item.getType().name().endsWith("_SPAWN_EGG") || event.getPlayer().hasPermission(PERM_BYPASS)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Map<Material, Integer> found = new EnumMap<>(Material.class);
        found.put(item.getType(), item.getAmount());
        if (event.getHand() != null) player.getInventory().setItem(event.getHand(), null);
        player.sendMessage(msg("spawn-egg", player));
        punish(player, found, "Spawn-Ei benutzt");
    }

    // ------------------------------------------------------------
    // /itemscan
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("itemscan")
                        .requires(src -> src.getSender().hasPermission(PERM_SCAN))
                        .executes(ctx -> {
                            scanAll();
                            reply(ctx.getSource().getSender(), "scanned-all", null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("spieler", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    Bukkit.getOnlinePlayers().forEach(p -> builder.suggest(p.getName()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    Player target = Bukkit.getPlayerExact(StringArgumentType.getString(ctx, "spieler"));
                                    if (target == null) {
                                        reply(sender, "not-online", null);
                                        return 0;
                                    }
                                    scan(target);
                                    reply(sender, "scanned", target);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(),
                "Scannt Inventare nach verbotenen Items",
                List.of("iscan"));
    }

    private void reply(CommandSender sender, String key, Player target) {
        sender.sendMessage(msg(key, sender instanceof Player p ? p : null,
                TextService.placeholder("target", target == null ? "" : target.getName())));
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("itemguard.messages." + key), player, extra);
    }
}
