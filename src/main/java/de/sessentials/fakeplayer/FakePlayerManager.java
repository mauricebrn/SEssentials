package de.sessentials.fakeplayer;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.sessentials.SEssentials;
import de.sessentials.punish.PunishmentManager;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Fake-Spieler als echte Spieler ohne Client: zählen online, stehen im TAB und in /list, laden Chunks,
 * lassen Pflanzen wachsen und Mobs spawnen und sind Ziel aller Befehle (/msg, /ban, /tp, …).
 * Inventar und Position speichert der Server wie bei jedem Spieler; fakeplayers.yml hält Skin, KI und
 * Heimatpunkt. Nutzt Server-Interna (NMS) – nach Minecraft-Updates prüfen.
 */
public final class FakePlayerManager implements Listener {

    public static final String PERM = "sess.fakeplayer";
    public static final String PERM_IGNORE = "sess.fakeplayer.ignore";

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final SEssentials plugin;
    private final File file;
    private final AsyncFileWriter writer;
    /** Kleingeschriebener Name → Fake-Spieler, in Erstellungsreihenfolge. */
    private final Map<String, FakePlayer> bots = new LinkedHashMap<>();
    private final FakeAnimator animator;
    private FakePlayer.Tuning tuning;
    private BukkitTask task;

    public FakePlayerManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "fakeplayers.yml");
        this.writer = new AsyncFileWriter("FakePlayers", plugin.getLogger());
        this.animator = new FakeAnimator(plugin, this);
        // gehört zu diesem Modul – nur angemeldet, wenn es die Fake-Spieler gibt
        Bukkit.getPluginManager().registerEvents(animator, plugin);
        reload();
        load();
        // einen Tick später, damit alle Plugins bereit sind und die Join-Events normal verarbeiten
        Bukkit.getScheduler().runTask(plugin, () -> bots.values().stream().filter(b -> b.online).forEach(this::join));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void reload() {
        var cfg = plugin.getConfig();
        tuning = new FakePlayer.Tuning(
                Math.max(2, cfg.getDouble("fakeplayers.wander-radius", 12)),
                Math.max(2, cfg.getDouble("fakeplayers.aggro-range", 16)),
                Math.max(1, cfg.getInt("fakeplayers.attack-cooldown-ticks", 12)),
                Math.max(2, cfg.getDouble("fakeplayers.defense.flee-distance", 16)),
                Math.max(1, cfg.getInt("fakeplayers.defense.flee-seconds", 10)) * 20,
                Math.max(0, cfg.getDouble("fakeplayers.defense.flee-below-health", 6)));
    }

    /** Beim Deaktivieren: alle Fake-Spieler regulär abmelden (Spielerdaten werden dabei gespeichert). */
    public void shutdown() {
        if (task != null) task.cancel();
        animator.stopAll();
        for (FakePlayer b : bots.values()) disconnect(b, "Server stoppt");
        save();
        writer.shutdown();
    }

    public boolean isFake(Player player) {
        return ((CraftPlayer) player).getHandle() instanceof FakeServerPlayer;
    }

    private void tick() {
        animator.tick();
        for (FakePlayer b : bots.values()) {
            Player body = b.bukkit();
            // animierte Fake-Spieler steuert der Admin, nicht die KI
            if (body != null && !animator.isAnimated(b)) b.tick(body, tuning, this::attackable);
        }
    }

    private boolean attackable(Player p) {
        return (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                && !p.isInvulnerable() && !isFake(p) && !plugin.vanish().isVanished(p) && !p.hasPermission(PERM_IGNORE);
    }

    // ------------------------------------------------------------
    // Beitreten / Verlassen
    // ------------------------------------------------------------

    /** Lässt den Fake-Spieler beitreten. {@code null} bei Erfolg, sonst der Nachrichten-Schlüssel des Grundes. */
    String join(FakePlayer b) {
        if (b.handle != null) return "already-online";
        if (Bukkit.getPlayerExact(b.name) != null) return "name-in-use";
        OfflinePlayer offline = Bukkit.getOfflinePlayer(b.id);
        if (offline.isBanned() || plugin.punishments().activeFor(PunishmentManager.Type.BAN, b.id) != null) return "banned";

        MinecraftServer server = MinecraftServer.getServer();
        PropertyMap properties = b.textureValue == null ? PropertyMap.EMPTY
                : new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", b.textureValue, b.textureSignature)));
        GameProfile profile = new GameProfile(b.id, b.name, properties);
        FakeServerPlayer player;
        // wie Paper beim echten Login (PrepareSpawnTask): gespeicherte Spielerdaten laden, dann Welt und Position bestimmen
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LoggerFactory.getLogger("SEssentials"))) {
            Optional<ValueInput> data = server.getPlayerList().loadPlayerData(new NameAndId(b.id, b.name))
                    .map(tag -> TagValueInput.create(reporter, server.registryAccess(), tag));
            Location home = b.home != null && b.home.getWorld() != null ? FakePlayer.ground(b.home) : null;
            World world = home != null ? home.getWorld() : Bukkit.getWorlds().getFirst();
            ServerPlayer.SavedPosition saved = ServerPlayer.SavedPosition.EMPTY;
            if (data.isPresent() && !b.placeAtHome) {
                World savedWorld = savedWorld(data.get());
                if (savedWorld != null) world = savedWorld;
                saved = data.get().read(ServerPlayer.SavedPosition.MAP_CODEC).orElse(ServerPlayer.SavedPosition.EMPTY);
            }
            player = new FakeServerPlayer(server, ((CraftWorld) world).getHandle(), profile);
            data.ifPresent(player::load);
            if (saved.position().isPresent() && !b.placeAtHome) {
                Vec2 rotation = saved.rotation().orElse(Vec2.ZERO);
                player.snapTo(saved.position().get(), rotation.x, rotation.y);
            } else {
                Location at = home != null ? home : FakePlayer.ground(world.getSpawnLocation());
                player.snapTo(new Vec3(at.getX(), at.getY(), at.getZ()), at.getYaw(), at.getPitch());
            }
        }
        b.handle = player;
        FakeConnection connection = new FakeConnection(details -> {
            // vom Server getrennt (Kick, Ban): im nächsten Tick regulär abmelden
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> removeFromServer(b, player, details));
            else removeFromServer(b, player, details);
        });
        try {
            server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        } catch (RuntimeException e) {
            b.handle = null;
            plugin.getLogger().log(Level.SEVERE, "Fake-Spieler " + b.name + " konnte nicht beitreten", e);
            return "join-failed";
        }
        b.placeAtHome = false;
        // Fake-Spieler sollen nicht verhindern, dass die Nacht durch Schlafen übersprungen wird
        player.getBukkitEntity().setSleepingIgnored(true);
        b.clearState();
        return null;
    }

    /** Welt aus den Bukkit-Daten der Spielerdatei (wie Paper beim Login), {@code null} = unbekannt. */
    private static World savedWorld(ValueInput data) {
        Optional<Long> most = data.getLong("WorldUUIDMost"), least = data.getLong("WorldUUIDLeast");
        if (most.isPresent() && least.isPresent()) return Bukkit.getWorld(new UUID(most.get(), least.get()));
        return data.getString("world").map(Bukkit::getWorld).orElse(null);
    }

    /** Meldet den Fake-Spieler regulär ab (Quit-Event, Spielerdaten speichern). */
    void disconnect(FakePlayer b, String reason) {
        FakeServerPlayer player = b.handle;
        if (player == null) return;
        removeFromServer(b, player, new DisconnectionDetails(net.minecraft.network.chat.Component.literal(reason)));
    }

    private void removeFromServer(FakePlayer b, FakeServerPlayer player, DisconnectionDetails details) {
        if (b.handle != player) return; // schon abgemeldet
        b.handle = null;
        player.connection.onDisconnect(details);
    }

    // ------------------------------------------------------------
    // Events
    // ------------------------------------------------------------

    /** Gekickt oder gebannt: offline bleiben, bis ein Admin /fp join nutzt. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        FakePlayer b = byPlayer(event.getPlayer());
        if (b == null) return;
        b.online = false;
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        FakePlayer b = byPlayer(event.getPlayer());
        if (b != null && b.handle != null && b.handle.getBukkitEntity() == event.getPlayer()) b.handle = null;
    }

    /** Ein echter Spieler mit demselben Namen hat Vorrang: der Fake-Spieler geht. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String name = event.getName().toLowerCase(Locale.ROOT);
        Bukkit.getScheduler().runTask(plugin, () -> {
            FakePlayer b = bots.get(name);
            if (b == null || b.handle == null || b.id.equals(event.getUniqueId())) return;
            plugin.getLogger().warning("Fake-Spieler " + b.name + " verlässt den Server – ein echter Spieler mit dem Namen tritt bei.");
            b.online = false;
            disconnect(b, "Name wird von einem echten Spieler genutzt");
            save();
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        FakePlayer b = byPlayer(event.getPlayer());
        if (b == null) return;
        long delay = Math.max(1, plugin.getConfig().getLong("fakeplayers.respawn-seconds", 5)) * 20L;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && player.isDead()) player.spigot().respawn();
        }, delay);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        FakePlayer b = byPlayer(event.getPlayer());
        if (b != null && b.home != null && b.home.getWorld() != null) event.setRespawnLocation(FakePlayer.ground(b.home));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        FakePlayer b = byPlayer(victim);
        if (b == null) return;
        Entity damager = event.getDamager();
        if (damager instanceof Projectile p && p.getShooter() instanceof Entity shooter) damager = shooter;
        if (damager instanceof LivingEntity attacker) b.attackedBy(attacker, tuning, this::attackable);
    }

    private FakePlayer byPlayer(Player player) {
        FakePlayer b = bots.get(player.getName().toLowerCase(Locale.ROOT));
        return b != null && b.id.equals(player.getUniqueId()) ? b : null;
    }

    // ------------------------------------------------------------
    // Aktionen
    // ------------------------------------------------------------

    private void create(Player sender, String name, String skin) {
        if (!NAME.matcher(name).matches()) {
            sender.sendMessage(msg("invalid-name", sender, tag("name", name)));
            return;
        }
        OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(name);
        if (bots.containsKey(name.toLowerCase(Locale.ROOT)) || Bukkit.getPlayerExact(name) != null
                || known != null && !known.getUniqueId().equals(FakePlayer.idFor(name))) {
            sender.sendMessage(msg("exists", sender, tag("name", name)));
            return;
        }
        int max = plugin.getConfig().getInt("fakeplayers.max", 20);
        if (bots.size() >= max) {
            sender.sendMessage(msg("limit", sender, tag("count", String.valueOf(max))));
            return;
        }
        FakePlayer b = new FakePlayer(name);
        b.skin = skin != null ? skin : name;
        var cfg = plugin.getConfig();
        b.ai = cfg.getBoolean("fakeplayers.defaults.ai", false);
        b.goal = orDefault(FakePlayer.Goal.byKey(cfg.getString("fakeplayers.defaults.goal", "wander")), FakePlayer.Goal.WANDER);
        b.defense = defaultDefense();
        b.home = FakePlayer.ground(sender.getLocation());
        b.placeAtHome = true;
        b.online = true;
        bots.put(name.toLowerCase(Locale.ROOT), b);
        String problem = join(b);
        save();
        if (problem != null) {
            sender.sendMessage(msg(problem, sender, tag("name", name)));
            return;
        }
        resolveSkin(b);
        sender.sendMessage(msg("created", sender, tag("name", name), tag("skin", b.skin)));
    }

    private void remove(CommandSender sender, FakePlayer b) {
        disconnect(b, "Entfernt");
        bots.remove(b.name.toLowerCase(Locale.ROOT));
        save();
        sender.sendMessage(msg("removed", sender, tag("name", b.name)));
    }

    private void joinCommand(CommandSender sender, FakePlayer b) {
        b.online = true;
        String problem = join(b);
        save();
        sender.sendMessage(msg(problem != null ? problem : "joined", sender, tag("name", b.name)));
    }

    private void leaveCommand(CommandSender sender, FakePlayer b) {
        b.online = false;
        disconnect(b, "Verlassen");
        save();
        sender.sendMessage(msg("left", sender, tag("name", b.name)));
    }

    private void list(CommandSender sender) {
        if (bots.isEmpty()) {
            sender.sendMessage(msg("list-empty", sender));
            return;
        }
        sender.sendMessage(msg("list-header", sender, tag("count", String.valueOf(bots.size()))));
        for (FakePlayer b : bots.values()) {
            Player body = b.bukkit();
            Location at = body != null ? body.getLocation() : null;
            sender.sendMessage(msg("list-entry", sender, tag("name", b.name),
                    tag("state", body == null ? "offline" : b.ai ? b.goal.label + ", " + b.defense.label : "KI aus"),
                    tag("location", at == null ? "–" : at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ())));
        }
    }

    private void setAi(CommandSender sender, FakePlayer b, boolean on) {
        b.ai = on;
        if (b.handle != null) b.clearState();
        save();
        sender.sendMessage(msg(on ? "ai-on" : "ai-off", sender, tag("name", b.name), tag("goal", b.goal.label)));
    }

    private void setGoal(CommandSender sender, FakePlayer b, FakePlayer.Goal goal) {
        b.goal = goal;
        if (b.handle != null) b.clearState();
        save();
        sender.sendMessage(msg("goal-set", sender, tag("name", b.name), tag("goal", goal.label)));
    }

    private void setDefense(CommandSender sender, FakePlayer b, FakePlayer.Defense defense) {
        b.defense = defense;
        if (b.handle != null) b.clearState();
        save();
        sender.sendMessage(msg("defense-set", sender, tag("name", b.name), tag("defense", defense.label)));
    }

    private FakePlayer.Defense defaultDefense() {
        return orDefault(FakePlayer.Defense.byKey(plugin.getConfig().getString("fakeplayers.defaults.defense", "flee")), FakePlayer.Defense.FLEE);
    }

    private static <T> T orDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private void setSkin(CommandSender sender, FakePlayer b, String skin) {
        b.skin = skin;
        resolveSkin(b);
        save();
        sender.sendMessage(msg("skin-set", sender, tag("name", b.name), tag("skin", skin)));
    }

    /** Lädt den Skin (Mojang) im Hintergrund, merkt ihn sich und zeigt ihn sofort an. */
    private void resolveSkin(FakePlayer b) {
        String skin = b.skin;
        Bukkit.createProfile(skin).update().whenComplete((profile, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!skin.equals(b.skin) || !bots.containsValue(b)) return;
            ProfileProperty textures = error != null || profile == null ? null : profile.getProperties().stream()
                    .filter(p -> p.getName().equals("textures")).findFirst().orElse(null);
            if (textures == null) {
                plugin.getLogger().warning("Skin '" + skin + "' für Fake-Spieler " + b.name + " nicht gefunden.");
                return;
            }
            b.textureValue = textures.getValue();
            b.textureSignature = textures.getSignature();
            save();
            Player body = b.bukkit();
            if (body != null) {
                PlayerProfile own = body.getPlayerProfile();
                own.setProperty(textures);
                body.setPlayerProfile(own);
            }
        }));
    }

    /** Kopiert Rüstung und Hand-Items des Absenders ins Inventar des Fake-Spielers (Kopien). */
    private void equip(Player sender, FakePlayer b) {
        Player body = b.bukkit();
        if (body == null) {
            sender.sendMessage(msg("offline", sender, tag("name", b.name)));
            return;
        }
        ItemStack[] armor = sender.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) armor[i] = armor[i] == null ? null : armor[i].clone();
        body.getInventory().setArmorContents(armor);
        body.getInventory().setItemInMainHand(sender.getInventory().getItemInMainHand().clone());
        body.getInventory().setItemInOffHand(sender.getInventory().getItemInOffHand().clone());
        sender.sendMessage(msg("equipped", sender, tag("name", b.name)));
    }

    private void openInventory(Player sender, FakePlayer b) {
        Player body = b.bukkit();
        if (body == null) sender.sendMessage(msg("offline", sender, tag("name", b.name)));
        else sender.openInventory(body.getInventory());
    }

    private void teleportTo(Player sender, FakePlayer b) {
        Player body = b.bukkit();
        if (body == null) {
            sender.sendMessage(msg("offline", sender, tag("name", b.name)));
            return;
        }
        sender.teleportAsync(body.getLocation());
        sender.sendMessage(msg("teleported", sender, tag("name", b.name)));
    }

    /** Holt den Fake-Spieler zum Absender und setzt dort seinen Heimatpunkt (Mitte fürs Herumlaufen, Respawn). */
    private void bringHere(Player sender, FakePlayer b) {
        b.home = FakePlayer.ground(sender.getLocation());
        Player body = b.bukkit();
        if (body != null) {
            b.clearState();
            body.teleport(b.home);
        } else {
            b.placeAtHome = true;
        }
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
                .then(withBot("remove", false, this::remove))
                .then(withBot("join", false, this::joinCommand))
                .then(withBot("leave", false, this::leaveCommand))
                .then(withBot("equip", true, (sender, b) -> equip((Player) sender, b)))
                .then(withBot("inv", true, (sender, b) -> openInventory((Player) sender, b)))
                .then(withBot("tp", true, (sender, b) -> teleportTo((Player) sender, b)))
                .then(withBot("tphere", true, (sender, b) -> bringHere((Player) sender, b)))
                .then(Commands.literal("animate").requires(src -> src.getExecutor() instanceof Player)
                        .executes(ctx -> {
                            animator.stop((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(botArgument().executes(ctx -> withBot(ctx, (sender, b) -> animator.toggle((Player) sender, b)))))
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

        var defense = botArgument();
        for (FakePlayer.Defense d : FakePlayer.Defense.values()) {
            defense.then(Commands.literal(d.key).executes(ctx -> withBot(ctx, (sender, b) -> setDefense(sender, b, d))));
        }
        root.then(Commands.literal("defense").then(defense));

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

    Component msg(String key, CommandSender sender, TagResolver... extra) {
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
                FakePlayer b = new FakePlayer(name);
                b.skin = s.getString("skin", name);
                b.textureValue = s.getString("texture-value");
                b.textureSignature = s.getString("texture-signature");
                b.online = s.getBoolean("online", true);
                b.placeAtHome = s.getBoolean("place-at-home");
                b.ai = s.getBoolean("ai");
                FakePlayer.Goal goal = FakePlayer.Goal.byKey(s.getString("goal", "wander"));
                b.goal = goal != null ? goal : FakePlayer.Goal.WANDER;
                b.defense = orDefault(FakePlayer.Defense.byKey(s.getString("defense", "")), defaultDefense());
                b.home = s.getLocation("home");
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
            yml.set(p + "online", b.online);
            yml.set(p + "place-at-home", b.placeAtHome);
            yml.set(p + "ai", b.ai);
            yml.set(p + "goal", b.goal.key);
            yml.set(p + "defense", b.defense.key);
            yml.set(p + "home", b.home);
        }
        writer.write(file, yml.saveToString());
    }
}
