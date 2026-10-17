package de.sessentials.trust;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import de.sessentials.SEssentials;
import de.sessentials.text.Durations;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Vertrauens-System: neue Spieler dürfen nicht mit der Welt interagieren (Blöcke, Container, Entities, Eimer),
 * bis genug Spieler per /trust für sie gestimmt haben. Wer abstimmen darf, steuert {@code trust.voters}:
 * alle vertrauten Spieler oder nur Spieler mit {@code sess.trust.vote}.
 * Daten in {@code plugins/SEssentials/trust.yml}. Beim ersten Start gelten alle bisherigen Spieler als vertraut.
 */
public final class TrustManager implements Listener {

    private static final String PERM_USE = "sess.trust";
    private static final String PERM_VOTE = "sess.trust.vote";
    public static final String PERM_ADMIN = "sess.trust.admin";
    static final String PERM_BYPASS = "sess.trust.bypass";

    private final SEssentials plugin;
    private final File file;
    private final Set<UUID> trusted = new HashSet<>();
    /** Kandidat → Spieler, die für ihn gestimmt haben. */
    private final Map<UUID, Set<UUID>> votes = new HashMap<>();
    private boolean permissionVoters;
    private int votesNeeded;
    private boolean autoVote;
    /** Spielzeit, ab der automatisch vertraut wird; {@code null} = aus. */
    private Duration autoTrustPlaytime;

