package de.sessentials.elytra;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Einweg-Elytren: Wer in einem Startbereich in die Tiefe springt, bekommt eine Elytra, die beim Landen wieder
 * verschwindet. Sie lässt sich nicht bewegen, droppen oder ablegen; F gibt im Flug einen Schub. Eine getragene
 * Brustplatte wird solange am Spieler (PDC) gespeichert und danach zurückgegeben – auch nach Absturz oder Quit.
 */
public final class DisposableElytra implements Listener {

    public static final String PERM_ADMIN = "sess.elytra.admin";
    public static final String PERM_USE = "sess.elytra.use";

    private record Area(String world, BoundingBox box) {
        boolean contains(Location loc) {
            return loc.getWorld().getName().equals(world) && box.contains(loc.toVector());
        }
    }

    private final SEssentials plugin;
    private final File file;
    private final AsyncFileWriter writer;
    private final NamespacedKey itemKey;
    private final NamespacedKey chestKey;
    private final Map<String, Area> areas = new HashMap<>();
    /** Admin → markierte Ecken [pos1, pos2]. */
    private final Map<UUID, Location[]> selections = new HashMap<>();
    /** Spieler, die gerade eine Einweg-Elytra tragen. */
    private final Set<UUID> flying = ConcurrentHashMap.newKeySet();
    /** Spieler → Zeitpunkt, bis zu dem kein Fallschaden zählt (kurz nach dem Landen). */
    private final Map<UUID, Long> safeUntil = new HashMap<>();
    private final Map<UUID, Long> boostReady = new HashMap<>();
    private BukkitTask task;

    public DisposableElytra(SEssentials plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("elytra-areas.yml");
        this.writer = new AsyncFileWriter("Elytra", plugin.getLogger());
        this.itemKey = new NamespacedKey(plugin, "disposable_elytra");
        this.chestKey = new NamespacedKey(plugin, "elytra_saved_chestplate");
        load();
    }

