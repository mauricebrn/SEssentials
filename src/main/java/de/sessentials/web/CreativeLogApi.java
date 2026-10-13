package de.sessentials.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import de.sessentials.SEssentials;
import de.sessentials.creativelog.CreativeLog.Entry;
import de.sessentials.creativelog.CreativeLog.Kind;
import de.sessentials.creativelog.CreativeLog.LogSnapshot;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * CreativeLog-Viewer im Webpanel: Spielerliste, gefilterte Einträge (Spieler, Art, Suche, Zeitraum, Seiten)
 * und CSV-Export. Die Daten werden höchstens alle 5 s im Main-Thread kopiert; gefiltert wird im Web-Thread.
 */
final class CreativeLogApi {

    private static final long CACHE_MILLIS = 5000;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_CSV_ROWS = 50_000;
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Eintrag mit Spieler – flach für Filter und Ausgabe. */
    private record Row(long time, String player, String uuid, Kind kind, String text) {}

    private final SEssentials plugin;
    private volatile List<LogSnapshot> cached = List.of();
    private volatile long cachedAt;

    CreativeLogApi(SEssentials plugin) {
        this.plugin = plugin;
    }

    private List<LogSnapshot> data() {
        if (System.currentTimeMillis() - cachedAt < CACHE_MILLIS) return cached;
        try {
            cached = Bukkit.getScheduler().callSyncMethod(plugin, () -> plugin.creativeLog().snapshot()).get(3, TimeUnit.SECONDS);
            cachedAt = System.currentTimeMillis();
        } catch (Exception e) {
            // Server ausgelastet oder beim Herunterfahren: letzte Kopie weiterverwenden
        }
        return cached;
    }

    // ------------------------------------------------------------
    // Endpunkte
    // ------------------------------------------------------------

    void players(HttpExchange ex) throws IOException {
        JsonArray arr = new JsonArray();
        for (LogSnapshot log : data()) {
            if (log.entries().isEmpty()) continue;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", log.id().toString());
            o.addProperty("name", log.name());
            o.addProperty("entries", log.entries().size());
            o.addProperty("last", log.entries().getLast().time());
            o.addProperty("inCreative", log.inCreativeNow());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("players", arr);
        JsonArray kinds = new JsonArray();
        for (Kind k : Kind.values()) kinds.add(k.name());
        root.add("kinds", kinds);
        WebPanel.send(ex, 200, "application/json", root.toString());
    }

    void entries(HttpExchange ex) throws IOException {
        Map<String, String> q = WebPanel.params(ex.getRequestURI());
        int offset = Math.max(0, parseInt(q.get("offset"), 0));
        int limit = Math.min(MAX_LIMIT, Math.max(1, parseInt(q.get("limit"), 100)));

        Filter filter = Filter.of(q);
        List<Row> rows = filter(filter, true);
        // Zähler pro Art: mit allen Filtern außer der Art-Auswahl – zeigt, was ein Häkchen bringen würde
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        for (Row r : filter(filter, false)) counts.merge(r.kind(), 1, Integer::sum);

        JsonObject root = new JsonObject();
        root.addProperty("total", rows.size());
        root.addProperty("offset", offset);
        root.addProperty("limit", limit);
        JsonObject countJson = new JsonObject();
        for (Kind k : Kind.values()) countJson.addProperty(k.name(), counts.getOrDefault(k, 0));
        root.add("counts", countJson);
        JsonArray arr = new JsonArray();
        for (Row r : rows.subList(Math.min(offset, rows.size()), Math.min(rows.size(), offset + limit))) {
            JsonObject o = new JsonObject();
            o.addProperty("time", r.time());
            o.addProperty("player", r.player());
            o.addProperty("uuid", r.uuid());
            o.addProperty("kind", r.kind().name());
            o.addProperty("text", r.text());
            arr.add(o);
        }
        root.add("entries", arr);
        WebPanel.send(ex, 200, "application/json", root.toString());
    }

    void csv(HttpExchange ex) throws IOException {
        List<Row> rows = filter(Filter.of(WebPanel.params(ex.getRequestURI())), true);
        StringBuilder out = new StringBuilder("﻿Zeit;Spieler;Art;Eintrag\n");
        for (Row r : rows.subList(0, Math.min(rows.size(), MAX_CSV_ROWS))) {
            out.append(cell(CSV_TIME.format(Instant.ofEpochMilli(r.time())))).append(';')
                    .append(cell(r.player())).append(';')
                    .append(cell(r.kind().name())).append(';')
                    .append(cell(r.text())).append('\n');
        }
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"creativelog.csv\"");
        WebPanel.send(ex, 200, "text/csv", out.toString());
    }

    // ------------------------------------------------------------
    // Filter
    // ------------------------------------------------------------

    private record Filter(String player, EnumSet<Kind> kinds, String query, long from, long to) {

        static Filter of(Map<String, String> q) {
            EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
            String kindParam = q.getOrDefault("kinds", "");
            for (String k : kindParam.split(",")) {
                try {
                    if (!k.isBlank()) kinds.add(Kind.valueOf(k.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // unbekannte Art ignorieren
                }
            }
            return new Filter(q.getOrDefault("player", ""), kinds,
                    q.getOrDefault("q", "").trim().toLowerCase(Locale.ROOT),
                    parseLong(q.get("from"), Long.MIN_VALUE), parseLong(q.get("to"), Long.MAX_VALUE));
        }
    }

    /** Alle passenden Einträge, neueste zuerst; {@code withKinds=false} ignoriert die Art-Auswahl. */
    private List<Row> filter(Filter f, boolean withKinds) {
        Predicate<Row> match = r -> r.time() >= f.from() && r.time() <= f.to()
                && (!withKinds || f.kinds().isEmpty() || f.kinds().contains(r.kind()))
                && (f.query().isEmpty() || r.text().toLowerCase(Locale.ROOT).contains(f.query())
                    || r.player().toLowerCase(Locale.ROOT).contains(f.query()));
        List<Row> rows = new ArrayList<>();
        for (LogSnapshot log : data()) {
            if (!f.player().isEmpty() && !f.player().equals(log.id().toString())) continue;
            for (Entry e : log.entries()) {
                Row r = new Row(e.time(), log.name(), log.id().toString(), e.kind(), e.text());
                if (match.test(r)) rows.add(r);
            }
        }
        rows.sort(Comparator.comparingLong(Row::time).reversed());
        return rows;
    }

    /** CSV-Zelle; führende = + - @ werden entschärft, damit Tabellenprogramme keine Formeln ausführen. */
    private static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        try {
            return s == null || s.isBlank() ? fallback : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
