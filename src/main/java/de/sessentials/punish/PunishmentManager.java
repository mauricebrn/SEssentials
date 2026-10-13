package de.sessentials.punish;

import de.sessentials.SEssentials;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.Durations;
import de.sessentials.text.TextService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Strafen: Bans, Mutes und IP-Bans – zeitlich begrenzt oder permanent, frei oder aus Vorlagen (IDs in der Config).
 * Thread-sicher, weil Login-Prüfung und Chat asynchron laufen. Gespeichert in punishments.yml.
 */
public final class PunishmentManager implements Listener {

    public enum Type {
        BAN("Ban", "gebannt"), MUTE("Mute", "gemutet"), IPBAN("IP-Ban", "IP-gebannt");

        public final String label;
        public final String verb;

        Type(String label, String verb) {
            this.label = label;
            this.verb = verb;
        }

        static Type parse(String name) {
            return switch (name == null ? "" : name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")) {
                case "ban" -> BAN;
                case "mute" -> MUTE;
                case "ipban" -> IPBAN;
                default -> null;
            };
        }
    }

    /** Eine Strafe. {@code target} ist bei reinen IP-Bans {@code null}; {@code expires} -1 = permanent. */
    public static final class Punishment {
        public final int id;
        public final Type type;
        public final UUID target;
        public final String targetName;
        public final String ip;
        public final String reason;
        public final String actor;
        public final long created;
        public final long expires;
        public final String template;
        boolean active = true;
        String revokedBy;
        long revokedAt;

        Punishment(int id, Type type, UUID target, String targetName, String ip, String reason, String actor,
                   long created, long expires, String template) {
            this.id = id;
            this.type = type;
            this.target = target;
            this.targetName = targetName;
            this.ip = ip;
            this.reason = reason;
            this.actor = actor;
            this.created = created;
            this.expires = expires;
            this.template = template;
        }

        public boolean isActive(long now) {
            return active && (expires < 0 || expires > now);
        }

        public boolean permanent() {
            return expires < 0;
        }
    }

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final SEssentials plugin;
    private final File file;
    private final AsyncFileWriter writer;
    private final List<Punishment> all = new ArrayList<>();
    /** Letzte bekannte IP pro Spieler – für IP-Bans von Offline-Spielern. */
    private final Map<UUID, String> lastIp = new HashMap<>();
    private int nextId;

