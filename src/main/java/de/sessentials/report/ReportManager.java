package de.sessentials.report;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Report-System: /report (Spieler melden), /reports (Admin-GUI), /clearreports.
 * Reports werden in {@code plugins/SEssentials/reports.yml} gespeichert.
 */
public final class ReportManager implements Listener {

    static final String PERM_ADMIN = "sess.reports.admin";
    private static final String PERM_USE = "sess.report";
    private static final String PERM_BYPASS_COOLDOWN = "sess.report.bypasscooldown";

    static final class Report {
        final int id;
        final String reporter;
        final String reported;
        final String reason;
        final long time;
        /** Zusatzinfos, z.B. vom Client-Detector. Leer bei normalen /report-Meldungen. */
        final List<String> details;
        boolean open;

        Report(int id, String reporter, String reported, String reason, long time, List<String> details, boolean open) {
            this.id = id;
            this.reporter = reporter;
            this.reported = reported;
            this.reason = reason;
            this.time = time;
            this.details = List.copyOf(details);
            this.open = open;
        }
    }

    private final SEssentials plugin;
    private final File file;
    private final ReportMenu menu;
    // nach ID sortiert – älteste Reports zuerst
    private final Map<Integer, Report> reports = new TreeMap<>();
    private final Map<UUID, Long> lastReport = new HashMap<>();
    private int counter;

    public ReportManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "reports.yml");
        this.menu = new ReportMenu(plugin, this);
        load();
    }

    public ReportMenu menu() {
        return menu;
    }

    // ------------------------------------------------------------
    // Daten
    // ------------------------------------------------------------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        counter = yml.getInt("counter");
        ConfigurationSection sec = yml.getConfigurationSection("reports");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(key);
            if (r == null) continue;
            int id = Integer.parseInt(key);
            reports.put(id, new Report(id, r.getString("reporter", "?"), r.getString("reported", "?"),
                    r.getString("reason", ""), r.getLong("time"), r.getStringList("details"),
                    "open".equals(r.getString("status"))));
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("counter", counter);
        for (Report r : reports.values()) {
            String path = "reports." + r.id + ".";
            yml.set(path + "reporter", r.reporter);
            yml.set(path + "reported", r.reported);
            yml.set(path + "reason", r.reason);
            yml.set(path + "time", r.time);
            if (!r.details.isEmpty()) yml.set(path + "details", r.details);
            yml.set(path + "status", r.open ? "open" : "resolved");
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "reports.yml konnte nicht gespeichert werden", e);
        }
    }

    List<Report> openReports() {
        List<Report> open = new ArrayList<>();
        for (Report r : reports.values()) if (r.open) open.add(r);
        return open;
    }

    public int openCount() {
        return (int) reports.values().stream().filter(r -> r.open).count();
    }

    /** Liefert den Report, wenn er noch existiert und offen ist. */
    Report findOpen(int id) {
        Report r = reports.get(id);
        return r != null && r.open ? r : null;
    }

    /**
     * Legt eine automatische Meldung an (z.B. vom Client-Detector). Gibt es schon eine offene
     * Meldung desselben Absenders für denselben Spieler und Grund, wird diese zurückgegeben.
     */
    public int createSystemReport(String reporter, String reported, String reason, List<String> details) {
        for (Report r : reports.values()) {
            if (r.open && r.reporter.equals(reporter) && r.reported.equals(reported) && r.reason.equals(reason)) return r.id;
        }
        Report report = new Report(++counter, reporter, reported, reason, System.currentTimeMillis(), details, true);
        reports.put(report.id, report);
        save();
        return report.id;
    }

    void resolve(Report report) {
        report.open = false;
        save();
    }

    void delete(Report report) {
        reports.remove(report.id);
        save();
    }

    private int clearOpen() {
        List<Report> open = openReports();
        open.forEach(r -> reports.remove(r.id));
        save();
        return open.size();
    }

    // ------------------------------------------------------------
    // Nachrichten
    // ------------------------------------------------------------

    Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("reports.messages." + key), player, extra);
    }

    static TagResolver tags(Report r) {
        return TagResolver.resolver(
                Placeholder.unparsed("id", String.valueOf(r.id)),
                Placeholder.unparsed("target", r.reported),
                Placeholder.unparsed("reporter", r.reporter),
                Placeholder.unparsed("reason", r.reason));
    }

    // ------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("report")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_USE))
                        .then(Commands.argument("spieler", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    Player self = (Player) ctx.getSource().getExecutor();
                                    String typed = builder.getRemainingLowerCase();
                                    for (Player p : Bukkit.getOnlinePlayers()) {
                                        // versteckte (Vanish) Spieler nicht vorschlagen
                                        if (p != self && self.canSee(p) && p.getName().toLowerCase().startsWith(typed)) {
                                            builder.suggest(p.getName());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("grund", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            report((Player) ctx.getSource().getExecutor(),
                                                    StringArgumentType.getString(ctx, "spieler"),
                                                    StringArgumentType.getString(ctx, "grund"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .build(),
                "Meldet einen Spieler",
                List.of("melden"));

        commands.register(
                Commands.literal("reports")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_ADMIN))
                        .executes(ctx -> {
                            menu.openList((Player) ctx.getSource().getExecutor(), 1);
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Öffnet die Report-Übersicht",
                List.of("rlist", "reportadmin"));

        commands.register(
                Commands.literal("clearreports")
                        .requires(src -> src.getSender().hasPermission(PERM_ADMIN))
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            int count = clearOpen();
                            sender.sendMessage(msg("cleared", sender instanceof Player p ? p : null,
                                    Placeholder.unparsed("count", String.valueOf(count))));
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Löscht alle offenen Reports",
                List.of());
    }

    private void report(Player reporter, String targetName, String reason) {
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        TagResolver targetTag = Placeholder.unparsed("target", targetName);
        if (target == null) {
            reporter.sendMessage(msg("unknown-player", reporter, targetTag));
            return;
        }
        if (target.getUniqueId().equals(reporter.getUniqueId())) {
            reporter.sendMessage(msg("self", reporter));
            return;
        }

        long cooldownMillis = Math.max(0L, plugin.getConfig().getLong("reports.cooldown")) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastReport.get(reporter.getUniqueId());
        if (last != null && now - last < cooldownMillis && !reporter.hasPermission(PERM_BYPASS_COOLDOWN)) {
            long remaining = (cooldownMillis - (now - last) + 999) / 1000;
            reporter.sendMessage(msg("cooldown", reporter, Placeholder.unparsed("time", String.valueOf(remaining))));
            return;
        }
        lastReport.put(reporter.getUniqueId(), now);

        String reportedName = target.getName() != null ? target.getName() : targetName;
        Report report = new Report(++counter, reporter.getName(), reportedName, reason, now, List.of(), true);
        reports.put(report.id, report);
        save();

        reporter.sendMessage(msg("sent", reporter, tags(report)));
        Component notify = msg("notify", reporter, tags(report));
        Sound sound = Sound.sound(org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, Sound.Source.MASTER, 1f, 1f);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.notifications().wantsReports(p)) {
                p.sendMessage(notify);
                p.playSound(sound);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Cooldown bleibt über Relogs erhalten, nur abgelaufene Einträge entfernen
        long cooldownMillis = Math.max(0L, plugin.getConfig().getLong("reports.cooldown")) * 1000L;
        Long last = lastReport.get(event.getPlayer().getUniqueId());
        if (last != null && System.currentTimeMillis() - last >= cooldownMillis) {
            lastReport.remove(event.getPlayer().getUniqueId());
        }
    }
}
