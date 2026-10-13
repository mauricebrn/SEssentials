package de.sessentials.news;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * News: /news (Übersicht), /news read <id> (ganze Nachricht), /newsadmin (Verwaltung).
 * Gespeichert in {@code plugins/SEssentials/news.yml}; gelesene News pro Spieler in dessen Spielerdaten.
 */
public final class NewsManager implements Listener {

    static final String PERM_ADMIN = "sess.news.admin";
    static final int MIN_PRIORITY = 1, MAX_PRIORITY = 4;
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    /** [Text](/befehl) → klickbarer Befehl (Syntax aus dem alten Skript). */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((/[^)]+)\\)");

    enum Status {
        DRAFT("Entwurf"), SCHEDULED("Geplant"), ACTIVE("Aktiv"), EXPIRED("Abgelaufen");

        final String label;

        Status(String label) {
            this.label = label;
        }
    }

    static final class News {
        final int id;
        final long created;
        String title;
        String content;
        int priority;
        Long start;
        Long expires;
        boolean visible;

        News(int id, long created) {
            this.id = id;
            this.created = created;
        }

        Status status(long now) {
            if (!visible) return Status.DRAFT;
            if (start != null && start > now) return Status.SCHEDULED;
            if (expires != null && expires <= now) return Status.EXPIRED;
            return Status.ACTIVE;
        }

        /** Veröffentlichungsdatum: Start, sonst Erstellung. */
        long published() {
            return start != null ? start : created;
        }
    }

    private final SEssentials plugin;
    private final File file;
    private final NamespacedKey seenKey;
    private final NewsMenu menu;
    private final Map<Integer, News> news = new TreeMap<>();
    private int nextId;

    public NewsManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "news.yml");
        this.seenKey = new NamespacedKey(plugin, "news-seen");
        this.menu = new NewsMenu(plugin, this);
        load();
    }

    public NewsMenu menu() {
        return menu;
    }

    // ------------------------------------------------------------
    // Daten
    // ------------------------------------------------------------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = yml.getInt("next-id");
        ConfigurationSection sec = yml.getConfigurationSection("news");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(key);
            if (s == null) continue;
            News n = new News(Integer.parseInt(key), s.getLong("created"));
            n.title = s.getString("title", "");
            n.content = s.getString("content", "");
            n.priority = clampPriority(s.getInt("priority", 2));
            n.start = s.contains("start") ? s.getLong("start") : null;
            n.expires = s.contains("expires") ? s.getLong("expires") : null;
            n.visible = s.getBoolean("visible");
            news.put(n.id, n);
        }
    }

    void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (News n : news.values()) {
            String path = "news." + n.id + ".";
            yml.set(path + "title", n.title);
            yml.set(path + "content", n.content);
            yml.set(path + "priority", n.priority);
            yml.set(path + "created", n.created);
            yml.set(path + "start", n.start);
            yml.set(path + "expires", n.expires);
            yml.set(path + "visible", n.visible);
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "news.yml konnte nicht gespeichert werden", e);
        }
    }

    News create() {
        News n = new News(++nextId, System.currentTimeMillis());
        n.title = "Neue News";
        n.content = "Hier steht der Inhalt. [Alle News](/news)";
        n.priority = 2;
        news.put(n.id, n);
        save();
        return n;
    }

    void delete(News n) {
        news.remove(n.id);
        save();
    }

    News get(int id) {
        return news.get(id);
    }

    /** Alle News, neueste zuerst. */
    List<News> all() {
        return news.values().stream().sorted(Comparator.comparingInt((News n) -> n.id).reversed()).toList();
    }

    /** Aktive News: höchste Priorität zuerst, innerhalb gleicher Priorität neueste zuerst. */
    private List<News> active() {
        long now = System.currentTimeMillis();
        return news.values().stream()
                .filter(n -> n.status(now) == Status.ACTIVE)
                .sorted(Comparator.comparingInt((News n) -> n.priority).thenComparingInt(n -> n.id).reversed())
                .toList();
    }

    static int clampPriority(int p) {
        return Math.max(MIN_PRIORITY, Math.min(MAX_PRIORITY, p));
    }

    // ------------------------------------------------------------
    // Darstellung
    // ------------------------------------------------------------

    Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("news." + key), player, extra);
    }

    Component priority(News n, Player player) {
        return plugin.text().render(plugin.getConfig().getString("news.priorities." + n.priority), player);
    }

    /** Inhalt als MiniMessage: [Text](/befehl) und "\n" umgewandelt; {@code links=false} zeigt nur den Text. */
    private String contentMiniMessage(News n, boolean links) {
        Matcher m = LINK.matcher(n.content.replace("\\n", "<newline>"));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String label = m.group(1), cmd = m.group(2).replace("'", "\\'");
            String replacement = links
                    ? "<click:run_command:'" + cmd + "'><hover:show_text:'<gray>Klicken: " + cmd + "'><aqua><u>" + label + "</u></aqua></hover></click>"
                    : "<aqua>" + label + "</aqua>";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    Component content(News n, Player player) {
        return plugin.text().render(contentMiniMessage(n, true), player);
    }

    /** Einzeilige Kurzfassung ohne Formatierung, auf {@code max} Zeichen gekürzt. */
    String shortText(News n, Player player, int max) {
        String plain = PlainTextComponentSerializer.plainText()
                .serialize(plugin.text().render(contentMiniMessage(n, false), player))
                .replace('\n', ' ');
        return plain.length() > max ? plain.substring(0, max - 3) + "..." : plain;
    }

    TagResolver tags(News n, Player player) {
        return TagResolver.resolver(
                TextService.placeholder("id", String.valueOf(n.id)),
                Placeholder.component("title", plugin.text().render(n.title, player)),
                Placeholder.component("priority", priority(n, player)),
                TextService.placeholder("date", DATE.format(Instant.ofEpochMilli(n.published()))),
                TextService.placeholder("short", shortText(n, player, 45)));
    }

    // ------------------------------------------------------------
    // Spieler-Ansicht
    // ------------------------------------------------------------

    /** Übersicht; {@code silent} = ohne Meldung, wenn es keine News gibt (beim Join). */
    public void feed(Player player, boolean silent) {
        List<News> active = active();
        if (active.isEmpty()) {
            if (!silent) player.sendMessage(msg("messages.none", player));
            return;
        }
        int[] seen = seen(player);
        player.sendMessage(msg("feed.header", player, TextService.placeholder("count", String.valueOf(active.size()))));
        for (News n : active) {
            Component isNew = Arrays.stream(seen).anyMatch(id -> id == n.id) ? Component.empty() : msg("feed.new", player);
            player.sendMessage(msg("feed.entry", player, tags(n, player), Placeholder.component("new", isNew)));
        }
        player.sendMessage(msg("feed.footer", player));
    }

    /** Ganze Nachricht; {@code mark} = als gelesen markieren. */
    void show(Player player, News n, boolean mark) {
        TagResolver tags = tags(n, player);
        player.sendMessage(msg("read.header", player, tags));
        player.sendMessage(msg("read.date", player, tags));
        player.sendMessage(Component.empty());
        player.sendMessage(content(n, player));
        player.sendMessage(Component.empty());
        player.sendMessage(msg("read.footer", player, tags));
        if (mark) markSeen(player, n.id);
    }

    private int[] seen(Player player) {
        return player.getPersistentDataContainer().getOrDefault(seenKey, PersistentDataType.INTEGER_ARRAY, new int[0]);
    }

    private void markSeen(Player player, int id) {
        int[] old = seen(player);
        if (Arrays.stream(old).anyMatch(i -> i == id)) return;
        // gelöschte News dabei aufräumen, damit die Liste nicht wächst
        int[] updated = java.util.stream.IntStream.concat(Arrays.stream(old).filter(news::containsKey), java.util.stream.IntStream.of(id)).toArray();
        player.getPersistentDataContainer().set(seenKey, PersistentDataType.INTEGER_ARRAY, updated);
    }

    private void read(Player player, int id) {
        News n = news.get(id);
        boolean admin = player.hasPermission(PERM_ADMIN);
        if (n == null) {
            player.sendMessage(msg(admin ? "messages.not-found" : "messages.not-available", player));
            return;
        }
        if (n.status(System.currentTimeMillis()) != Status.ACTIVE && !admin) {
            player.sendMessage(msg("messages.not-available", player));
            return;
        }
        show(player, n, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("news.show-on-join")) return;
        Player player = event.getPlayer();
        long delay = Math.max(1L, plugin.getConfig().getLong("news.join-delay-seconds")) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) feed(player, true);
        }, delay);
    }

    // ------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------

    public void register(Commands commands) {
        var readNode = Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(ctx -> {
                    read((Player) ctx.getSource().getExecutor(), IntegerArgumentType.getInteger(ctx, "id"));
                    return Command.SINGLE_SUCCESS;
                });
        commands.register(
                Commands.literal("news")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(ctx -> {
                            feed((Player) ctx.getSource().getExecutor(), false);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("read").then(readNode))
                        .then(Commands.literal("lesen").then(readNode))
                        .build(),
                "Zeigt die aktuellen News",
                List.of("neuigkeiten"));

        commands.register(
                Commands.literal("newsadmin")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_ADMIN))
                        .executes(ctx -> {
                            menu.openPanel((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("edit").then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    Player p = (Player) ctx.getSource().getExecutor();
                                    News n = news.get(IntegerArgumentType.getInteger(ctx, "id"));
                                    if (n == null) p.sendMessage(msg("messages.not-found", p));
                                    else menu.openEditor(p, n);
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(Commands.literal("cancel").executes(ctx -> {
                            plugin.chatPrompt().cancel((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "News-Verwaltung",
                List.of());
    }
}
