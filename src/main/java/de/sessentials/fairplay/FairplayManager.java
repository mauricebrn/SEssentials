package de.sessentials.fairplay;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
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
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fairplay-Punkte pro Spieler (Start {@code fairplay.start-score}). Verstöße ({@link FairplayListener}) kosten Punkte;
 * fällt der Stand auf {@code strike-threshold} oder darunter, gibt es einen Strike und der Stand beginnt wieder beim
 * Startwert. Bei {@code strikes-for-punishment} aktiven Strikes wird die Strafvorlage {@code punishment-template}
 * vergeben und die Strikes verfallen. Fair spielen bringt mit der Zeit Punkte zurück. Gespeichert in fairplay.yml.
 */
public final class FairplayManager {

    static final String PERM_USE = "sess.fairplay";
    public static final String PERM_ADMIN = "sess.fairplay.admin";
    static final String PERM_NOTIFY = "sess.fairplay.notify";
    static final String PERM_EXEMPT = "sess.fairplay.exempt";
    private static final int HISTORY = 10;

    /** Ein Verstoß; {@code type} = Schlüssel unter {@code fairplay.violations}. */
    /** {@code fine} = tatsächlich abgezogene Geldstrafe; {@code type} "strike" = Geldstrafe für einen Strike. */
    public record Violation(String type, int points, long fine, long time) {}

    /** Stand eines Spielers für Anzeigen (Admin-Panel, Webpanel); {@code rank} ab 1. */
    public record Standing(UUID id, String name, int rank, int score, int strikes, List<Violation> history) {}

    static final class Record {
        int score;
        /** Zeitpunkte der aktiven Strikes (ms). */
        final List<Long> strikes = new ArrayList<>();
        /** Letzte Verstöße, neueste zuletzt. */
        final List<Violation> history = new ArrayList<>();

        Record(int score) {
            this.score = score;
        }
    }

    private final SEssentials plugin;
    private final File file;
    private final AsyncFileWriter writer;
    private final Map<UUID, Record> records = new HashMap<>();
    private final BukkitTask recoveryTask;

