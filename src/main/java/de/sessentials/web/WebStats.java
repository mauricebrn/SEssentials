package de.sessentials.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.sessentials.SEssentials;
import de.sessentials.bank.BankManager;
import de.sessentials.fairplay.FairplayManager;
import de.sessentials.gamble.GambleStats;
import de.sessentials.gamble.LuckyWheel;
import de.sessentials.news.NewsManager;
import de.sessentials.punish.PunishmentManager.Punishment;
import de.sessentials.report.ReportManager.Report;
import de.sessentials.tracker.ItemTracker;
import de.sessentials.tracker.ItemTracker.Snapshot;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Baut den Statistik-Schnappschuss für das Webpanel – nur lesend, im Main-Thread.
 * Enthält bewusst keine IP-Adressen.
 */
final class WebStats {

    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;

    private final SEssentials plugin;

    WebStats(SEssentials plugin) {
        this.plugin = plugin;
    }

    JsonObject build() {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", System.currentTimeMillis());
        root.add("server", server());
        root.add("players", players());
        root.add("moderation", moderation());
        root.add("economy", economy());
        root.add("news", news());
        if (plugin.luckyWheel() != null) root.add("gambling", gambling());
        if (plugin.fairplay() != null) root.add("fairplay", fairplay());
        return root;
    }

    private JsonObject server() {
        JsonObject o = new JsonObject();
        o.addProperty("version", Bukkit.getMinecraftVersion());
        o.addProperty("software", Bukkit.getName() + " " + Bukkit.getVersion());
        o.addProperty("plugin", plugin.getPluginMeta().getVersion());
        o.addProperty("online", Bukkit.getOnlinePlayers().size());
        o.addProperty("onlineVisible", plugin.vanish().visibleCount());
        o.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        o.addProperty("tps", Math.min(20.0, Math.round(Bukkit.getTPS()[0] * 100) / 100.0));
        o.addProperty("mspt", Math.round(Bukkit.getAverageTickTime() * 100) / 100.0);
        o.addProperty("uptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
        o.addProperty("softrestartSeconds", plugin.softRestart().isRunning() ? plugin.softRestart().remainingSeconds() : -1);
        return o;
    }

    private JsonArray players() {
        JsonArray arr = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            o.addProperty("uuid", p.getUniqueId().toString());
            o.addProperty("world", p.getWorld().getName());
            o.addProperty("gamemode", p.getGameMode().name());
            o.addProperty("vanished", plugin.vanish().isVanished(p));
            o.addProperty("ping", p.getPing());
            o.addProperty("balance", plugin.bank().balance(p.getUniqueId()));
            arr.add(o);
        }
        return arr;
    }

    private JsonObject moderation() {
        JsonObject o = new JsonObject();
        JsonArray reports = new JsonArray();
        for (Report r : plugin.reports().openReports()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", r.id);
            j.addProperty("reporter", r.reporter);
            j.addProperty("reported", r.reported);
            j.addProperty("reason", r.reason);
            j.addProperty("time", r.time);
            reports.add(j);
        }
        o.add("openReports", reports);

        JsonArray punishments = new JsonArray();
        for (Punishment p : plugin.punishments().active()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", p.id);
            j.addProperty("type", p.type.label);
            // bei reinen IP-Bans keinen Namen und keine IP zeigen
            j.addProperty("target", p.targetName != null ? p.targetName : "(IP)");
            j.addProperty("reason", p.reason);
            j.addProperty("actor", p.actor);
            j.addProperty("created", p.created);
            j.addProperty("expires", p.expires);
            punishments.add(j);
        }
        o.add("activePunishments", punishments);

        // ausgeschaltete Module zählen als "aus" bzw. 0
        o.addProperty("itemGuardEnabled", plugin.itemGuard() != null && plugin.itemGuard().enabled());
        o.addProperty("itemGuardRemoved", plugin.itemGuard() != null ? plugin.itemGuard().removedSinceStart() : 0);
        long creative = Bukkit.getOnlinePlayers().stream().filter(p -> p.getGameMode() == GameMode.CREATIVE).count();
        o.addProperty("playersInCreative", creative);
        return o;
    }

