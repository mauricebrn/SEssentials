package de.sessentials.fakeplayer;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Fake-Spieler: sichtbar als Mannequin (Spielermodell mit Skin), mit TAB-Eintrag (über PacketEvents)
 * und einschaltbarer KI. Verwaltung mit /fakeplayer; gespeichert in fakeplayers.yml. Der Körper wird mit
 * der Welt gespeichert und über einen PDC-Schlüssel wiedererkannt; stirbt er, entsteht er am Heimatpunkt neu.
 */
public final class FakePlayerManager implements Listener {

    public static final String PERM = "sess.fakeplayer";
    public static final String PERM_IGNORE = "sess.fakeplayer.ignore";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final SEssentials plugin;
    private final NamespacedKey key;
    private final File file;
    private final AsyncFileWriter writer;
    /** Kleingeschriebener Name → Fake-Spieler, in Erstellungsreihenfolge. */
    private final Map<String, FakePlayer> bots = new LinkedHashMap<>();
    /** {@code null}, wenn PacketEvents fehlt – dann gibt es keine TAB-Einträge. */
    private final FakeTab tab;
    private FakePlayer.Tuning tuning;
    private BukkitTask task;
    private long ticks;

    public FakePlayerManager(SEssentials plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "fakeplayer");
        this.file = new File(plugin.getDataFolder(), "fakeplayers.yml");
        this.writer = new AsyncFileWriter("FakePlayers", plugin.getLogger());
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            tab = new FakeTab();
        } else {
            tab = null;
            plugin.getLogger().warning("PacketEvents nicht gefunden – Fake-Spieler erscheinen nicht im TAB.");
        }
        reload();
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void reload() {
        var cfg = plugin.getConfig();
        tuning = new FakePlayer.Tuning(
                Math.max(2, cfg.getDouble("fakeplayers.wander-radius", 12)),
                Math.max(2, cfg.getDouble("fakeplayers.aggro-range", 16)),
                Math.max(1, cfg.getInt("fakeplayers.attack-cooldown-ticks", 12)),
                Math.clamp(cfg.getDouble("fakeplayers.walk-speed", 0.2), 0.05, 0.6),
                Math.clamp(cfg.getDouble("fakeplayers.sprint-speed", 0.28), 0.05, 0.8));
        for (FakePlayer b : bots.values()) {
            Mannequin body = b.body();
            if (body != null) body.setDescription(description());
        }
        if (tab != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                tab.hide(p, bots.values().stream().map(b -> b.profileId).toList());
                tab.show(p, entries(bots.values()));
            }
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
        if (tab != null) {
            List<UUID> ids = bots.values().stream().map(b -> b.profileId).toList();
            for (Player p : Bukkit.getOnlinePlayers()) tab.hide(p, ids);
        }
        save();
        writer.shutdown();
    }

    // ------------------------------------------------------------
    // Ticken
    // ------------------------------------------------------------

    private void tick() {
        ticks++;
        for (FakePlayer b : bots.values()) {
            Mannequin body = b.body();
            if (body != null) {
                b.tick(body, tuning, this::attackable);
            } else if (ticks % 20 == 0) {
                respawnIfMissing(b);
            }
        }
        if (ticks % 1200 == 0) save();
    }

    private boolean attackable(Player p) {
        return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && !p.isInvulnerable() && !plugin.vanish().isVanished(p) && !p.hasPermission(PERM_IGNORE);
    }

    /** Fehlt der Körper, obwohl sein Chunk samt Entities geladen ist (z.B. von einem anderen Plugin entfernt), entsteht er neu. */
    private void respawnIfMissing(FakePlayer b) {
        if (b.respawning) return;
        Location at = b.lastSeen != null ? b.lastSeen : b.home;
        if (at == null || at.getWorld() == null) return;
        World w = at.getWorld();
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        if (!w.getChunkAt(at).isEntitiesLoaded()) return;
        spawnBody(b, b.home != null ? b.home : at, null);
        save();
    }

    // ------------------------------------------------------------
    // Körper & TAB
    // ------------------------------------------------------------

    private void spawnBody(FakePlayer b, Location at, ItemStack[] equipment) {
        Mannequin body = at.getWorld().spawn(FakePlayer.ground(at), Mannequin.class, m -> {
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.customName(Component.text(b.name));
            m.setCustomNameVisible(true);
            m.setDescription(description());
            m.setProfile(profile(b));
            m.getPersistentDataContainer().set(key, PersistentDataType.STRING, b.name);
            if (equipment != null) applyEquipment(m.getEquipment(), equipment);
        });
        b.entityId = body.getUniqueId();
        b.lastSeen = body.getLocation();
        b.clearState();
    }

    private static ResolvableProfile profile(FakePlayer b) {
        if (b.textureValue != null) {
            return ResolvableProfile.resolvableProfile().name(b.name)
                    .addProperty(new ProfileProperty("textures", b.textureValue, b.textureSignature)).build();
        }
        // ohne geladene Skin-Daten löst der Client den Skin selbst über den Namen auf
        return ResolvableProfile.resolvableProfile().name(b.skin).build();
    }

    private Component description() {
        String text = plugin.getConfig().getString("fakeplayers.description", "");
        return text.isBlank() ? null : plugin.text().render(text);
    }

    /** Lädt den Skin (Mojang) im Hintergrund und aktualisiert danach Körper und TAB-Kopf. */
    private void resolveSkin(FakePlayer b) {
        String skin = b.skin;
        ResolvableProfile.resolvableProfile().name(skin).build().resolve().whenComplete((profile, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!skin.equals(b.skin) || !bots.containsValue(b)) return;
                    ProfileProperty textures = error != null || profile == null ? null : profile.getProperties().stream()
                            .filter(p -> p.getName().equals("textures")).findFirst().orElse(null);
                    if (textures == null) {
                        plugin.getLogger().warning("Skin '" + skin + "' für Fake-Spieler " + b.name + " nicht gefunden.");
                        return;
                    }
                    b.textureValue = textures.getValue();
                    b.textureSignature = textures.getSignature();
                    Mannequin body = b.body();
                    if (body != null) body.setProfile(profile(b));
                    refreshTab(b);
                    save();
                }));
    }

    private List<FakeTab.Entry> entries(Iterable<FakePlayer> list) {
        String format = plugin.getConfig().getString("fakeplayers.tab-format", "<name>");
        List<FakeTab.Entry> out = new ArrayList<>();
        for (FakePlayer b : list) {
            out.add(new FakeTab.Entry(b.profileId, b.name, b.textureValue, b.textureSignature, b.ping,
                    plugin.text().render(format, TextService.placeholder("name", b.name))));
        }
        return out;
    }

    private void refreshTab(FakePlayer b) {
        if (tab == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            tab.hide(p, List.of(b.profileId));
            tab.show(p, entries(List.of(b)));
        }
    }

    private static ItemStack[] snapshot(EntityEquipment eq) {
        ItemStack[] armor = eq.getArmorContents();
        ItemStack[] all = Arrays.copyOf(armor, armor.length + 2);
        all[armor.length] = eq.getItemInMainHand();
        all[armor.length + 1] = eq.getItemInOffHand();
        for (int i = 0; i < all.length; i++) all[i] = all[i] == null ? null : all[i].clone();
        return all;
    }

    private static void applyEquipment(EntityEquipment eq, ItemStack[] all) {
        eq.setArmorContents(Arrays.copyOf(all, all.length - 2));
        eq.setItemInMainHand(all[all.length - 2]);
        eq.setItemInOffHand(all[all.length - 1]);
    }

    // ------------------------------------------------------------
    // Events
    // ------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (tab == null) return;
        Player player = event.getPlayer();
        // kurz warten, bis der Client seine eigene Spielerliste hat
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) tab.show(player, entries(bots.values()));
        }, 5L);
    }

    /** Alte Körper (z.B. nach einem Neuerscheinen, während der Chunk entladen war) werden entfernt. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            String name = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            if (name == null) continue;
            FakePlayer b = bots.get(name.toLowerCase(Locale.ROOT));
            if (b == null || !e.getUniqueId().equals(b.entityId)) e.remove();
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        FakePlayer b = byEntity(event.getEntity());
        if (b == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        ItemStack[] equipment = snapshot(event.getEntity().getEquipment());
        b.respawning = true;
        b.entityId = null;
        long delay = Math.max(1, plugin.getConfig().getLong("fakeplayers.respawn-seconds", 5)) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            b.respawning = false;
            if (!bots.containsValue(b) || b.home == null || b.home.getWorld() == null) return;
            spawnBody(b, b.home, equipment);
            save();
        }, delay);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        FakePlayer b = byEntity(event.getEntity());
        if (b == null) return;
        Entity damager = event.getDamager();
        if (damager instanceof Projectile p && p.getShooter() instanceof Entity shooter) damager = shooter;
        if (damager instanceof LivingEntity attacker) b.provoke(attacker);
    }

    private FakePlayer byEntity(Entity e) {
        if (!(e instanceof Mannequin)) return null;
        for (FakePlayer b : bots.values()) {
            if (e.getUniqueId().equals(b.entityId)) return b;
        }
        return null;
    }

    // ------------------------------------------------------------
    // Aktionen
    // ------------------------------------------------------------

    private void create(Player sender, String name, String skin) {
        if (!NAME.matcher(name).matches()) {
            sender.sendMessage(msg("invalid-name", sender, tag("name", name)));
            return;
        }
        if (bots.containsKey(name.toLowerCase(Locale.ROOT)) || Bukkit.getPlayerExact(name) != null) {
            sender.sendMessage(msg("exists", sender, tag("name", name)));
            return;
        }
        int max = plugin.getConfig().getInt("fakeplayers.max", 20);
        if (bots.size() >= max) {
            sender.sendMessage(msg("limit", sender, tag("count", String.valueOf(max))));
            return;
        }
        FakePlayer b = new FakePlayer(name, FakePlayer.profileIdFor(name));
        b.skin = skin != null ? skin : name;
        b.goal = FakePlayer.Goal.WANDER;
        b.home = FakePlayer.ground(sender.getLocation());
        bots.put(name.toLowerCase(Locale.ROOT), b);
        spawnBody(b, b.home, null);
        if (tab != null) for (Player p : Bukkit.getOnlinePlayers()) tab.show(p, entries(List.of(b)));
        resolveSkin(b);
        save();
        sender.sendMessage(msg("created", sender, tag("name", name), tag("skin", b.skin)));
        if (tab == null) sender.sendMessage(msg("no-tab", sender));
    }

    private void remove(CommandSender sender, FakePlayer b) {
        Mannequin body = b.body();
        if (body != null) body.remove();
        bots.remove(b.name.toLowerCase(Locale.ROOT));
        if (tab != null) for (Player p : Bukkit.getOnlinePlayers()) tab.hide(p, List.of(b.profileId));
        save();
        sender.sendMessage(msg("removed", sender, tag("name", b.name)));
    }

    private void list(CommandSender sender) {
        if (bots.isEmpty()) {
            sender.sendMessage(msg("list-empty", sender));
            return;
        }
        sender.sendMessage(msg("list-header", sender, tag("count", String.valueOf(bots.size()))));
        for (FakePlayer b : bots.values()) {
            Mannequin body = b.body();
            Location at = body != null ? body.getLocation() : b.lastSeen != null ? b.lastSeen : b.home;
            sender.sendMessage(msg("list-entry", sender, tag("name", b.name),
                    tag("state", b.ai ? b.goal.label : "KI aus"),
                    tag("location", at == null ? "?" : at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ())));
        }
    }

    private void setAi(CommandSender sender, FakePlayer b, boolean on) {
        b.ai = on;
        b.clearState();
        save();
        sender.sendMessage(msg(on ? "ai-on" : "ai-off", sender, tag("name", b.name), tag("goal", b.goal.label)));
    }

    private void setGoal(CommandSender sender, FakePlayer b, FakePlayer.Goal goal) {
        b.goal = goal;
        b.clearState();
        save();
        sender.sendMessage(msg("goal-set", sender, tag("name", b.name), tag("goal", goal.label)));
    }

    private void setSkin(CommandSender sender, FakePlayer b, String skin) {
        b.skin = skin;
        b.textureValue = null;
        b.textureSignature = null;
        Mannequin body = b.body();
        if (body != null) body.setProfile(profile(b));
        resolveSkin(b);
        save();
        sender.sendMessage(msg("skin-set", sender, tag("name", b.name), tag("skin", skin)));
    }

    /** Kopiert Rüstung und Hand-Items des Absenders (Kopien – das Inventar bleibt unverändert). */
    private void equip(Player sender, FakePlayer b) {
        Mannequin body = b.body();
        if (body == null) {
            sender.sendMessage(msg("not-loaded", sender, tag("name", b.name)));
            return;
        }
        applyEquipment(body.getEquipment(), snapshot(sender.getEquipment()));
        sender.sendMessage(msg("equipped", sender, tag("name", b.name)));
    }

    private void teleportTo(Player sender, FakePlayer b) {
        Mannequin body = b.body();
        Location at = body != null ? body.getLocation() : b.lastSeen != null ? b.lastSeen : b.home;
        if (at == null) return;
        sender.teleportAsync(at);
        sender.sendMessage(msg("teleported", sender, tag("name", b.name)));
    }

    /** Holt den Fake-Spieler zum Absender und setzt dort seinen Heimatpunkt (Mitte fürs Herumlaufen, Respawn). */
    private void bringHere(Player sender, FakePlayer b) {
        b.home = FakePlayer.ground(sender.getLocation());
        b.clearState();
        Mannequin body = b.body();
        if (body != null) body.teleport(b.home);
        else if (!b.respawning) spawnBody(b, b.home, null);
        save();
        sender.sendMessage(msg("moved", sender, tag("name", b.name)));
    }

    // ------------------------------------------------------------
    // Befehl
    // ------------------------------------------------------------

    private final SuggestionProvider<CommandSourceStack> names = (ctx, builder) -> {
        String typed = builder.getRemainingLowerCase();
        for (FakePlayer b : bots.values()) if (b.name.toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(b.name);
        return builder.buildFuture();
    };

    public void register(Commands commands) {
        var root = Commands.literal("fakeplayer").requires(src -> src.getSender().hasPermission(PERM))
                .executes(ctx -> {
                    list(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.literal("list").executes(ctx -> {
                    list(ctx.getSource().getSender());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("create").requires(src -> src.getExecutor() instanceof Player)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    create((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "name"), null);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("skin", StringArgumentType.word()).executes(ctx -> {
                                    create((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "name"),
                                            StringArgumentType.getString(ctx, "skin"));
                                    return Command.SINGLE_SUCCESS;
                                }))))
                .then(withBot("remove", false, (sender, b) -> remove(sender, b)))
                .then(withBot("equip", true, (sender, b) -> equip((Player) sender, b)))
                .then(withBot("tp", true, (sender, b) -> teleportTo((Player) sender, b)))
                .then(withBot("tphere", true, (sender, b) -> bringHere((Player) sender, b)))
                .then(Commands.literal("skin").then(botArgument()
                        .then(Commands.argument("skin", StringArgumentType.word()).executes(ctx -> withBot(ctx,
                                (sender, b) -> setSkin(sender, b, StringArgumentType.getString(ctx, "skin")))))));

        var ai = botArgument();
        ai.then(Commands.literal("on").executes(ctx -> withBot(ctx, (sender, b) -> setAi(sender, b, true))));
        ai.then(Commands.literal("off").executes(ctx -> withBot(ctx, (sender, b) -> setAi(sender, b, false))));
        root.then(Commands.literal("ai").then(ai));

        var goal = botArgument();
        for (FakePlayer.Goal g : FakePlayer.Goal.values()) {
            goal.then(Commands.literal(g.key).executes(ctx -> withBot(ctx, (sender, b) -> setGoal(sender, b, g))));
        }
        root.then(Commands.literal("goal").then(goal));

        commands.register(root.build(), "Fake-Spieler verwalten", List.of("fp", "bot"));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> botArgument() {
        return Commands.argument("name", StringArgumentType.word()).suggests(names);
    }

    private LiteralArgumentBuilder<CommandSourceStack> withBot(String literal, boolean playerOnly, BiConsumer<CommandSender, FakePlayer> action) {
        var node = Commands.literal(literal);
        if (playerOnly) node.requires(src -> src.getExecutor() instanceof Player);
        return node.then(botArgument().executes(ctx -> withBot(ctx, action)));
    }

    private int withBot(CommandContext<CommandSourceStack> ctx, BiConsumer<CommandSender, FakePlayer> action) {
        CommandSender sender = ctx.getSource().getExecutor() instanceof Player p ? p : ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        FakePlayer b = bots.get(name.toLowerCase(Locale.ROOT));
        if (b == null) {
            sender.sendMessage(msg("not-found", sender, tag("name", name)));
            return 0;
        }
        action.accept(sender, b);
        return Command.SINGLE_SUCCESS;
    }

    private static TagResolver tag(String key, String value) {
        return TextService.placeholder(key, value);
    }

    private Component msg(String key, CommandSender sender, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("fakeplayers.messages." + key, ""),
                sender instanceof Player p ? p : null, extra);
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("bots");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(name);
            try {
                FakePlayer b = new FakePlayer(name, FakePlayer.profileIdFor(name));
                b.skin = s.getString("skin", name);
                b.textureValue = s.getString("texture-value");
                b.textureSignature = s.getString("texture-signature");
                String entity = s.getString("entity");
                b.entityId = entity == null ? null : UUID.fromString(entity);
                b.ai = s.getBoolean("ai");
                FakePlayer.Goal goal = FakePlayer.Goal.byKey(s.getString("goal", "wander"));
                b.goal = goal != null ? goal : FakePlayer.Goal.WANDER;
                b.home = s.getLocation("home");
                b.lastSeen = s.getLocation("last-seen");
                bots.put(name.toLowerCase(Locale.ROOT), b);
            } catch (RuntimeException e) {
                // z.B. Welt gelöscht – Eintrag überspringen statt das ganze Feature zu verlieren
                plugin.getLogger().log(Level.WARNING, "Fake-Spieler " + name + " konnte nicht geladen werden", e);
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (FakePlayer b : bots.values()) {
            String p = "bots." + b.name + ".";
            yml.set(p + "skin", b.skin);
            yml.set(p + "texture-value", b.textureValue);
            yml.set(p + "texture-signature", b.textureSignature);
            yml.set(p + "entity", b.entityId == null ? null : b.entityId.toString());
            yml.set(p + "ai", b.ai);
            yml.set(p + "goal", b.goal.key);
            yml.set(p + "home", b.home);
            yml.set(p + "last-seen", b.lastSeen);
        }
        writer.write(file, yml.saveToString());
    }
}