    public TrustManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "trust.yml");
        reload();
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::checkPlaytime), 20L * 60, 20L * 60);
    }

    public void reload() {
        permissionVoters = "permission".equalsIgnoreCase(plugin.getConfig().getString("trust.voters", "trusted"));
        votesNeeded = Math.max(1, plugin.getConfig().getInt("trust.votes-needed", 3));
        autoVote = plugin.getConfig().getBoolean("trust.auto-vote", true);
        String raw = plugin.getConfig().getString("trust.auto-trust-playtime", "0").trim();
        autoTrustPlaytime = raw.equals("0") ? null : Durations.parse(raw);
        if (autoTrustPlaytime == null && !raw.equals("0")) {
            plugin.getLogger().warning("trust.auto-trust-playtime ungültig: '" + raw + "' – kein automatisches Vertrauen.");
        }
    }

    // ------------------------------------------------------------
    // Daten
    // ------------------------------------------------------------

    private void load() {
        if (!file.exists()) {
            // Erster Start: wer schon gespielt hat, soll nicht plötzlich gesperrt sein
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) trusted.add(p.getUniqueId());
            save();
            plugin.getLogger().info("trust.yml angelegt – " + trusted.size() + " bisherige Spieler gelten als vertraut.");
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getStringList("trusted")) parse(id, trusted);
        ConfigurationSection sec = yml.getConfigurationSection("votes");
        if (sec == null) return;
        for (String candidate : sec.getKeys(false)) {
            Set<UUID> voters = new LinkedHashSet<>();
            for (String id : sec.getStringList(candidate)) parse(id, voters);
            try {
                if (!voters.isEmpty()) votes.put(UUID.fromString(candidate), voters);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("trust.yml: ungültige UUID " + candidate);
            }
        }
    }

    private void parse(String id, Set<UUID> into) {
        try {
            into.add(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("trust.yml: ungültige UUID " + id);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("trusted", trusted.stream().map(UUID::toString).toList());
        votes.forEach((candidate, voters) ->
                yml.set("votes." + candidate, voters.stream().map(UUID::toString).toList()));
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "trust.yml konnte nicht gespeichert werden", e);
        }
    }

    public boolean isTrusted(Player player) {
        if (trusted.contains(player.getUniqueId()) || player.hasPermission(PERM_BYPASS)) return true;
        // Fake-Spieler steuern nur Admins
        return plugin.fakePlayers() != null && plugin.fakePlayers().isFake(player);
    }

    /** Spielzeit, die noch bis zum automatischen Vertrauen fehlt; {@code null}, wenn das aus ist. */
    private Duration playtimeLeft(Player player) {
        if (autoTrustPlaytime == null) return null;
        Duration played = Duration.ofSeconds(player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
        return autoTrustPlaytime.minus(played);
    }

    /** Vertraut dem Spieler, sobald er genug Spielzeit hat. */
    private boolean checkPlaytime(Player player) {
        Duration left = playtimeLeft(player);
        if (left == null || left.isPositive() || isTrusted(player)) return false;
        setTrusted(player.getUniqueId(), true);
        Bukkit.broadcast(msg("now-trusted", null, tags(player.getName(), 0)));
        return true;
    }

    /** Darf abstimmen – für Vertrauen und Verdacht ({@code trust.voters}). */
    boolean canVote(Player player) {
        return permissionVoters ? player.hasPermission(PERM_VOTE) : isTrusted(player);
    }

    /** Spieler, die auf Vertrauen warten: online und nicht vertraut, oder mit offenen Stimmen. */
    public List<UUID> candidates() {
        Set<UUID> result = new LinkedHashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (!isTrusted(p)) result.add(p.getUniqueId());
        result.addAll(votes.keySet());
        return List.copyOf(result);
    }

    /** Löscht alle Stimmen für einen Spieler (z.B. bei abgesprochenen Stimmen). */
    void resetVotes(CommandSender sender, OfflinePlayer target) {
        votes.remove(target.getUniqueId());
        save();
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        sender.sendMessage(msg("votes-reset", sender instanceof Player p ? p : null, tags(name, 0)));
    }

    public int votesNeeded() {
        return votesNeeded;
    }

    /** Lesbare Spielzeit fürs automatische Vertrauen, z.B. "5 Stunden", oder "aus". */
    String autoTrustPlaytime() {
        return autoTrustPlaytime == null ? "aus" : Durations.format(autoTrustPlaytime);
    }

    boolean autoVote() {
        return autoVote;
    }

    boolean permissionVoters() {
        return permissionVoters;
    }

    int voteCount(UUID candidate) {
        Set<UUID> v = votes.get(candidate);
        return v == null ? 0 : v.size();
    }

    private void setTrusted(UUID id, boolean trust) {
        votes.remove(id);
        if (trust) trusted.add(id);
        else trusted.remove(id);
        save();
    }

    // ------------------------------------------------------------
    // Nachrichten
    // ------------------------------------------------------------

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("trust.messages." + key, ""), player, extra);
    }

    private TagResolver tags(String target, int count) {
        return TagResolver.resolver(
                TextService.placeholder("target", target),
                TextService.placeholder("votes", String.valueOf(count)),
                TextService.placeholder("needed", String.valueOf(votesNeeded)));
    }

    // ------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("trust")
                        .requires(src -> src.getSender().hasPermission(PERM_USE))
                        .executes(ctx -> {
                            if (ctx.getSource().getExecutor() instanceof Player p) status(p);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("add")
                                .requires(src -> src.getSender().hasPermission(PERM_ADMIN))
                                .then(playerArg().executes(ctx -> {
                                    admin(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "spieler"), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(Commands.literal("remove")
                                .requires(src -> src.getSender().hasPermission(PERM_ADMIN))
                                .then(playerArg().executes(ctx -> {
                                    admin(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "spieler"), false);
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(playerArg()
                                .requires(src -> src.getExecutor() instanceof Player)
                                .executes(ctx -> {
                                    vote((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(),
                "Stimmt dafür, einem neuen Spieler zu vertrauen",
                List.of("vertrauen"));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> playerArg() {
        return Commands.argument("spieler", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    String typed = builder.getRemainingLowerCase();
                    CommandSender sender = ctx.getSource().getSender();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if ((!(sender instanceof Player self) || self.canSee(p)) && p.getName().toLowerCase().startsWith(typed)) {
                            builder.suggest(p.getName());
                        }
                    }
                    return builder.buildFuture();
                });
    }

    private void status(Player player) {
        if (isTrusted(player)) {
            player.sendMessage(msg("status-trusted", player));
        } else {
            player.sendMessage(msg("status-untrusted", player, tags(player.getName(), voteCount(player.getUniqueId()))));
            sendPlaytimeLeft(player);
        }
    }

    private void sendPlaytimeLeft(Player player) {
        Duration left = playtimeLeft(player);
        if (left != null) player.sendMessage(msg("playtime-left", player, TextService.placeholder("time", Durations.format(left))));
    }

    private void vote(Player voter, String targetName) {
        if (!canVote(voter)) {
            voter.sendMessage(msg("no-vote", voter));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            voter.sendMessage(msg("unknown-player", voter, tags(targetName, 0)));
            return;
        }
        UUID id = target.getUniqueId();
        String name = target.getName() != null ? target.getName() : targetName;
        if (id.equals(voter.getUniqueId())) {
            voter.sendMessage(msg("self", voter));
            return;
        }
        Player online = target.getPlayer();
        if (trusted.contains(id) || (online != null && isTrusted(online))) {
            voter.sendMessage(msg("already-trusted", voter, tags(name, 0)));
            return;
        }
        if (!votes.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(voter.getUniqueId())) {
            voter.sendMessage(msg("already-voted", voter, tags(name, voteCount(id))));
            return;
        }

        int count = voteCount(id);
        if (count >= votesNeeded) {
            setTrusted(id, true);
            Bukkit.broadcast(msg("now-trusted", null, tags(name, count)));
            return;
        }
        save();
        voter.sendMessage(msg("voted", voter, tags(name, count)));
        if (online != null) online.sendMessage(msg("received-vote", online, tags(name, count),
                TextService.placeholder("voter", voter.getName())));
    }

    private void admin(CommandSender sender, String targetName, boolean trust) {
        Player self = sender instanceof Player p ? p : null;
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            sender.sendMessage(msg("unknown-player", self, tags(targetName, 0)));
            return;
        }
        setTrustedBy(sender, target, trust);
    }

    /** Vertrauen direkt setzen oder entziehen (Befehl oder Admin-Panel), mit Rückmeldung an beide. */
    void setTrustedBy(CommandSender sender, OfflinePlayer target, boolean trust) {
        Player self = sender instanceof Player p ? p : null;
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        setTrusted(target.getUniqueId(), trust);
        sender.sendMessage(msg(trust ? "added" : "removed", self, tags(name, 0)));
        Player online = target.getPlayer();
        if (online != null && online != sender) online.sendMessage(msg(trust ? "now-trusted-self" : "revoked-self", online, tags(name, 0)));
    }

    // ------------------------------------------------------------
    // Schutz
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isTrusted(player) || checkPlaytime(player)) return;
        TagResolver tags = tags(player.getName(), voteCount(player.getUniqueId()));
        player.sendMessage(msg("join", player, tags));
        sendPlaytimeLeft(player);
        if (!autoVote) return;
        // Abstimmung automatisch starten: alle, die abstimmen dürfen, bekommen einen Klick-Button
        for (Player voter : Bukkit.getOnlinePlayers()) {
            if (voter != player && canVote(voter)) voter.sendMessage(msg("vote-request", voter, tags));
        }
    }

    /** Bricht das Ereignis ab, wenn der Spieler (noch) nicht vertraut ist. */
    private void block(Player player, Cancellable event) {
        if (player == null || isTrusted(player)) return;
        event.setCancelled(true);
        player.sendActionBar(msg("blocked", player));
    }

    /** Spieler hinter einem Angriff, auch per Pfeil o.ä. */
    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        block(event.getPlayer(), event);
    }

    /** Türen, Knöpfe, Container, Druckplatten, Feuerzeug, Spawn-Eier … – alles mit einem Block. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        block(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        block(attacker(event.getDamager()), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        block(attacker(event.getRemover()), event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (event.getAttacker() != null) block(attacker(event.getAttacker()), event);
    }
}
