package de.sessentials.web;

import com.mojang.brigadier.Command;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Webpanel: zeigt Statistiken aus dem Admin-Panel im Browser – nur lesend.
 * Login über einen Einmal-Link aus /webpanel (5 Minuten gültig), danach Sitzung per HttpOnly-Cookie.
 * Die Daten werden regelmäßig im Main-Thread als JSON-Schnappschuss gebaut; Anfragen lesen nur diesen.
 */
public final class WebPanel {

    private static final String PERM = "sess.webpanel";
    private static final String COOKIE = "sess_panel";
    private static final long LINK_MILLIS = 5 * 60 * 1000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Login(UUID player, String name, long expires) {}

    private final SEssentials plugin;
    private final WebStats stats;
    /** Einmal-Token aus /webpanel → wird beim ersten Aufruf verbraucht. {@code player} ist bei der Konsole {@code null}. */
    private final Map<String, Login> pendingLinks = new ConcurrentHashMap<>();
    /** Sitzungs-ID (Cookie) → angemeldeter Admin. */
    private final Map<String, Login> sessions = new ConcurrentHashMap<>();
    private volatile String snapshot = "{}";
    private HttpServer server;
    private BukkitTask refreshTask;

    public WebPanel(SEssentials plugin) {
        this.plugin = plugin;
        this.stats = new WebStats(plugin);
    }

    // ------------------------------------------------------------
    // Start / Stopp
    // ------------------------------------------------------------

    public void start() {
        if (!plugin.getConfig().getBoolean("webpanel.enabled")) return;
        String bind = plugin.getConfig().getString("webpanel.bind", "0.0.0.0");
        int port = plugin.getConfig().getInt("webpanel.port");
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Webpanel: Port " + port + " konnte nicht geöffnet werden", e);
            return;
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> new Thread(r, "SEssentials-Webpanel")));
        server.start();

        long ticks = Math.max(5, plugin.getConfig().getLong("webpanel.refresh-seconds")) * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, ticks);
        plugin.getLogger().info("Webpanel läuft auf " + bind + ":" + port + " – Login im Spiel mit /webpanel.");
        if (plugin.getConfig().getString("webpanel.public-url", "").isBlank()) {
            plugin.getLogger().info("Tipp: webpanel.public-url setzen, damit Login-Links die richtige Adresse enthalten.");
        }
    }

    public void stop() {
        if (refreshTask != null) refreshTask.cancel();
        if (server != null) server.stop(0);
        server = null;
    }

    private void refresh() {
        try {
            snapshot = stats.build().toString();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Webpanel: Statistiken konnten nicht erstellt werden", e);
        }
    }

    // ------------------------------------------------------------
    // /webpanel
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("webpanel")
                        // auch von der Konsole: der Link erscheint dann dort
                        .requires(src -> src.getSender().hasPermission(PERM))
                        .executes(ctx -> {
                            link(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("logout").executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            UUID id = sender instanceof Player p ? p.getUniqueId() : null;
                            sessions.values().removeIf(s -> Objects.equals(s.player(), id));
                            sender.sendMessage(msg("logged-out", sender instanceof Player p ? p : null));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "Erzeugt einen Login-Link für das Webpanel",
                List.of());
    }

    private void link(CommandSender sender) {
        Player player = sender instanceof Player p ? p : null;
        if (server == null) {
            sender.sendMessage(msg("disabled", player));
            return;
        }
        long now = System.currentTimeMillis();
        pendingLinks.values().removeIf(l -> l.expires() < now);
        String token = randomToken();
        pendingLinks.put(token, new Login(player != null ? player.getUniqueId() : null,
                player != null ? player.getName() : "Konsole", now + LINK_MILLIS));
        String url = baseUrl() + "/login?token=" + token;
        sender.sendMessage(msg("link", player, TextService.placeholder("url", url)));
        // in der Konsole ist der Link nicht klickbar – deshalb zusätzlich als Text
        if (player == null) sender.sendMessage(url);
    }

    private String baseUrl() {
        String configured = plugin.getConfig().getString("webpanel.public-url", "").trim();
        if (!configured.isEmpty()) return configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured;
        String ip = Bukkit.getIp().isBlank() ? "localhost" : Bukkit.getIp();
        return "http://" + ip + ":" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            securityHeaders(ex);
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "Method Not Allowed");
                return;
            }
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/login" -> login(ex);
                case "/logout" -> {
                    String sid = sessionId(ex);
                    if (sid != null) sessions.remove(sid);
                    ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
                    redirect(ex, "/");
                }
                case "/style.css" -> resource(ex, "web/style.css", "text/css");
                case "/app.js" -> {
                    if (session(ex) == null) send(ex, 401, "text/plain", "Unauthorized");
                    else resource(ex, "web/app.js", "text/javascript");
                }
                case "/api/stats" -> {
                    Login s = session(ex);
                    if (s == null) send(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
                    else send(ex, 200, "application/json", snapshot);
                }
                case "/" -> resource(ex, session(ex) == null ? "web/login.html" : "web/index.html", "text/html");
                default -> send(ex, 404, "text/plain", "Not Found");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Webpanel: Fehler bei " + ex.getRequestURI(), e);
        }
    }

    private void login(HttpExchange ex) throws IOException {
        String token = query(ex.getRequestURI(), "token");
        Login link = token == null ? null : pendingLinks.remove(token);
        if (link == null || link.expires() < System.currentTimeMillis()) {
            resource(ex, "web/login.html", "text/html");
            return;
        }
        long hours = Math.max(1, plugin.getConfig().getLong("webpanel.session-hours"));
        String sid = randomToken();
        sessions.put(sid, new Login(link.player(), link.name(), System.currentTimeMillis() + hours * 3600_000L));
        boolean https = baseUrl().startsWith("https://");
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + sid + "; Path=/; Max-Age=" + hours * 3600
                + "; HttpOnly; SameSite=Strict" + (https ? "; Secure" : ""));
        plugin.getLogger().info("Webpanel: " + link.name() + " hat sich angemeldet.");
        // Token aus der Adresszeile entfernen
        redirect(ex, "/");
    }

    private Login session(HttpExchange ex) {
        String sid = sessionId(ex);
        if (sid == null) return null;
        Login s = sessions.get(sid);
        if (s != null && s.expires() < System.currentTimeMillis()) {
            sessions.remove(sid);
            return null;
        }
        return s;
    }

    private static String sessionId(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(COOKIE) && !kv[1].isEmpty()) return kv[1];
            }
        }
        return null;
    }

    private static String query(URI uri, String key) {
        String q = uri.getRawQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
    }

    private static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.add("Content-Security-Policy", "default-src 'self'; img-src 'self' https://mc-heads.net; "
                + "style-src 'self'; script-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        h.add("X-Content-Type-Options", "nosniff");
        h.add("X-Frame-Options", "DENY");
        h.add("Referrer-Policy", "no-referrer");
        h.add("Cache-Control", "no-store");
    }

    private void resource(HttpExchange ex, String path, String type) throws IOException {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                send(ex, 404, "text/plain", "Not Found");
                return;
            }
            send(ex, 200, type, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("webpanel.messages." + key), player, extra);
    }
}
