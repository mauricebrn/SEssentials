package de.sessentials.creativelog;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * CreativeLog: protokolliert die Aktionen von Spielern im Kreativmodus (außer mit {@code sess.creativelog.bypass}).
 * Einträge pro Spieler begrenzt; gesammelt und alle 30 s, beim Verlassen und beim Stoppen in
 * {@code creativelog/<uuid>.log} geschrieben. Abruf mit /creativelog oder im Admin-Panel.
 */
public final class CreativeLog implements Listener {

    public static final String PERM_VIEW = "sess.creativelog.view";
    private static final String PERM_BYPASS = "sess.creativelog.bypass";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final long FLUSH_TICKS = 30 * 20L;

    /** Art des Eintrags – bestimmt die Farbe in der Anzeige. */
    public enum Kind {
        ENTER(NamedTextColor.GREEN), LEAVE(NamedTextColor.RED), SESSION(NamedTextColor.YELLOW),
        ITEM(NamedTextColor.AQUA), BLOCK(NamedTextColor.WHITE), INTERACT(NamedTextColor.GRAY),
        COMBAT(NamedTextColor.RED), COMMAND(NamedTextColor.GOLD);

        final NamedTextColor color;

        Kind(NamedTextColor color) {
            this.color = color;
        }
    }

    public record Entry(long time, Kind kind, String text) {}

    /** Unveränderliche Kopie eines Spieler-Logs, z.B. für das Webpanel. */
    public record LogSnapshot(UUID id, String name, boolean inCreativeNow, List<Entry> entries) {}

    /** Log eines Spielers, älteste zuerst. */
    static final class PlayerLog {
        final UUID id;
        String name;
        final Deque<Entry> entries = new ArrayDeque<>();

        PlayerLog(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        Entry latest() {
            return entries.peekLast();
        }
    }

    private final SEssentials plugin;
    private final File dir;
    private final AsyncFileWriter writer;
    private final Map<UUID, PlayerLog> logs = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private volatile boolean enabled;
    private volatile int maxEntries;
    private BukkitTask flushTask;

