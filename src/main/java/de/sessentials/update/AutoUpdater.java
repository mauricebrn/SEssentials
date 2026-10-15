package de.sessentials.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Automatische Updates aus GitHub-Releases – so, wie {@code .github/workflows/build.yml} sie bei einem Tag
 * {@code v1.2.3} veröffentlicht. Prüft täglich zur eingestellten Uhrzeit das neueste Release; ist es neuer,
 * wird das Jar geladen, per SHA-256 und plugin.yml geprüft und in Paper's Update-Ordner gelegt. Paper ersetzt
 * das Plugin damit beim nächsten Start. Optional startet danach ein Softrestart.
 */
public final class AutoUpdater {

    public static final String PERM = "sess.update";

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern ASSET = Pattern.compile("SEssentials-.+\\.jar");
    private static final long MAX_JAR_BYTES = 50L * 1024 * 1024;
    private static final LocalTime DEFAULT_TIME = LocalTime.of(4, 0);

    /** Ergebnis einer Prüfung: Nachrichten-Schlüssel und Version des Releases (falls bekannt). */
    private record Result(String key, String version, String error) {}

    private final SEssentials plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final AtomicBoolean checking = new AtomicBoolean();
    private BukkitTask task;
    /** Tag der letzten geplanten Prüfung (nur Main-Thread). */
    private LocalDate lastRun;
    /** Version, die im Update-Ordner auf den nächsten Start wartet. */
    private volatile String staged;
    private volatile String lastResult = "Noch nicht geprüft";

