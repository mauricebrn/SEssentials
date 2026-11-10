package de.sessentials.tag;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.sessentials.SEssentials;
import de.sessentials.hook.PermissionInfo;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.Durations;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eigene Prefixe und Suffixe (/prefix, /suffix) – auch ohne LuckPerms. Umhüllt das Rechte-Plugin: ist für einen
 * Spieler ein eigener Wert gesetzt (und nicht abgelaufen), gilt er statt des LuckPerms-Werts. Weil Chat, TAB und
 * Nametags alle {@code <lp_prefix>}/{@code <lp_suffix>} nutzen, wirkt er überall. Gespeichert in data/tags.yml.
 */
public final class TagOverrides implements PermissionInfo {

    private static final String[] KINDS = {"prefix", "suffix"};

    /** {@code expires = 0} = unbegrenzt. */
    private record Tag(String value, long expires) {
        boolean expired(long now) {
            return expires != 0 && expires <= now;
        }
    }

    private final SEssentials plugin;
    private final PermissionInfo delegate;
    private final File file;
    private final AsyncFileWriter writer;
    /** Wird auch asynchron gelesen (Chat). */
    private final Map<UUID, Tag> prefixes = new ConcurrentHashMap<>();
    private final Map<UUID, Tag> suffixes = new ConcurrentHashMap<>();
    private BukkitTask task;

    public TagOverrides(SEssentials plugin, PermissionInfo delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
        this.file = plugin.dataFile("tags.yml");
        this.writer = new AsyncFileWriter("Tags", plugin.getLogger());
        load();
    }