    private JsonObject economy() {
        BankManager bank = plugin.bank();
        JsonObject o = new JsonObject();
        o.addProperty("currencySingular", bank.currencyName(1));
        o.addProperty("currencyPlural", bank.currencyName(2));
        o.addProperty("total", bank.total());
        o.addProperty("accounts", bank.accounts());
        o.addProperty("chestShops", plugin.chestShops() != null ? plugin.chestShops().count() : 0);

        JsonArray top = new JsonArray();
        for (BankManager.Account a : bank.top(10)) {
            JsonObject j = new JsonObject();
            j.addProperty("name", a.name());
            j.addProperty("balance", a.balance());
            top.add(j);
        }
        o.add("top", top);

        ItemTracker tracker = plugin.tracker();
        if (tracker == null) {
            o.add("trackedItems", new JsonArray());
            o.add("valueHistory", new JsonArray());
            return o;
        }
        List<Snapshot> history = tracker.serverHistory();
        Snapshot latest = history.isEmpty() ? null : history.getLast();
        Snapshot weekAgo = ItemTracker.atOrBefore(history, System.currentTimeMillis() - WEEK);
        JsonArray items = new JsonArray();
        for (Material m : tracker.items()) {
            JsonObject j = new JsonObject();
            j.addProperty("material", m.name());
            j.addProperty("count", latest == null ? 0 : latest.total(m));
            j.addProperty("weekAgo", weekAgo == null ? -1 : weekAgo.total(m));
            j.addProperty("value", latest == null ? 0 : plugin.bankExchange().worth(m) * latest.total(m));
            items.add(j);
        }
        o.add("trackedItems", items);

        JsonArray series = new JsonArray();
        long since = System.currentTimeMillis() - WEEK;
        for (Snapshot s : history) {
            if (s.time() < since) continue;
            JsonObject j = new JsonObject();
            j.addProperty("time", s.time());
            j.addProperty("value", tracker.value(s));
            series.add(j);
        }
        o.add("valueHistory", series);
        return o;
    }

    /** Rangliste, Einstellungen und Verstöße der letzten Zeit (ohne IPs). */
    private JsonObject fairplay() {
        FairplayManager fp = plugin.fairplay();
        var cfg = plugin.getConfig();
        JsonObject o = new JsonObject();
        o.addProperty("startScore", fp.startScore());
        o.addProperty("strikeThreshold", cfg.getInt("fairplay.strike-threshold"));
        o.addProperty("strikesForPunishment", cfg.getInt("fairplay.strikes-for-punishment"));
        o.addProperty("strikeExpireDays", cfg.getLong("fairplay.strike-expire-days"));
        o.addProperty("punishmentTemplate", cfg.getString("fairplay.punishment-template", ""));

        long weekAgo = System.currentTimeMillis() - WEEK;
        JsonArray players = new JsonArray();
        JsonArray recent = new JsonArray();
        List<JsonObject> violations = new ArrayList<>();
        Map<String, Integer> byType = new TreeMap<>();
        long finesWeek = 0;
        for (FairplayManager.Standing s : fp.standings()) {
            JsonObject j = new JsonObject();
            j.addProperty("rank", s.rank());
            j.addProperty("name", s.name());
            j.addProperty("uuid", s.id().toString());
            j.addProperty("score", s.score());
            j.addProperty("strikes", s.strikes());
            j.addProperty("violations", s.history().size());
            players.add(j);
            for (FairplayManager.Violation v : s.history()) {
                JsonObject vj = new JsonObject();
                vj.addProperty("time", v.time());
                vj.addProperty("name", s.name());
                vj.addProperty("type", fp.violationName(v.type()));
                vj.addProperty("points", v.points());
                vj.addProperty("fine", v.fine());
                violations.add(vj);
                if (v.time() >= weekAgo) {
                    byType.merge(fp.violationName(v.type()), 1, Integer::sum);
                    finesWeek += v.fine();
                }
            }
        }
        violations.sort(Comparator.comparingLong((JsonObject j) -> j.get("time").getAsLong()).reversed());
        violations.stream().limit(50).forEach(recent::add);
        JsonObject week = new JsonObject();
        byType.forEach(week::addProperty);
        o.add("players", players);
        o.add("recent", recent);
        o.add("weekByType", week);
        o.addProperty("finesWeek", finesWeek);
        o.addProperty("strikeFine", cfg.getLong("fairplay.strike-fine"));
        return o;
    }