    public AutoUpdater(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        if (task != null) task.cancel();
        task = null;
        if (!enabled()) return;
        // nach einem Start oder Einschalten nach der Uhrzeit erst morgen prüfen – sofort geht mit /autoupdate check
        if (!LocalTime.now().isBefore(time())) lastRun = LocalDate.now();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L * 30);
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("auto-update.enabled");
    }

    public void setEnabled(CommandSender sender, boolean on) {
        plugin.getConfig().set("auto-update.enabled", on);
        plugin.saveConfig();
        reload();
        sender.sendMessage(msg(on ? "enabled" : "disabled", sender));
    }

    public LocalTime time() {
        String raw = plugin.getConfig().getString("auto-update.time", "04:00");
        try {
            return LocalTime.parse(raw.length() == 4 ? "0" + raw : raw);
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("auto-update.time '" + raw + "' ist keine Uhrzeit (HH:mm) – nutze 04:00.");
            return DEFAULT_TIME;
        }
    }

    public String repository() {
        return plugin.getConfig().getString("auto-update.repository", "").trim();
    }

    public String lastResult() {
        return lastResult;
    }

    private void tick() {
        LocalDate today = LocalDate.now();
        if (today.equals(lastRun) || LocalTime.now().isBefore(time())) return;
        lastRun = today;
        check(null);
    }

    // ------------------------------------------------------------
    // Prüfen (asynchron)
    // ------------------------------------------------------------

    /** @param requester bekommt das Ergebnis; {@code null} = geplante Prüfung (dann alle mit {@link #PERM}). */
    public void check(CommandSender requester) {
        if (!checking.compareAndSet(false, true)) {
            if (requester != null) requester.sendMessage(msg("busy", requester));
            return;
        }
        if (requester != null) requester.sendMessage(msg("checking", requester));
        // Werte im Main-Thread lesen, Netzwerk und Dateien im Hintergrund
        String repo = repository();
        String token = plugin.getConfig().getString("auto-update.token", "").trim();
        String current = plugin.getPluginMeta().getVersion();
        File target = new File(Bukkit.getUpdateFolderFile(), plugin.jarFile().getName());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Result result;
            try {
                result = run(repo, token, current, target);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Auto-Update fehlgeschlagen", e);
                result = new Result("failed", null, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result = new Result("failed", null, "unterbrochen");
            } finally {
                checking.set(false);
            }
            Result done = result;
            Bukkit.getScheduler().runTask(plugin, () -> finish(requester, done));
        });
    }

    private Result run(String repo, String token, String current, File target) throws IOException, InterruptedException {
        if (!REPOSITORY.matcher(repo).matches()) throw new IOException("auto-update.repository muss 'besitzer/repo' sein");

        HttpResponse<String> res = http.send(api("https://api.github.com/repos/" + repo + "/releases/latest", token)
                .header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 404) return new Result("no-release", null, null);
        if (res.statusCode() != 200) throw new IOException("GitHub antwortet mit HTTP " + res.statusCode());

        JsonObject release = JsonParser.parseString(res.body()).getAsJsonObject();
        String tag = release.get("tag_name").getAsString();
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        String installed = staged != null ? staged : current;
        if (Versions.compare(version, installed) <= 0) return new Result(staged != null ? "already-staged" : "up-to-date", version, null);

        JsonObject asset = null;
        JsonArray assets = release.getAsJsonArray("assets");
        for (JsonElement e : assets) {
            if (ASSET.matcher(e.getAsJsonObject().get("name").getAsString()).matches()) {
                asset = e.getAsJsonObject();
                break;
            }
        }
        if (asset == null) throw new IOException("Release " + tag + " enthält kein SEssentials-*.jar");
        if (asset.get("size").getAsLong() > MAX_JAR_BYTES) throw new IOException("Jar in " + tag + " ist zu groß");

        Path part = target.toPath().resolveSibling(target.getName() + ".part");
        Files.createDirectories(part.getParent());
        try {
            download(asset.get("url").getAsString(), token, part);
            verifyDigest(asset, part);
            verifyJar(part, version);
            Files.move(part, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(part);
        }
        staged = version;
        return new Result("staged", version, null);
    }

    /**
     * Lädt ein Release-Asset über die API-URL (funktioniert auch bei privaten Repos). GitHub leitet auf einen
     * signierten Download-Link um – dem wird ohne Token gefolgt, damit der Token nicht zum Speicher-Host geht.
     */
    private void download(String apiUrl, String token, Path to) throws IOException, InterruptedException {
        HttpResponse<InputStream> res = http.send(api(apiUrl, token).header("Accept", "application/octet-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() == 302 || res.statusCode() == 301 || res.statusCode() == 307) {
            res.body().close();
            String location = res.headers().firstValue("Location").orElseThrow(() -> new IOException("Umleitung ohne Ziel"));
            URI uri = URI.create(location);
            if (!"https".equals(uri.getScheme())) throw new IOException("Download nur über HTTPS");
            res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofInputStream());
        }
        if (res.statusCode() != 200) {
            res.body().close();
            throw new IOException("Download: HTTP " + res.statusCode());
        }
        try (InputStream in = res.body()) {
            Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.size(to) > MAX_JAR_BYTES) throw new IOException("Download ist zu groß");
    }

    private static HttpRequest.Builder api(String url, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SEssentials-AutoUpdater")
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
        return b;
    }

    /** GitHub liefert zu jedem Asset {@code digest: "sha256:…"}; fehlt er, reicht die Prüfung der plugin.yml. */
    private void verifyDigest(JsonObject asset, Path file) throws IOException {
        JsonElement digest = asset.get("digest");
        if (digest == null || digest.isJsonNull()) {
            plugin.getLogger().warning("Auto-Update: Release-Asset ohne SHA-256 – nur plugin.yml wird geprüft.");
            return;
        }
        String expected = digest.getAsString();
        if (!expected.startsWith("sha256:")) throw new IOException("Unbekannte Prüfsumme: " + expected);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) sha.update(buf, 0, n);
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equalsIgnoreCase(expected.substring(7))) throw new IOException("SHA-256 stimmt nicht – Download verworfen");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Das Jar muss dieses Plugin in genau der Release-Version sein. */
    private void verifyJar(Path file, String version) throws IOException {
        try (JarFile jar = new JarFile(file.toFile())) {
            ZipEntry entry = jar.getEntry("plugin.yml");
            if (entry == null) throw new IOException("Jar enthält keine plugin.yml");
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8));
            if (!plugin.getName().equals(yml.getString("name"))) throw new IOException("Jar ist nicht " + plugin.getName());
            if (!version.equals(yml.getString("version"))) {
                throw new IOException("Jar hat Version " + yml.getString("version") + ", Release ist " + version);
            }
        }
    }

    private void finish(CommandSender requester, Result result) {
        TagResolver tags = TagResolver.resolver(
                TextService.placeholder("version", result.version() != null ? result.version() : "?"),
                TextService.placeholder("current", plugin.getPluginMeta().getVersion()),
                TextService.placeholder("error", result.error() != null ? result.error() : ""),
                TextService.placeholder("repository", repository()));
        lastResult = switch (result.key()) {
            case "staged" -> "Version " + result.version() + " bereit (aktiv nach Neustart)";
            case "already-staged" -> "Version " + result.version() + " wartet auf Neustart";
            case "up-to-date" -> "Aktuell (" + plugin.getPluginMeta().getVersion() + ")";
            case "no-release" -> "Kein Release gefunden";
            default -> "Fehler: " + result.error();
        } + " – " + LocalDateTime.now().withNano(0).toString().replace('T', ' ');

        if (result.key().equals("staged")) plugin.getLogger().info("Auto-Update: Version " + result.version() + " liegt im Update-Ordner.");
        else plugin.getLogger().info("Auto-Update: " + lastResult);

        boolean important = result.key().equals("staged") || result.key().equals("failed");
        if (requester != null) {
            requester.sendMessage(msg(result.key(), requester, tags));
        } else if (important) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission(PERM)) p.sendMessage(msg(result.key(), p, tags));
            }
        }

        int minutes = plugin.getConfig().getInt("auto-update.restart-minutes");
        if (result.key().equals("staged") && minutes > 0 && !plugin.softRestart().isRunning()) {
            plugin.softRestart().start(Bukkit.getConsoleSender(), minutes);
        }
    }

    // ------------------------------------------------------------
    // Befehl
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("autoupdate")
                        .requires(src -> src.getSender().hasPermission(PERM))
                        .executes(ctx -> {
                            status(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("check").executes(ctx -> {
                            check(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("an").executes(ctx -> {
                            setEnabled(ctx.getSource().getSender(), true);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("aus").executes(ctx -> {
                            setEnabled(ctx.getSource().getSender(), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "Automatische Plugin-Updates (check, an, aus)",
                List.of());
    }

    private void status(CommandSender sender) {
        sender.sendMessage(msg("status", sender,
                TextService.placeholder("state", enabled() ? "an" : "aus"),
                TextService.placeholder("repository", repository()),
                TextService.placeholder("time", time().toString()),
                TextService.placeholder("current", plugin.getPluginMeta().getVersion()),
                TextService.placeholder("result", lastResult)));
    }

    private Component msg(String key, CommandSender sender, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("auto-update.messages." + key, ""),
                sender instanceof Player p ? p : null, extra);
    }
}