    /** Räumt abgelaufene Einträge alle 10 s weg und aktualisiert TAB/Nametags der Betroffenen. */
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 200L, 200L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        writer.shutdown();
    }

    // ------------------------------------------------------------
    // PermissionInfo
    // ------------------------------------------------------------

    @Override
    public String prefix(Player player) {
        String own = active(prefixes, player.getUniqueId());
        return own != null ? own : delegate.prefix(player);
    }

    @Override
    public String suffix(Player player) {
        String own = active(suffixes, player.getUniqueId());
        return own != null ? own : delegate.suffix(player);
    }

    @Override public String primaryGroup(Player player) { return delegate.primaryGroup(player); }
    @Override public String meta(Player player, String key) { return delegate.meta(player, key); }
    @Override public void loadUser(UUID id, String name) { delegate.loadUser(id, name); }
    @Override public boolean supportsOwnTag() { return delegate.supportsOwnTag(); }

    @Override
    public void setOwnTag(UUID player, boolean suffix, int priority, String value, boolean oldSuffix, int oldPriority) {
        delegate.setOwnTag(player, suffix, priority, value, oldSuffix, oldPriority);
    }

    /** Aktueller eigener Wert ({@code null} = keiner) – fürs Admin-Menü. */
    String own(String kind, UUID id) {
        return active(map(kind), id);
    }

    private static String active(Map<UUID, Tag> map, UUID id) {
        Tag tag = map.get(id);
        return tag == null || tag.expired(System.currentTimeMillis()) ? null : tag.value();
    }

    // ------------------------------------------------------------
    // Befehle
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(command("prefix").build(), "Eigener Prefix in Chat, TAB und Nametag", List.of());
        commands.register(command("suffix").build(), "Eigener Suffix in Chat, TAB und Nametag", List.of());
    }

    /**
     * /<kind> <text> [spieler] [zeit] und /<kind> reset [spieler]. Der Text darf Leerzeichen und Farbcodes enthalten
     * (mit Leerzeichen am Ende in "Anführungszeichen"); am Ende werden eine Zeitangabe und davor ein bekannter
     * Spielername als optionale Argumente erkannt.
     */
    private LiteralArgumentBuilder<CommandSourceStack> command(String kind) {
        String perm = "sess." + kind;
        return Commands.literal(kind)
                .requires(src -> src.getSender().hasPermission(perm))
                .then(Commands.literal("reset")
                        .executes(ctx -> run(() -> clear(ctx.getSource().getSender(), kind, null)))
                        .then(Commands.argument("spieler", StringArgumentType.word())
                                .suggests(plugin.knownPlayers().suggestions())
                                .executes(ctx -> run(() -> clear(ctx.getSource().getSender(), kind, StringArgumentType.getString(ctx, "spieler"))))))
                .then(Commands.argument("text [spieler] [zeit]", StringArgumentType.greedyString())
                        .executes(ctx -> run(() -> set(ctx.getSource().getSender(), kind,
                                StringArgumentType.getString(ctx, "text [spieler] [zeit]")))));
    }

    private static int run(Runnable action) {
        action.run();
        return Command.SINGLE_SUCCESS;
    }

    /** Eingabe aufteilen: optionaler Spieler und Zeit am Ende, der Rest (ohne äußere Anführungszeichen) ist der Text. */
    record Input(String value, String target, String time) {
        static Input parse(String raw, java.util.function.Predicate<String> isPlayer) {
            String rest = raw.trim();
            String value;
            if (rest.startsWith("\"") && rest.indexOf('"', 1) > 0) {
                int close = rest.indexOf('"', 1);
                value = rest.substring(1, close);
                rest = rest.substring(close + 1).trim();
                String[] parts = rest.isEmpty() ? new String[0] : rest.split("\\s+", 2);
                String target = null, time = null;
                if (parts.length > 0 && Durations.parse(rest) != null) time = rest;
                else if (parts.length > 0) {
                    target = parts[0];
                    time = parts.length > 1 ? parts[1] : null;
                }
                return new Input(value, target, time);
            }
            List<String> words = new java.util.ArrayList<>(List.of(rest.split("\\s+")));
            String time = null, target = null;
            if (words.size() > 1 && Durations.parse(words.getLast()) != null) time = words.removeLast();
            if (words.size() > 1 && isPlayer.test(words.getLast())) target = words.removeLast();
            return new Input(String.join(" ", words), target, time);
        }
    }

    private void set(CommandSender sender, String kind, String raw) {
        set(sender, kind, Input.parse(raw, name -> Bukkit.getOfflinePlayerIfCached(name) != null));
    }

    /** Für das Admin-Menü: Eingabe "<text> [zeit]" für einen festen Spieler. */
    void set(CommandSender sender, String kind, String targetName, String raw) {
        Input in = Input.parse(raw, name -> false);
        set(sender, kind, new Input(in.value(), targetName, in.time()));
    }

    private void set(CommandSender sender, String kind, Input in) {
        String value = in.value();
        String targetName = in.target();
        String time = in.time();
        OfflinePlayer target = target(sender, kind, targetName);
        if (target == null) return;
        Duration duration = null;
        if (time != null) {
            duration = Durations.parse(time);
            if (duration == null) {
                sender.sendMessage(msg("invalid-time", sender, TextService.placeholder("time", time)));
                return;
            }
        }
        int max = plugin.getConfig().getInt("tags.max-length", 64);
        if (value.length() > max) {
            sender.sendMessage(msg("too-long", sender, TextService.placeholder("max", String.valueOf(max))));
            return;
        }
        long expires = duration == null ? 0 : System.currentTimeMillis() + duration.toMillis();
        map(kind).put(target.getUniqueId(), new Tag(value, expires));
        save();
        refresh(target.getUniqueId());
        String name = target.getName() != null ? target.getName() : targetName;
        sender.sendMessage(msg(duration == null ? "set" : "set-timed", sender,
                TextService.placeholder("kind", kind), TextService.placeholder("target", name),
                TextService.placeholder("time", duration == null ? "" : Durations.format(duration)),
                Placeholder.component("value", TextService.parse(value))));
    }

    void clear(CommandSender sender, String kind, String targetName) {
        OfflinePlayer target = target(sender, kind, targetName);
        if (target == null) return;
        String name = target.getName() != null ? target.getName() : targetName;
        if (map(kind).remove(target.getUniqueId()) == null) {
            sender.sendMessage(msg("none", sender, TextService.placeholder("kind", kind), TextService.placeholder("target", name)));
            return;
        }
        save();
        refresh(target.getUniqueId());
        sender.sendMessage(msg("reset", sender, TextService.placeholder("kind", kind), TextService.placeholder("target", name)));
    }

    /** Ziel: angegebener Spieler (braucht .others, auch offline) oder der Absender selbst. */
    private OfflinePlayer target(CommandSender sender, String kind, String targetName) {
        if (targetName == null) {
            if (sender instanceof Player p) return p;
            sender.sendMessage(msg("player-only", sender));
            return null;
        }
        if (!sender.hasPermission("sess." + kind + ".others")
                && !(sender instanceof Player p && p.getName().equalsIgnoreCase(targetName))) {
            sender.sendMessage(msg("no-permission-others", sender, TextService.placeholder("kind", kind)));
            return null;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) sender.sendMessage(msg("unknown-player", sender, TextService.placeholder("target", targetName)));
        return target;
    }

    private Map<UUID, Tag> map(String kind) {
        return kind.equals("suffix") ? suffixes : prefixes;
    }

    private Component msg(String key, CommandSender sender, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("tags.messages." + key, ""),
                sender instanceof Player p ? p : null, extra);
    }

    // ------------------------------------------------------------
    // Ablauf & Speichern
    // ------------------------------------------------------------

    private void expire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map<UUID, Tag> map : List.of(prefixes, suffixes)) {
            for (var it = map.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue().expired(now)) {
                    it.remove();
                    refresh(e.getKey());
                    changed = true;
                }
            }
        }
        if (changed) save();
    }

    /** TAB und Nametag sofort neu zeichnen (Chat liest den Wert bei jeder Nachricht). */
    private void refresh(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null && plugin.tab() != null) plugin.tab().update(online);
    }

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String kind : KINDS) {
            ConfigurationSection sec = yml.getConfigurationSection(kind);
            if (sec == null) continue;
            for (String id : sec.getKeys(false)) {
                String value = sec.getString(id + ".value");
                if (value != null) map(kind).put(UUID.fromString(id), new Tag(value, sec.getLong(id + ".expires")));
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (String kind : KINDS) {
            map(kind).forEach((id, tag) -> {
                yml.set(kind + "." + id + ".value", tag.value());
                yml.set(kind + "." + id + ".expires", tag.expires());
            });
        }
        writer.write(file, yml.saveToString());
    }
}
