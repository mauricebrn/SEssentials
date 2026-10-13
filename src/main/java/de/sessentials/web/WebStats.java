package de.sessentials.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.sessentials.SEssentials;
import de.sessentials.bank.BankManager;
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
import java.util.List;

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

        o.addProperty("itemGuardEnabled", plugin.itemGuard().enabled());
        o.addProperty("itemGuardRemoved", plugin.itemGuard().removedSinceStart());
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
        o.addProperty("chestShops", plugin.chestShops().count());

        JsonArray top = new JsonArray();
        for (BankManager.Account a : bank.top(10)) {
            JsonObject j = new JsonObject();
            j.addProperty("name", a.name());
            j.addProperty("balance", a.balance());
            top.add(j);
        }
        o.add("top", top);

        ItemTracker tracker = plugin.tracker();
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

    private JsonArray news() {
        JsonArray arr = new JsonArray();
        for (NewsManager.News n : plugin.news().active()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", n.id);
            j.addProperty("title", PlainTextComponentSerializer.plainText().serialize(plugin.text().render(n.title, null)));
            j.addProperty("priority", n.priority);
            j.addProperty("created", n.created);
            arr.add(j);
        }
        return arr;
    }
}
