package de.sessentials.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import de.sessentials.SEssentials;
import de.sessentials.gamble.GambleOdds;
import de.sessentials.gamble.GambleStats;
import de.sessentials.gamble.LuckyWheel;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Glücksrad-Chancen pro Spieler im Webpanel: lesen (GET) und ändern (POST, nur mit {@code sess.gamble.admin}).
 * Alle Zugriffe auf die Spieldaten laufen im Main-Thread.
 */
final class GamblingOddsApi {

    private static final int MAX_BODY = 8 * 1024;

    private final SEssentials plugin;

    GamblingOddsApi(SEssentials plugin) {
        this.plugin = plugin;
    }

    /** Spielernamen für die Auswahl: alle, die schon gedreht haben, plus wer gerade online ist. */
    void players(HttpExchange ex) throws IOException {
        JsonArray arr = new JsonArray();
        sync(() -> {
            TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (GambleStats.PlayerStats p : wheel().stats().players()) if (p.name != null) names.add(p.name);
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }).forEach(arr::add);
        WebPanel.send(ex, 200, "application/json", arr.toString());
    }

    /** {@code ?player=<name>} → Felder mit Standard- und eigenem Gewicht. */
    void get(HttpExchange ex) throws IOException {
        OfflinePlayer target = target(WebPanel.params(ex.getRequestURI()).get("player"));
        if (target == null) {
            error(ex, 404, "Spieler nicht gefunden.");
            return;
        }
        WebPanel.send(ex, 200, "application/json", sync(() -> describe(target)).toString());
    }

    /** Body {@code {"player": "...", "weights": {"2": 5, ...}}} oder {@code {"player": "...", "reset": true}}. */
    void post(HttpExchange ex, String actor) throws IOException {
        JsonObject body;
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                error(ex, 413, "Anfrage zu groß.");
                return;
            }
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            body = parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            body = null;
        }
        if (body == null || !body.has("player")) {
            error(ex, 400, "Ungültige Anfrage.");
            return;
        }
        OfflinePlayer target = target(body.get("player").getAsString());
        if (target == null) {
            error(ex, 404, "Spieler nicht gefunden.");
            return;
        }
        boolean reset = body.has("reset") && body.get("reset").getAsBoolean();
        Map<String, Integer> weights = new HashMap<>();
        if (!reset) {
            if (!body.has("weights") || !body.get("weights").isJsonObject()) {
                error(ex, 400, "Gewichte fehlen.");
                return;
            }
            try {
                for (var e : body.getAsJsonObject("weights").entrySet()) weights.put(e.getKey(), e.getValue().getAsInt());
            } catch (RuntimeException e) {
                error(ex, 400, "Gewichte müssen ganze Zahlen sein.");
                return;
            }
        }
        JsonObject result = sync(() -> {
            GambleOdds odds = wheel().odds();
            if (reset) odds.resetCustom(target.getUniqueId());
            else if (!odds.setCustom(target.getUniqueId(), weights, wheel().segments())) return null;
            plugin.getLogger().info("Webpanel: " + actor + " hat die Glücksrad-Chancen von " + target.getName()
                    + (reset ? " zurückgesetzt." : " geändert: " + weights));
            return describe(target);
        });
        if (result == null) error(ex, 400, "Gewichte müssen ≥ 0 sein, mindestens eins > 0.");
        else WebPanel.send(ex, 200, "application/json", result.toString());
    }

    private JsonObject describe(OfflinePlayer target) {
        LuckyWheel wheel = wheel();
        UUID id = target.getUniqueId();
        List<LuckyWheel.Segment> segments = wheel.segments();
        Map<String, Integer> base = wheel.odds().baseWeights(id, segments);
        JsonObject o = new JsonObject();
        o.addProperty("player", target.getName());
        o.addProperty("uuid", id.toString());
        o.addProperty("custom", wheel.odds().hasCustom(id));
        JsonArray arr = new JsonArray();
        for (LuckyWheel.Segment seg : segments) {
            JsonObject j = new JsonObject();
            j.addProperty("key", GambleOdds.key(seg));
            j.addProperty("multiplier", seg.multiplier());
            j.addProperty("jackpot", seg.jackpot());
            j.addProperty("defaultWeight", seg.weight());
            j.addProperty("weight", base.get(GambleOdds.key(seg)));
            arr.add(j);
        }
        o.add("segments", arr);
        return o;
    }

    private static OfflinePlayer target(String name) {
        return name == null || name.isBlank() ? null : Bukkit.getOfflinePlayerIfCached(name.trim());
    }

    private LuckyWheel wheel() {
        return plugin.luckyWheel();
    }

    private <T> T sync(Callable<T> task) throws IOException {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("Server antwortet nicht", e);
        }
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        WebPanel.send(ex, status, "application/json", o.toString());
    }
}