    public FairplayManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "fairplay.yml");
        this.writer = new AsyncFileWriter("Fairplay", plugin.getLogger());
        load();
        long ticks = 20L * 60 * Math.max(1, plugin.getConfig().getLong("fairplay.recovery.interval-minutes"));
        recoveryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::recover, ticks, ticks);
    }

    // ------------------------------------------------------------
    // Punkte, Strikes, Strafe
    // ------------------------------------------------------------

    public int startScore() {
        return Math.max(1, plugin.getConfig().getInt("fairplay.start-score"));
    }

    private Record record(UUID id) {
        return records.computeIfAbsent(id, k -> new Record(startScore()));
    }

    /** Verstoß {@code type} für {@code player}: Punkte abziehen, ggf. Strike und Strafe. Punkte ≤ 0 = Verstoß aus. */
    void violate(Player player, String type, TagResolver details) {
        int points = Math.max(0, plugin.getConfig().getInt("fairplay.violations." + type + ".points"));
        long fineAmount = plugin.getConfig().getLong("fairplay.violations." + type + ".fine");
        if ((points == 0 && fineAmount <= 0) || player.hasPermission(PERM_EXEMPT)) return;
        Record r = record(player.getUniqueId());
        r.score -= points;
        long fined = fine(player, fineAmount);
        addHistory(r, new Violation(type, points, fined, System.currentTimeMillis()));

        TagResolver tags = TagResolver.resolver(details, tags(player.getName(), r), fineTag(fined),
                TextService.placeholder("violation", violationName(type)),
                TextService.placeholder("points", String.valueOf(points)));
        if (player.isOnline()) {
            player.sendMessage(msg("violation", player, tags));
            if (fined > 0) player.sendMessage(msg("fined", player, tags));
        }
        notifyStaff("violation-notify", tags);

        if (r.score <= plugin.getConfig().getInt("fairplay.strike-threshold")) strike(player, r);
        save();
    }

    private void strike(Player player, Record r) {
        expireStrikes(r);
        r.strikes.add(System.currentTimeMillis());
        r.score = startScore();
        int needed = plugin.getConfig().getInt("fairplay.strikes-for-punishment");
        long fined = fine(player, plugin.getConfig().getLong("fairplay.strike-fine"));
        if (fined > 0) addHistory(r, new Violation("strike", 0, fined, System.currentTimeMillis()));
        TagResolver tags = TagResolver.resolver(tags(player.getName(), r), fineTag(fined));
        if (player.isOnline()) {
            player.sendMessage(msg("strike", player, tags));
            if (fined > 0) player.sendMessage(msg("fined", player, tags));
        }
        notifyStaff("strike-notify", tags);
        if (needed <= 0 || r.strikes.size() < needed) return;

        String template = plugin.getConfig().getString("fairplay.punishment-template", "");
        if (template.isBlank()) return;
        if (!plugin.modules().on("punishments")) {
            plugin.getLogger().warning("Fairplay: Strafe für " + player.getName() + " fällig, aber das Modul punishments ist aus.");
            return;
        }
        r.strikes.clear();
        // erst nach dem Event strafen: der Kick beim Ban darf nicht mitten im Quit-/Death-Event passieren
        Bukkit.getScheduler().runTask(plugin, () -> plugin.punishCommands().punishAutomatic(player.getName(), template, "Fairplay"));
    }

    /**
     * Geldstrafe vom Bankkonto in den Server-Topf – höchstens so viel, wie da ist (nie ins Minus).
     * Ohne Bank-Modul keine Geldstrafen. Rückgabe: tatsächlich abgezogen.
     */
    private long fine(Player player, long amount) {
        if (amount <= 0 || !plugin.modules().on("bank")) return 0;
        long taken = Math.min(amount, plugin.bank().balance(player.getUniqueId()));
        return taken > 0 && plugin.bank().payServer(player.getUniqueId(), taken) ? taken : 0;
    }

    private TagResolver fineTag(long fined) {
        return TextService.placeholder("fine", plugin.bank().format(fined));
    }

    private static void addHistory(Record r, Violation v) {
        r.history.add(v);
        while (r.history.size() > HISTORY) r.history.removeFirst();
    }

    private void expireStrikes(Record r) {
        long days = plugin.getConfig().getLong("fairplay.strike-expire-days");
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        r.strikes.removeIf(t -> t < cutoff);
    }

    /** Alle online Spieler bekommen {@code recovery.points} zurück, höchstens bis zum Startwert. */
    private void recover() {
        int points = plugin.getConfig().getInt("fairplay.recovery.points");
        if (points <= 0) return;
        boolean changed = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Record r = records.get(p.getUniqueId());
            if (r == null) continue;
            expireStrikes(r);
            if (r.score >= startScore()) continue;
            r.score = Math.min(startScore(), r.score + points);
            changed = true;
        }
        if (changed) save();
    }

    private void reset(CommandSender sender, String name) {
        Player self = sender instanceof Player p ? p : null;
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) {
            sender.sendMessage(msg("unknown-player", self, TextService.placeholder("target", name)));
            return;
        }
        reset(target.getUniqueId());
        sender.sendMessage(msg("reset", self, TextService.placeholder("target", String.valueOf(target.getName()))));
    }

    // ------------------------------------------------------------
    // Anzeige
    // ------------------------------------------------------------

    /** Legt beim Join einen Eintrag an, damit auch Spieler ohne Verstöße in der Rangliste stehen. */
    void ensure(Player player) {
        if (records.containsKey(player.getUniqueId()) || player.hasPermission(PERM_EXEMPT)) return;
        record(player.getUniqueId());
        save();
    }

    /** Rangliste als unveränderliche Kopie – nur im Main-Thread aufrufen. */
    public List<Standing> standings() {
        List<Standing> list = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<UUID, Record> e : ranking()) {
            Record r = e.getValue();
            list.add(new Standing(e.getKey(), nameOf(e.getKey()), rank++, r.score, r.strikes.size(), List.copyOf(r.history)));
        }
        return list;
    }

    /** Admin: Punkte und Strikes zurücksetzen. */
    public void reset(UUID id) {
        records.remove(id);
        save();
    }

    /** Admin: den ältesten aktiven Strike entfernen. {@code false}, wenn keiner da war. */
    public boolean removeStrike(UUID id) {
        Record r = records.get(id);
        if (r == null || r.strikes.isEmpty()) return false;
        r.strikes.removeFirst();
        save();
        return true;
    }

    /** Rangliste: höchster Stand zuerst, bei Gleichstand weniger Strikes. */
    private List<Map.Entry<UUID, Record>> ranking() {
        records.values().forEach(this::expireStrikes);
        List<Map.Entry<UUID, Record>> list = new ArrayList<>(records.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<UUID, Record> e) -> e.getValue().score).reversed()
                .thenComparingInt(e -> e.getValue().strikes.size()));
        return list;
    }

    private void top(CommandSender sender) {
        Player self = sender instanceof Player p ? p : null;
        List<Map.Entry<UUID, Record>> ranking = ranking();
        sender.sendMessage(msg("top-header", self));
        int rank = 1;
        for (Map.Entry<UUID, Record> e : ranking.subList(0, Math.min(10, ranking.size()))) {
            sender.sendMessage(msg("top-entry", self, tags(nameOf(e.getKey()), e.getValue()),
                    TextService.placeholder("rank", String.valueOf(rank++))));
        }
        if (ranking.isEmpty()) sender.sendMessage(msg("top-empty", self));
    }

    private void info(CommandSender sender, String name) {
        Player self = sender instanceof Player p ? p : null;
        OfflinePlayer target = name == null ? self : Bukkit.getOfflinePlayerIfCached(name);
        if (target == null) {
            if (name != null) sender.sendMessage(msg("unknown-player", self, TextService.placeholder("target", name)));
            return;
        }
        Record r = records.get(target.getUniqueId());
        if (r == null) r = new Record(startScore());
        else expireStrikes(r);
        String rank = "–";
        List<Map.Entry<UUID, Record>> ranking = ranking();
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).getKey().equals(target.getUniqueId())) rank = String.valueOf(i + 1);
        }
        String targetName = target.getName() != null ? target.getName() : name;
        sender.sendMessage(msg("info", self, tags(targetName, r), TextService.placeholder("rank", rank)));
        // Verlauf nur fürs Team
        if (!sender.hasPermission(PERM_ADMIN)) return;
        SimpleDateFormat date = new SimpleDateFormat("dd.MM. HH:mm");
        for (Violation v : r.history.reversed()) {
            sender.sendMessage(msg("history-entry", self,
                    TextService.placeholder("violation", violationName(v.type())),
                    TextService.placeholder("points", String.valueOf(v.points())),
                    fineTag(v.fine()),
                    TextService.placeholder("date", date.format(new Date(v.time())))));
        }
    }

    // ------------------------------------------------------------
    // Nachrichten
    // ------------------------------------------------------------

    private TagResolver tags(String name, Record r) {
        return TagResolver.resolver(
                TextService.placeholder("target", name),
                TextService.placeholder("score", String.valueOf(r.score)),
                TextService.placeholder("max", String.valueOf(startScore())),
                TextService.placeholder("strikes", String.valueOf(r.strikes.size())),
                TextService.placeholder("strikes_max", String.valueOf(plugin.getConfig().getInt("fairplay.strikes-for-punishment"))),
                TextService.placeholder("threshold", String.valueOf(plugin.getConfig().getInt("fairplay.strike-threshold"))));
    }

    public String violationName(String type) {
        if (type.equals("strike")) return plugin.getConfig().getString("fairplay.strike-name", "Strike");
        return plugin.getConfig().getString("fairplay.violations." + type + ".name", type);
    }

    private void notifyStaff(String key, TagResolver tags) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(PERM_NOTIFY)) p.sendMessage(msg(key, p, tags));
        }
    }

    Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("fairplay.messages." + key), player, extra);
    }

    private static String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : "?";
    }

    // ------------------------------------------------------------
    // Befehle
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(Commands.literal("fairplay")
                        .requires(src -> src.getSender().hasPermission(PERM_USE))
                        .executes(ctx -> {
                            info(ctx.getSource().getSender(), null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("top").executes(ctx -> {
                            top(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("reset")
                                .requires(src -> src.getSender().hasPermission(PERM_ADMIN))
                                .then(Commands.argument("spieler", StringArgumentType.word()).executes(ctx -> {
                                    reset(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(Commands.argument("spieler", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    for (Player p : Bukkit.getOnlinePlayers()) {
                                        if (p.getName().toLowerCase().startsWith(typed)) builder.suggest(p.getName());
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    info(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(),
                "Fairplay-Punkte und Rangliste",
                List.of());
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("players");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) continue;
            Record r = new Record(sec.getInt("score", startScore()));
            r.strikes.addAll(sec.getLongList("strikes"));
            for (Map<?, ?> v : sec.getMapList("history")) {
                Object fine = v.get("fine");
                r.history.add(new Violation(String.valueOf(v.get("type")), ((Number) v.get("points")).intValue(),
                        fine instanceof Number n ? n.longValue() : 0,
                        ((Number) v.get("time")).longValue()));
            }
            records.put(UUID.fromString(key), r);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        records.forEach((id, r) -> {
            String p = "players." + id + ".";
            yml.set(p + "score", r.score);
            yml.set(p + "strikes", r.strikes);
            List<Map<String, Object>> history = new ArrayList<>();
            for (Violation v : r.history) history.add(Map.of("type", v.type(), "points", v.points(), "fine", v.fine(), "time", v.time()));
            yml.set(p + "history", history);
        });
        writer.write(file, yml.saveToString());
    }

    public void shutdown() {
        recoveryTask.cancel();
        writer.shutdown();
    }
}