    private JsonObject gambling() {
        LuckyWheel wheel = plugin.luckyWheel();
        GambleStats st = wheel.stats();
        JsonObject o = new JsonObject();
        o.addProperty("pot", plugin.bank().pot());
        o.addProperty("spins", st.spins());
        o.addProperty("staked", st.staked());
        o.addProperty("paidOut", st.paidOut());
        o.addProperty("fees", st.fees());
        o.addProperty("players", st.players().size());
        o.addProperty("expectedReturn", wheel.expectedReturn());

        GambleStats.Spin big = st.biggest();
        if (big != null) o.add("biggest", spin(big));

        JsonArray segments = new JsonArray();
        int totalWeight = wheel.segments().stream().mapToInt(LuckyWheel.Segment::weight).sum();
        for (LuckyWheel.Segment seg : wheel.segments()) {
            JsonObject j = new JsonObject();
            j.addProperty("multiplier", seg.multiplier());
            j.addProperty("jackpot", seg.jackpot());
            j.addProperty("chance", (double) seg.weight() / totalWeight);
            j.addProperty("hits", st.hits(seg.multiplier(), seg.jackpot()));
            segments.add(j);
        }
        o.add("segments", segments);

        JsonArray days = new JsonArray();
        for (Map.Entry<String, GambleStats.Day> e : st.days().entrySet()) {
            GambleStats.Day d = e.getValue();
            JsonObject j = new JsonObject();
            j.addProperty("date", e.getKey());
            j.addProperty("spins", d.spins);
            j.addProperty("staked", d.staked);
            j.addProperty("paidOut", d.paidOut);
            j.addProperty("fees", d.fees);
            j.addProperty("pot", d.pot);
            days.add(j);
        }
        o.add("days", days);

        JsonArray players = new JsonArray();
        st.players().stream()
                .sorted(Comparator.comparingLong((GambleStats.PlayerStats p) -> p.spins).reversed())
                .limit(10)
                .forEach(p -> {
                    JsonObject j = new JsonObject();
                    j.addProperty("name", p.name);
                    j.addProperty("spins", p.spins);
                    j.addProperty("staked", p.staked);
                    j.addProperty("won", p.won);
                    players.add(j);
                });
        o.add("topPlayers", players);

        JsonArray recent = new JsonArray();
        for (GambleStats.Spin sp : st.recent()) recent.add(spin(sp));
        o.add("recent", recent);
        return o;
    }

    private static JsonObject spin(GambleStats.Spin sp) {
        JsonObject j = new JsonObject();
        j.addProperty("time", sp.time());
        j.addProperty("name", sp.name());
        j.addProperty("stake", sp.stake());
        j.addProperty("multiplier", sp.multiplier());
        j.addProperty("jackpot", sp.jackpot());
        j.addProperty("win", sp.win());
        return j;
    }

    private JsonArray news() {
        JsonArray arr = new JsonArray();
        for (NewsManager.News n : plugin.news().active()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", n.id);
            j.addProperty("title", PlainTextComponentSerializer.plainText().serialize(plugin.text().render(n.title)));
            j.addProperty("priority", n.priority);
            j.addProperty("created", n.created);
            arr.add(j);
        }
        return arr;
    }
}