    public PunishmentManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "punishments.yml");
        this.writer = new AsyncFileWriter("Punishments", plugin.getLogger());
        load();
    }

    public void shutdown() {
        writer.shutdown();
    }

    // ------------------------------------------------------------
    // Abfragen
    // ------------------------------------------------------------

    public synchronized Punishment activeFor(Type type, UUID target) {
        long now = System.currentTimeMillis();
        for (Punishment p : all) if (p.type == type && target.equals(p.target) && p.isActive(now)) return p;
        return null;
    }

    public synchronized Punishment activeIpBan(String ip) {
        long now = System.currentTimeMillis();
        for (Punishment p : all) if (p.type == Type.IPBAN && ip.equals(p.ip) && p.isActive(now)) return p;
        return null;
    }

    public synchronized List<Punishment> active() {
        long now = System.currentTimeMillis();
        return all.stream().filter(p -> p.isActive(now)).sorted(Comparator.comparingInt((Punishment p) -> p.id).reversed()).toList();
    }

    public synchronized List<Punishment> history(UUID target) {
        return all.stream().filter(p -> target.equals(p.target)).sorted(Comparator.comparingInt((Punishment p) -> p.id).reversed()).toList();
    }

    public synchronized String lastIp(UUID player) {
        return lastIp.get(player);
    }

    // ------------------------------------------------------------
    // Strafen vergeben und aufheben
    // ------------------------------------------------------------

    /** Neue Strafe; eine aktive Strafe gleichen Typs für dasselbe Ziel wird dabei ersetzt. */
    public synchronized Punishment punish(Type type, UUID target, String targetName, String ip, String reason,
                                          String actor, Duration duration, String template) {
        long now = System.currentTimeMillis();
        for (Punishment p : all) {
            boolean sameTarget = type == Type.IPBAN ? ip != null && ip.equals(p.ip) : target != null && target.equals(p.target);
            if (p.type == type && sameTarget && p.isActive(now)) {
                p.active = false;
                p.revokedBy = actor + " (ersetzt)";
                p.revokedAt = now;
            }
        }
        Punishment p = new Punishment(++nextId, type, target, targetName, ip, reason, actor, now,
                duration == null ? -1 : now + duration.toMillis(), template);
        all.add(p);
        save();
        return p;
    }

    /** Hebt die aktive Strafe auf; {@code null}, wenn keine aktiv war. */
    public synchronized Punishment revoke(Punishment p, String actor) {
        if (p == null || !p.isActive(System.currentTimeMillis())) return null;
        p.active = false;
        p.revokedBy = actor;
        p.revokedAt = System.currentTimeMillis();
        save();
        return p;
    }

    public synchronized Punishment byId(int id) {
        for (Punishment p : all) if (p.id == id) return p;
        return null;
    }

    // ------------------------------------------------------------
    // Durchsetzen
    // ------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        Punishment ban = activeFor(Type.BAN, event.getUniqueId());
        if (ban == null) ban = activeIpBan(event.getAddress().getHostAddress());
        if (ban != null) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen(ban));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        InetSocketAddress address = event.getPlayer().getAddress();
        if (address == null) return;
        String ip = address.getAddress().getHostAddress();
        synchronized (this) {
            if (!ip.equals(lastIp.put(event.getPlayer().getUniqueId(), ip))) save();
        }
    }

    // LOW: nach ChatPrompt (LOWEST), damit Admin-Eingaben in Menüs nicht als Chat geblockt werden
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Punishment mute = activeFor(Type.MUTE, event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(msg("muted-blocked", event.getPlayer(), tags(mute)));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) label = label.substring(label.indexOf(':') + 1);
        if (!plugin.getConfig().getStringList("punishments.mute-blocked-commands").contains(label)) return;
        Punishment mute = activeFor(Type.MUTE, event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(msg("muted-blocked", event.getPlayer(), tags(mute)));
    }

    /** Nach dem Vergeben: Betroffene kicken bzw. informieren. Main-Thread. */
    void enforce(Punishment p) {
        switch (p.type) {
            case BAN -> {
                Player online = p.target == null ? null : Bukkit.getPlayer(p.target);
                if (online != null) online.kick(screen(p));
            }
            case IPBAN -> {
                for (Player online : List.copyOf(Bukkit.getOnlinePlayers())) {
                    InetSocketAddress a = online.getAddress();
                    if (a != null && a.getAddress().getHostAddress().equals(p.ip)) online.kick(screen(p));
                }
            }
            case MUTE -> {
                Player online = p.target == null ? null : Bukkit.getPlayer(p.target);
                if (online != null) online.sendMessage(msg("muted", online, tags(p)));
            }
        }
    }

    private Component screen(Punishment p) {
        return msg(p.type == Type.IPBAN ? "ipban-screen" : "ban-screen", null, tags(p));
    }

    // ------------------------------------------------------------
    // Texte
    // ------------------------------------------------------------

    TagResolver tags(Punishment p) {
        long now = System.currentTimeMillis();
        String remaining = p.permanent() ? "permanent" : Durations.format(Duration.ofMillis(p.expires - now));
        String duration = p.permanent() ? "permanent" : Durations.format(Duration.ofMillis(p.expires - p.created));
        return TagResolver.resolver(
                TextService.placeholder("id", String.valueOf(p.id)),
                TextService.placeholder("type", p.type.label),
                TextService.placeholder("verb", p.type.verb),
                TextService.placeholder("target", p.targetName != null ? p.targetName : p.ip),
                TextService.placeholder("reason", p.reason),
                TextService.placeholder("actor", p.actor),
                TextService.placeholder("duration", duration),
                TextService.placeholder("remaining", remaining),
                TextService.placeholder("date", DATE.format(Instant.ofEpochMilli(p.created))),
                TextService.placeholder("expires", p.permanent() ? "nie" : DATE.format(Instant.ofEpochMilli(p.expires))));
    }

    Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("punishments.messages." + key), player, extra);
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = yml.getInt("next-id");
        ConfigurationSection sec = yml.getConfigurationSection("punishments");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(key);
                Type type = s == null ? null : Type.parse(s.getString("type"));
                if (type == null) continue;
                String target = s.getString("target");
                Punishment p = new Punishment(Integer.parseInt(key), type, target == null ? null : UUID.fromString(target),
                        s.getString("target-name"), s.getString("ip"), s.getString("reason", ""), s.getString("actor", "?"),
                        s.getLong("created"), s.getLong("expires", -1), s.getString("template"));
                p.active = s.getBoolean("active");
                p.revokedBy = s.getString("revoked-by");
                p.revokedAt = s.getLong("revoked-at");
                all.add(p);
            }
        }
        ConfigurationSection ips = yml.getConfigurationSection("last-ip");
        if (ips != null) for (String key : ips.getKeys(false)) lastIp.put(UUID.fromString(key), ips.getString(key));
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Punishment p : all) {
            String path = "punishments." + p.id + ".";
            yml.set(path + "type", p.type.name());
            yml.set(path + "target", p.target == null ? null : p.target.toString());
            yml.set(path + "target-name", p.targetName);
            yml.set(path + "ip", p.ip);
            yml.set(path + "reason", p.reason);
            yml.set(path + "actor", p.actor);
            yml.set(path + "created", p.created);
            yml.set(path + "expires", p.expires);
            yml.set(path + "template", p.template);
            yml.set(path + "active", p.active);
            yml.set(path + "revoked-by", p.revokedBy);
            if (p.revokedAt > 0) yml.set(path + "revoked-at", p.revokedAt);
        }
        lastIp.forEach((id, ip) -> yml.set("last-ip." + id, ip));
        writer.write(file, yml.saveToString());
    }
}
