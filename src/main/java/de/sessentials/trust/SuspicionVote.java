package de.sessentials.trust;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.text.Durations;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Verdachts-Abstimmung: Spieler stimmen per /suspect gegen einen Online-Spieler; bei genug Stimmen innerhalb
 * von {@code trust.suspicion.votes-expire} wird er über das Strafen-System zeitweise gebannt.
 * Abstimmen darf, wer auch für Vertrauen stimmen darf ({@code trust.voters}). Stimmen in {@code suspicion.yml}.
 */
public final class SuspicionVote {

    private static final String PERM_USE = "sess.trust";
    private static final String PERM_EXEMPT = "sess.punish.exempt";
    private static final String ACTOR = "Verdachts-Abstimmung";

    private final SEssentials plugin;
    private final TrustManager trust;
    private final File file;
    /** Verdächtigter → (Abstimmender → Zeitpunkt der Stimme). */
    private final Map<UUID, Map<UUID, Long>> votes = new HashMap<>();
    private boolean enabled;
    private int votesNeeded;
    private Duration expiry;
    private Duration banDuration;
    private String reason;

    public SuspicionVote(SEssentials plugin, TrustManager trust) {
        this.plugin = plugin;
        this.trust = trust;
        this.file = new File(plugin.getDataFolder(), "suspicion.yml");
        reload();
        load();
    }

    /** Format: {@code votes.<verdächtigter>.<abstimmender>: <zeitpunkt>}. Abgelaufene Stimmen fallen beim Laden weg. */
    private void load() {
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(file).getConfigurationSection("votes");
        if (sec == null) return;
        long now = System.currentTimeMillis();
        for (String target : sec.getKeys(false)) {
            ConfigurationSection voters = sec.getConfigurationSection(target);
            if (voters == null) continue;
            try {
                Map<UUID, Long> against = new HashMap<>();
                for (String voter : voters.getKeys(false)) {
                    long time = voters.getLong(voter);
                    if (now - time < expiry.toMillis()) against.put(UUID.fromString(voter), time);
                }
                if (!against.isEmpty()) votes.put(UUID.fromString(target), against);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("suspicion.yml: ungültige UUID bei " + target);
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        votes.forEach((target, against) -> against.forEach((voter, time) -> yml.set("votes." + target + "." + voter, time)));
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "suspicion.yml konnte nicht gespeichert werden", e);
        }
    }

    public void reload() {
        // Ohne das Strafen-Modul würde der Ban beim Login nicht durchgesetzt
        enabled = plugin.getConfig().getBoolean("trust.suspicion.enabled", false) && plugin.modules().on("punishments");
        votesNeeded = Math.max(1, plugin.getConfig().getInt("trust.suspicion.votes-needed", 3));
        expiry = duration("votes-expire", "30m");
        banDuration = duration("ban-duration", "1d");
        reason = plugin.getConfig().getString("trust.suspicion.reason", "Verdacht (Abstimmung der Spieler)");
    }

    private Duration duration(String key, String fallback) {
        String raw = plugin.getConfig().getString("trust.suspicion." + key, fallback);
        Duration d = Durations.parse(raw);
        if (d != null) return d;
        plugin.getLogger().warning("trust.suspicion." + key + " ungültig: '" + raw + "' – nutze " + fallback + ".");
        return Durations.parse(fallback);
    }

    /** Entfernt abgelaufene Stimmen und Spieler ohne Stimmen. */
    private void prune() {
        long now = System.currentTimeMillis();
        votes.values().forEach(against -> against.values().removeIf(time -> now - time >= expiry.toMillis()));
        votes.values().removeIf(Map::isEmpty);
    }

    /** Offene Abstimmungen fürs Admin-Panel: Verdächtigter → Abstimmende, meiste Stimmen zuerst. */
    public Map<UUID, List<UUID>> open() {
        prune();
        Map<UUID, List<UUID>> result = new LinkedHashMap<>();
        votes.entrySet().stream()
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .forEach(e -> result.put(e.getKey(), List.copyOf(e.getValue().keySet())));
        return result;
    }

    /** Verwirft alle Stimmen gegen einen Spieler (Admin-Panel). */
    void dismiss(CommandSender sender, OfflinePlayer target) {
        votes.remove(target.getUniqueId());
        save();
        String name = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        sender.sendMessage(msg("suspicion-dismissed", sender instanceof Player p ? p : null, tags(name, 0)));
    }

    boolean enabled() {
        return enabled;
    }

    int votesNeeded() {
        return votesNeeded;
    }

    String banDuration() {
        return Durations.format(banDuration);
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("trust.messages." + key, ""), player, extra);
    }

    private TagResolver tags(String target, int count) {
        return TagResolver.resolver(
                TextService.placeholder("target", target),
                TextService.placeholder("votes", String.valueOf(count)),
                TextService.placeholder("needed", String.valueOf(votesNeeded)),
                TextService.placeholder("time", Durations.format(banDuration)));
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("suspect")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_USE))
                        .then(Commands.argument("spieler", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    Player self = (Player) ctx.getSource().getExecutor();
                                    String typed = builder.getRemainingLowerCase();
                                    for (Player p : Bukkit.getOnlinePlayers()) {
                                        if (p != self && self.canSee(p) && p.getName().toLowerCase().startsWith(typed)) builder.suggest(p.getName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    vote((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(),
                "Stimmt dafür, einen verdächtigen Spieler zeitweise zu bannen",
                List.of("verdacht"));
    }

    private void vote(Player voter, String targetName) {
        if (!enabled) {
            voter.sendMessage(msg("suspicion-disabled", voter));
            return;
        }
        if (!trust.canVote(voter)) {
            voter.sendMessage(msg("no-vote", voter));
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !voter.canSee(target)) {
            voter.sendMessage(msg("suspect-offline", voter, tags(targetName, 0)));
            return;
        }
        if (target == voter) {
            voter.sendMessage(msg("suspect-self", voter));
            return;
        }
        if (target.hasPermission(PERM_EXEMPT) || target.hasPermission(TrustManager.PERM_BYPASS)
                || (plugin.fakePlayers() != null && plugin.fakePlayers().isFake(target))) {
            voter.sendMessage(msg("suspect-exempt", voter, tags(target.getName(), 0)));
            return;
        }

        long now = System.currentTimeMillis();
        Map<UUID, Long> against = votes.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>());
        against.values().removeIf(time -> now - time >= expiry.toMillis());
        if (against.putIfAbsent(voter.getUniqueId(), now) != null) {
            voter.sendMessage(msg("already-suspected", voter, tags(target.getName(), against.size())));
            return;
        }

        int count = against.size();
        if (count >= votesNeeded) {
            votes.remove(target.getUniqueId());
            save();
            Bukkit.broadcast(msg("suspect-banned", null, tags(target.getName(), count)));
            plugin.punishCommands().banAutomatic(target.getName(), reason, banDuration, ACTOR);
            return;
        }
        save();
        voter.sendMessage(msg("suspected", voter, tags(target.getName(), count)));
        Component notify = msg("suspicion-notify", null, tags(target.getName(), count), TextService.placeholder("voter", voter.getName()));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != voter && p.hasPermission(TrustManager.PERM_ADMIN)) p.sendMessage(notify);
        }
    }
}