    public void start() {
        // Landung prüfen: alle 2 Ticks reicht, damit niemand auf dem Boden mit Elytra herumläuft
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkLanding, 2L, 2L);
        for (Player p : Bukkit.getOnlinePlayers()) restore(p);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (UUID id : Set.copyOf(flying)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) remove(p, false);
        }
        writer.shutdown();
    }

    // ------------------------------------------------------------
    // Ausrüsten & Entfernen
    // ------------------------------------------------------------

    private boolean isDisposable(ItemStack item) {
        return item != null && item.getType() == Material.ELYTRA && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey);
    }

    private ItemStack createElytra() {
        ItemStack item = new ItemStack(Material.ELYTRA);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Einweg-Elytra", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Verschwindet beim Landen · F = Schub", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private void equip(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        ItemStack chest = player.getInventory().getChestplate();
        if (chest != null && !chest.isEmpty()) pdc.set(chestKey, PersistentDataType.BYTE_ARRAY, chest.serializeAsBytes());
        player.getInventory().setChestplate(createElytra());
        flying.add(player.getUniqueId());
        player.setGliding(true);
        player.sendActionBar(msg("equipped", player));
    }

    /** Elytra weg, Brustplatte zurück. {@code landed}: Fallschaden kurz danach ignorieren. */
    private void remove(Player player, boolean landed) {
        flying.remove(player.getUniqueId());
        ItemStack chest = player.getInventory().getChestplate();
        if (isDisposable(chest)) player.getInventory().setChestplate(savedChestplate(player));
        player.getPersistentDataContainer().remove(chestKey);
        // irgendwo anders gelandete Reste (sollte nie passieren) ebenfalls entfernen
        player.getInventory().forEach(item -> {
            if (isDisposable(item)) item.setAmount(0);
        });
        if (landed && plugin.getConfig().getBoolean("elytra.no-fall-damage", true)) {
            safeUntil.put(player.getUniqueId(), System.currentTimeMillis() + 2000);
        }
    }

    private ItemStack savedChestplate(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(chestKey, PersistentDataType.BYTE_ARRAY);
        return bytes == null ? null : ItemStack.deserializeBytes(bytes);
    }

    /** Nach Absturz/Neustart: hängt noch eine Einweg-Elytra am Spieler, gibt es die alte Brustplatte zurück. */
    private void restore(Player player) {
        boolean wearing = isDisposable(player.getInventory().getChestplate());
        if (wearing || player.getPersistentDataContainer().has(chestKey)) remove(player, true);
    }

    private void checkLanding() {
        for (UUID id : Set.copyOf(flying)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                flying.remove(id);
                continue;
            }
            if (p.isOnGround() || p.isInWater() || p.isInLava() || p.isInsideVehicle()) {
                remove(p, true);
                p.sendActionBar(msg("removed", p));
            }
        }
    }

    // ------------------------------------------------------------
    // Auslöser
    // ------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (areas.isEmpty() || flying.contains(player.getUniqueId()) || player.isOnGround()) return;
        if (event.getTo().getY() >= event.getFrom().getY()) return; // nur beim Fallen
        if (player.getFallDistance() < plugin.getConfig().getDouble("elytra.min-fall-distance", 3)) return;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;
        if (player.isGliding() || player.isInsideVehicle() || !player.hasPermission(PERM_USE)) return;
        if (player.getInventory().getChestplate() != null && player.getInventory().getChestplate().getType() == Material.ELYTRA) return;
        Location loc = event.getTo();
        for (Area area : areas.values()) {
            if (area.contains(loc)) {
                equip(player);
                return;
            }
        }
    }

    /** F im Flug = Schub in Blickrichtung. Ohne Einweg-Elytra bleibt F normal. */
    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!flying.contains(player.getUniqueId())) return;
        event.setCancelled(true);
        if (!player.isGliding()) return;
        long now = System.currentTimeMillis();
        long ready = boostReady.getOrDefault(player.getUniqueId(), 0L);
        if (now < ready) {
            player.sendActionBar(msg("boost-cooldown", player,
                    TextService.placeholder("seconds", String.valueOf((ready - now + 999) / 1000))));
            return;
        }
        boostReady.put(player.getUniqueId(), now + (long) (plugin.getConfig().getDouble("elytra.boost.cooldown-seconds", 3) * 1000));
        double strength = plugin.getConfig().getDouble("elytra.boost.strength", 1.5);
        Vector dir = player.getLocation().getDirection().normalize().multiply(strength);
        player.setVelocity(player.getVelocity().add(dir));
        player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation(), 15, 0.2, 0.2, 0.2, 0.05);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
    }

    /** Gleiten nicht vorzeitig beenden lassen, solange man in der Luft ist. */
    @EventHandler(ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!event.isGliding() && event.getEntity() instanceof Player p && flying.contains(p.getUniqueId()) && !p.isOnGround()
                && !p.isInWater()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.FALL && cause != EntityDamageEvent.DamageCause.FLY_INTO_WALL) return;
        if (!plugin.getConfig().getBoolean("elytra.no-fall-damage", true)) return;
        Long until = safeUntil.get(p.getUniqueId());
        if (flying.contains(p.getUniqueId()) || (until != null && until > System.currentTimeMillis())) event.setCancelled(true);
    }

    // ------------------------------------------------------------
    // Nicht bewegen, droppen, ablegen
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (isDisposable(event.getCurrentItem()) || isDisposable(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (event.getHotbarButton() >= 0 && isDisposable(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (isDisposable(event.getOldCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isDisposable(event.getItemDrop().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (isDisposable(event.getPlayerItem()) || isDisposable(event.getArmorStandItem())) event.setCancelled(true);
    }

    /** Beim Tod nie droppen: statt der Elytra fällt die gespeicherte Brustplatte bzw. bleibt bei keepInventory. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        boolean had = event.getDrops().removeIf(this::isDisposable) || flying.contains(player.getUniqueId())
                || player.getPersistentDataContainer().has(chestKey);
        if (!had) return;
        ItemStack saved = savedChestplate(player);
        flying.remove(player.getUniqueId());
        player.getPersistentDataContainer().remove(chestKey);
        if (event.getKeepInventory()) {
            player.getInventory().setChestplate(saved);
        } else {
            player.getInventory().setChestplate(null);
            if (saved != null) event.getDrops().add(saved);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (flying.contains(p.getUniqueId())) remove(p, false);
        selections.remove(p.getUniqueId());
        safeUntil.remove(p.getUniqueId());
        boostReady.remove(p.getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restore(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (flying.contains(event.getPlayer().getUniqueId())) remove(event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        if (flying.contains(p.getUniqueId()) && event.getCause() != PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && flying.contains(p.getUniqueId())) remove(p, true);
            });
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (flying.contains(event.getPlayer().getUniqueId())) remove(event.getPlayer(), true);
    }

    // ------------------------------------------------------------
    // Befehle: /elytra pos1|pos2|create <name>|delete <name>|list
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(Commands.literal("elytra")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_ADMIN))
                        .then(Commands.literal("pos1").executes(ctx -> run(() -> mark((Player) ctx.getSource().getExecutor(), 0))))
                        .then(Commands.literal("pos2").executes(ctx -> run(() -> mark((Player) ctx.getSource().getExecutor(), 1))))
                        .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> run(() -> create((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    areas.keySet().stream().filter(n -> n.startsWith(b.getRemainingLowerCase())).forEach(b::suggest);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> run(() -> delete((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "name"))))))
                        .then(Commands.literal("list").executes(ctx -> run(() -> list((Player) ctx.getSource().getExecutor()))))
                        .build(),
                "Startbereiche für Einweg-Elytren verwalten", List.of());
    }

    private static int run(Runnable r) {
        r.run();
        return Command.SINGLE_SUCCESS;
    }

    private void mark(Player player, int index) {
        Location[] sel = selections.computeIfAbsent(player.getUniqueId(), id -> new Location[2]);
        sel[index] = player.getLocation().toBlockLocation();
        player.sendMessage(msg(index == 0 ? "pos1" : "pos2", player, coords(sel[index])));
    }

    private void create(Player player, String rawName) {
        String name = rawName.toLowerCase(java.util.Locale.ROOT);
        Location[] sel = selections.get(player.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            player.sendMessage(msg("no-selection", player));
            return;
        }
        if (!sel[0].getWorld().equals(sel[1].getWorld())) {
            player.sendMessage(msg("different-worlds", player));
            return;
        }
        if (areas.containsKey(name)) {
            player.sendMessage(msg("exists", player, TextService.placeholder("name", name)));
            return;
        }
        // volle Blöcke einschließen
        BoundingBox box = BoundingBox.of(sel[0].getBlock(), sel[1].getBlock());
        areas.put(name, new Area(sel[0].getWorld().getName(), box));
        save();
        player.sendMessage(msg("created", player, TextService.placeholder("name", name)));
    }

    private void delete(Player player, String name) {
        if (areas.remove(name.toLowerCase(java.util.Locale.ROOT)) == null) {
            player.sendMessage(msg("unknown", player, TextService.placeholder("name", name)));
            return;
        }
        save();
        player.sendMessage(msg("deleted", player, TextService.placeholder("name", name)));
    }

    private void list(Player player) {
        if (areas.isEmpty()) {
            player.sendMessage(msg("list-empty", player));
            return;
        }
        player.sendMessage(msg("list-header", player, TextService.placeholder("count", String.valueOf(areas.size()))));
        areas.forEach((name, a) -> player.sendMessage(msg("list-entry", player, TextService.placeholder("name", name),
                TextService.placeholder("world", a.world()),
                TextService.placeholder("from", (int) a.box().getMinX() + " " + (int) a.box().getMinY() + " " + (int) a.box().getMinZ()),
                TextService.placeholder("to", (int) a.box().getMaxX() + " " + (int) a.box().getMaxY() + " " + (int) a.box().getMaxZ()))));
    }

    private static TagResolver coords(Location l) {
        return TextService.placeholder("coords", l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ());
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("elytra.messages." + key, ""), player, extra);
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(file).getConfigurationSection("areas");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection a = sec.getConfigurationSection(name);
            if (a == null) continue;
            areas.put(name, new Area(a.getString("world", ""), new BoundingBox(
                    a.getDouble("min.x"), a.getDouble("min.y"), a.getDouble("min.z"),
                    a.getDouble("max.x"), a.getDouble("max.y"), a.getDouble("max.z"))));
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        areas.forEach((name, a) -> {
            String p = "areas." + name + ".";
            yml.set(p + "world", a.world());
            yml.set(p + "min.x", a.box().getMinX());
            yml.set(p + "min.y", a.box().getMinY());
            yml.set(p + "min.z", a.box().getMinZ());
            yml.set(p + "max.x", a.box().getMaxX());
            yml.set(p + "max.y", a.box().getMaxY());
            yml.set(p + "max.z", a.box().getMaxZ());
        });
        writer.write(file, yml.saveToString());
    }
}