    public CreativeLog(SEssentials plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "creativelog");
        this.writer = new AsyncFileWriter("CreativeLog", plugin.getLogger());
        load();
        reload();
        flushTask = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, FLUSH_TICKS, FLUSH_TICKS);
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("creativelog.enabled");
        maxEntries = Math.max(10, plugin.getConfig().getInt("creativelog.max-entries"));
    }

    public void shutdown() {
        if (flushTask != null) flushTask.cancel();
        flush();
        writer.shutdown();
    }

    public boolean enabled() {
        return enabled;
    }

    Collection<PlayerLog> logs() {
        return logs.values();
    }

    /** Kopie aller Logs (älteste Einträge zuerst). Nur im Main-Thread aufrufen. */
    public List<LogSnapshot> snapshot() {
        List<LogSnapshot> result = new ArrayList<>();
        for (PlayerLog log : logs.values()) {
            Player online = Bukkit.getPlayer(log.id);
            result.add(new LogSnapshot(log.id, log.name, online != null && online.getGameMode() == GameMode.CREATIVE,
                    List.copyOf(log.entries)));
        }
        return result;
    }

    // ------------------------------------------------------------
    // Protokollieren
    // ------------------------------------------------------------

    private boolean tracked(Player player) {
        return enabled && player.getGameMode() == GameMode.CREATIVE && !player.hasPermission(PERM_BYPASS);
    }

    private void log(Player player, Kind kind, String text) {
        PlayerLog log = logs.computeIfAbsent(player.getUniqueId(), id -> new PlayerLog(id, player.getName()));
        log.name = player.getName();
        log.entries.addLast(new Entry(System.currentTimeMillis(), kind, text));
        while (log.entries.size() > maxEntries) log.entries.removeFirst();
        dirty.add(player.getUniqueId());
    }

    private static String pos(Location loc) {
        return loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ() + " (" + loc.getWorld().getName() + ")";
    }

    private static String item(ItemStack item) {
        if (item == null || item.isEmpty()) return "nichts";
        return item.getAmount() + "x " + item.getType().name() + (item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? " (umbenannt)" : "");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (!enabled || player.hasPermission(PERM_BYPASS)) return;
        if (event.getNewGameMode() == GameMode.CREATIVE) {
            log(player, Kind.ENTER, ">> Kreativmodus BETRETEN bei " + pos(player.getLocation()));
        } else if (player.getGameMode() == GameMode.CREATIVE) {
            log(player, Kind.LEAVE, "<< Kreativmodus VERLASSEN (neuer Modus: " + event.getNewGameMode().name() + ")");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (tracked(event.getPlayer())) log(event.getPlayer(), Kind.SESSION, "Server betreten im Kreativmodus bei " + pos(event.getPlayer().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (tracked(event.getPlayer())) log(event.getPlayer(), Kind.SESSION, "Server verlassen im Kreativmodus");
        flushPlayer(event.getPlayer().getUniqueId());
    }

    /** Items aus dem Kreativ-Inventar nehmen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreativeTake(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !tracked(player)) return;
        ItemStack cursor = event.getCursor();
        if (!cursor.isEmpty()) log(player, Kind.ITEM, "Aus Kreativ-Inventar: " + item(cursor));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !tracked(player)) return;
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.CREATIVE || type == InventoryType.PLAYER) return;
        Location loc = event.getInventory().getLocation();
        log(player, Kind.INTERACT, "Container geöffnet: " + type.name() + (loc != null ? " bei " + pos(loc) : ""));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (tracked(event.getPlayer())) log(event.getPlayer(), Kind.ITEM, "Gedroppt: " + item(event.getItemDrop().getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && tracked(player)) {
            log(player, Kind.ITEM, "Aufgehoben: " + item(event.getItem().getItemStack()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (tracked(event.getPlayer())) log(event.getPlayer(), Kind.BLOCK, "Platziert: " + event.getBlock().getType().name() + " bei " + pos(event.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (tracked(event.getPlayer())) log(event.getPlayer(), Kind.BLOCK, "Abgebaut: " + event.getBlock().getType().name() + " bei " + pos(event.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !tracked(event.getPlayer())) return;
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null) {
            log(event.getPlayer(), Kind.INTERACT, "Rechtsklick auf " + block.getType().name() + " bei " + pos(block.getLocation()) + " mit " + item(event.getItem()));
        } else if (event.getAction() == Action.RIGHT_CLICK_AIR && event.getItem() != null) {
            log(event.getPlayer(), Kind.INTERACT, "Rechtsklick mit " + item(event.getItem()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !tracked(event.getPlayer())) return;
        log(event.getPlayer(), Kind.INTERACT, "Rechtsklick auf " + event.getRightClicked().getType().name()
                + " mit " + item(event.getPlayer().getInventory().getItemInMainHand()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null;
        if (attacker == null || !tracked(attacker)) return;
        String victim = event.getEntity() instanceof Player v ? "Spieler " + v.getName() : event.getEntity().getType().name();
        log(attacker, Kind.COMBAT, "Schaden an " + victim + ": " + Math.round(event.getFinalDamage())
                + " (Item: " + item(attacker.getInventory().getItemInMainHand()) + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!tracked(event.getPlayer())) return;
        String label = event.getMessage().split(" ", 2)[0].toLowerCase();
        if (label.equals("/creativelog") || label.equals("/clog")) return;
        log(event.getPlayer(), Kind.COMMAND, "Befehl: " + event.getMessage());
    }

    // ------------------------------------------------------------
    // Anzeigen
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("creativelog")
                        .requires(src -> src.getSender().hasPermission(PERM_VIEW))
                        .then(Commands.argument("spieler", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    logs.values().forEach(l -> builder.suggest(l.name));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    show(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "spieler"), 1);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("seite", IntegerArgumentType.integer(1)).executes(ctx -> {
                                    show(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "spieler"),
                                            IntegerArgumentType.getInteger(ctx, "seite"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .build(),
                "Zeigt die Kreativ-Aktionen eines Spielers",
                List.of("clog"));
    }

    /** Zeigt eine Seite des Logs im Chat, neueste Einträge zuerst. */
    public void show(CommandSender viewer, String name, int page) {
        Player self = viewer instanceof Player p ? p : null;
        PlayerLog log = find(name);
        if (log == null || log.entries.isEmpty()) {
            viewer.sendMessage(msg("none", self, TextService.placeholder("target", name)));
            return;
        }
        int size = Math.max(1, plugin.getConfig().getInt("creativelog.page-size"));
        List<Entry> newestFirst = new ArrayList<>(log.entries).reversed();
        int pages = (newestFirst.size() + size - 1) / size;
        if (page > pages) {
            viewer.sendMessage(msg("no-page", self, TextService.placeholder("pages", String.valueOf(pages))));
            return;
        }
        TagResolver tags = TagResolver.resolver(
                TextService.placeholder("target", log.name),
                TextService.placeholder("page", String.valueOf(page)),
                TextService.placeholder("pages", String.valueOf(pages)),
                TextService.placeholder("prev", String.valueOf(Math.max(1, page - 1))),
                TextService.placeholder("next", String.valueOf(Math.min(pages, page + 1))));
        viewer.sendMessage(msg("header", self, tags));
        for (Entry e : newestFirst.subList((page - 1) * size, Math.min(newestFirst.size(), page * size))) {
            viewer.sendMessage(Component.text("[" + TIME.format(Instant.ofEpochMilli(e.time())) + "] ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(e.text(), e.kind().color)));
        }
        viewer.sendMessage(msg("footer", self, tags));
    }

    private PlayerLog find(String name) {
        for (PlayerLog log : logs.values()) if (log.name.equalsIgnoreCase(name)) return log;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached == null ? null : logs.get(cached.getUniqueId());
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("creativelog.messages." + key), player, extra);
    }

    // ------------------------------------------------------------
    // Speichern: "#name <name>", dann je Zeile "<zeit>|<art>|<text>"
    // ------------------------------------------------------------

    private void load() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log"));
        if (files == null) return;
        for (File f : files) {
            try {
                UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 4));
                List<String> lines = Files.readAllLines(f.toPath());
                PlayerLog log = new PlayerLog(id, "?");
                for (String line : lines) {
                    if (line.startsWith("#name ")) {
                        log.name = line.substring(6);
                        continue;
                    }
                    String[] parts = line.split("\\|", 3);
                    if (parts.length < 3) continue;
                    try {
                        log.entries.addLast(new Entry(Long.parseLong(parts[0]), Kind.valueOf(parts[1]), parts[2]));
                    } catch (IllegalArgumentException ignored) {
                        // beschädigte Zeile überspringen
                    }
                }
                logs.put(id, log);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("CreativeLog: " + f.getName() + " übersprungen (kein gültiger Dateiname).");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "CreativeLog: " + f.getName() + " konnte nicht gelesen werden", e);
            }
        }
    }

    private void flush() {
        for (UUID id : List.copyOf(dirty)) flushPlayer(id);
    }

    private void flushPlayer(UUID id) {
        if (!dirty.remove(id)) return;
        PlayerLog log = logs.get(id);
        if (log == null) return;
        StringBuilder out = new StringBuilder("#name ").append(log.name).append('\n');
        for (Entry e : log.entries) {
            out.append(e.time()).append('|').append(e.kind().name()).append('|')
                    .append(e.text().replace('\n', ' ')).append('\n');
        }
        writer.write(new File(dir, id + ".log"), out.toString());
    }
}
