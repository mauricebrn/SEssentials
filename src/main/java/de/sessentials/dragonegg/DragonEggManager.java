package de.sessentials.dragonegg;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Schwebendes Drachenei: Das Ei, das nach dem Tod des Enderdrachen auf dem Portal erscheint, wird durch ein sich
 * drehendes Ei mit Zähler ersetzt. Einsammeln (Klick) geht nur, wenn der Spieler allein im Umkreis ist.
 * Die Anzeige besteht aus drei Entities (Ei, Text, Klickfläche) mit derselben ID im PDC – sie überstehen Neustarts.
 */
public final class DragonEggManager implements Listener {

    public static final String PERM_ADMIN = "sess.dragonegg.admin";
    /** Ein ID-Wert verbindet Ei, Text und Klickfläche. */
    private record Egg(UUID item, UUID text, UUID hitbox) {}

    private final SEssentials plugin;
    private final NamespacedKey key;
    /** Ei-ID → Entities; nur geladene Eier (Main-Thread). */
    private final Map<String, Egg> eggs = new HashMap<>();
    private BukkitTask task;
    private float angle;

    public DragonEggManager(SEssentials plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "floating_dragon_egg");
    }

    public void start() {
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) adopt(e);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    private double range() {
        return plugin.getConfig().getDouble("dragon-egg.range", 16);
    }

    // ------------------------------------------------------------
    // Erzeugen, Wiederfinden, Entfernen
    // ------------------------------------------------------------

    /** Schwebendes Ei an {@code base} (Blockmitte, Bodenhöhe). */
    public void spawn(Location base) {
        String id = UUID.randomUUID().toString();
        World world = base.getWorld();
        double hover = plugin.getConfig().getDouble("dragon-egg.hover-height", 1.5);
        Location eggLoc = base.clone().add(0, hover, 0);

        ItemDisplay item = world.spawn(eggLoc, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.DRAGON_EGG));
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        });
        TextDisplay text = world.spawn(eggLoc.clone().add(0, 0.9, 0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        });
        Interaction hitbox = world.spawn(eggLoc.clone().add(0, -0.5, 0), Interaction.class, d -> {
            d.setInteractionWidth(1f);
            d.setInteractionHeight(1f);
            d.setResponsive(true);
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        });
        eggs.put(id, new Egg(item.getUniqueId(), text.getUniqueId(), hitbox.getUniqueId()));
        update(id, eggs.get(id));
    }

    /** Nach einem Neustart oder Chunk-Laden: Teile eines Eis wieder zuordnen. */
    private void adopt(Entity e) {
        String id = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return;
        Egg old = eggs.getOrDefault(id, new Egg(null, null, null));
        Egg egg = switch (e) {
            case ItemDisplay d -> new Egg(d.getUniqueId(), old.text(), old.hitbox());
            case TextDisplay d -> new Egg(old.item(), d.getUniqueId(), old.hitbox());
            case Interaction d -> new Egg(old.item(), old.text(), d.getUniqueId());
            default -> old;
        };
        eggs.put(id, egg);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) adopt(e);
    }

    private void removeEgg(String id) {
        Egg egg = eggs.remove(id);
        if (egg == null) return;
        for (UUID part : new UUID[]{egg.item(), egg.text(), egg.hitbox()}) {
            Entity e = part == null ? null : Bukkit.getEntity(part);
            if (e != null) e.remove();
        }
    }

    // ------------------------------------------------------------
    // Anzeige
    // ------------------------------------------------------------

    private void tick() {
        angle = (angle + (float) Math.toRadians(18)) % (float) (Math.PI * 2);
        for (Map.Entry<String, Egg> e : List.copyOf(eggs.entrySet())) update(e.getKey(), e.getValue());
    }

    private void update(String id, Egg egg) {
        Entity itemEntity = egg.item() == null ? null : Bukkit.getEntity(egg.item());
        if (!(itemEntity instanceof ItemDisplay item)) return; // Chunk nicht geladen
        if (!item.isValid()) {
            eggs.remove(id);
            return;
        }
        // langsam drehen; die Interpolation macht daraus eine flüssige Bewegung
        item.setInterpolationDelay(0);
        item.setInterpolationDuration(10);
        item.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(angle, 0, 1, 0),
                new Vector3f(1.2f, 1.2f, 1.2f), new AxisAngle4f()));

        if (egg.text() != null && Bukkit.getEntity(egg.text()) instanceof TextDisplay text) {
            int count = nearby(item.getLocation()).size();
            text.text(msg(count == 1 ? "counter-alone" : "counter", null,
                    TextService.placeholder("count", String.valueOf(count)),
                    TextService.placeholder("range", String.valueOf((int) range()))));
        }
    }

    /** Spieler im Umkreis, die zählen: keine Zuschauer und keine unsichtbaren (Vanish) Spieler. */
    private List<Player> nearby(Location center) {
        double r = range();
        List<Player> result = new ArrayList<>();
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || plugin.vanish().isVanished(p)) continue;
            if (p.getLocation().distanceSquared(center) <= r * r) result.add(p);
        }
        return result;
    }

    // ------------------------------------------------------------
    // Einsammeln
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        String id = event.getRightClicked().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        collect(event.getPlayer(), id);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent event) {
        String id = event.getEntity().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return;
        event.setCancelled(true);
        if (event.getDamager() instanceof Player p) collect(p, id);
    }

    private void collect(Player player, String id) {
        Egg egg = eggs.get(id);
        Entity itemEntity = egg == null || egg.item() == null ? null : Bukkit.getEntity(egg.item());
        if (itemEntity == null) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        List<Player> near = nearby(itemEntity.getLocation());
        if (near.size() != 1 || !near.contains(player)) {
            player.sendActionBar(msg("not-alone", player, TextService.placeholder("count", String.valueOf(near.size())),
                    TextService.placeholder("range", String.valueOf((int) range()))));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
            return;
        }
        Location loc = itemEntity.getLocation();
        removeEgg(id);
        player.getInventory().addItem(new ItemStack(Material.DRAGON_EGG))
                .values().forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
        loc.getWorld().spawnParticle(Particle.DRAGON_BREATH, loc, 60, 0.4, 0.4, 0.4, 0.02);
        loc.getWorld().playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1.2f);
        Component announce = msg("collected-broadcast", player);
        if (!announce.equals(Component.empty())) Bukkit.broadcast(announce);
        else player.sendMessage(msg("collected", player));
    }

    // ------------------------------------------------------------
    // Ei nach dem Drachenkampf ersetzen
    // ------------------------------------------------------------

    /**
     * Das Ei erscheint erst nach der Todesanimation auf dem Ausgangsportal. Danach 60 s lang jede Sekunde rund um
     * das Portal nach einem Drachenei-Block suchen und es ersetzen.
     */
    @EventHandler
    public void onDragonDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) return;
        World world = dragon.getWorld();
        int[] tries = {0};
        BukkitTask[] handle = new BukkitTask[1];
        handle[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Block egg = findPortalEgg(world);
            if (egg != null || ++tries[0] >= 60) {
                handle[0].cancel();
                if (egg != null) {
                    egg.setType(Material.AIR);
                    spawn(egg.getLocation().add(0.5, 0, 0.5));
                }
            }
        }, 20L, 20L);
    }

    /** Ausgangsportal steht bei x=0, z=0; das Ei liegt oben auf der Säule. */
    private static Block findPortalEgg(World world) {
        if (world.getEnvironment() != World.Environment.THE_END) return null;
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                Block top = world.getHighestBlockAt(x, z);
                for (int dy = 0; dy <= 2; dy++) {
                    Block b = top.getRelative(0, dy, 0);
                    if (b.getType() == Material.DRAGON_EGG) return b;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    // Befehle: /dragonegg spawn | remove
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(Commands.literal("dragonegg")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_ADMIN))
                        .then(Commands.literal("spawn").executes(ctx -> {
                            Player p = (Player) ctx.getSource().getExecutor();
                            spawn(p.getLocation().toBlockLocation().add(0.5, 0, 0.5));
                            p.sendMessage(msg("spawned", p));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("remove").executes(ctx -> {
                            Player p = (Player) ctx.getSource().getExecutor();
                            String nearest = nearestEgg(p.getLocation(), 10);
                            if (nearest == null) p.sendMessage(msg("none-nearby", p));
                            else {
                                removeEgg(nearest);
                                p.sendMessage(msg("removed", p));
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "Schwebendes Drachenei setzen oder entfernen", List.of("drachenei"));
    }

    private String nearestEgg(Location loc, double max) {
        String best = null;
        double bestDist = max * max;
        for (Map.Entry<String, Egg> e : eggs.entrySet()) {
            Entity item = e.getValue().item() == null ? null : Bukkit.getEntity(e.getValue().item());
            if (item == null || !item.getWorld().equals(loc.getWorld())) continue;
            double d = item.getLocation().distanceSquared(loc);
            if (d <= bestDist) {
                bestDist = d;
                best = e.getKey();
            }
        }
        return best;
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("dragon-egg.messages." + key, ""), player, extra);
    }
}
