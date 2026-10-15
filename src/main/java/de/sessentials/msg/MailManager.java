package de.sessentials.msg;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.sessentials.SEssentials;
import de.sessentials.punish.PunishmentManager;
import de.sessentials.storage.AsyncFileWriter;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * /mail: Nachrichten an Spieler hinterlassen, auch wenn sie offline sind. Gespeichert in mail.yml;
 * beim Join erfährt der Empfänger von ungelesenen Mails. Gemutete Spieler können keine Mails senden.
 */
public final class MailManager implements Listener {

    private static final String PERM = "sess.mail";
    private static final int MAX_SUGGESTIONS = 50;

    private record Mail(String from, long time, String text, boolean read) {
        Mail markRead() {
            return new Mail(from, time, text, true);
        }
    }

    private final SEssentials plugin;
    private final File file;
    private final AsyncFileWriter writer;
    /** Empfänger → Postfach, älteste zuerst (nur Main-Thread). */
    private final Map<UUID, List<Mail>> inboxes = new HashMap<>();
    /** Namen aller Spieler, die schon einmal da waren – für die Tab-Vervollständigung (auch asynchron gelesen). */
    private final NavigableSet<String> knownNames = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    public MailManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "mail.yml");
        this.writer = new AsyncFileWriter("Mail", plugin.getLogger());
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null) knownNames.add(p.getName());
        }
        load();
    }

    public void shutdown() {
        writer.shutdown();
    }

    // ------------------------------------------------------------
    // Aktionen
    // ------------------------------------------------------------

    private void send(CommandSender sender, String targetName, String text) {
        Player self = sender instanceof Player p ? p : null;
        if (self != null && plugin.punishments().activeFor(PunishmentManager.Type.MUTE, self.getUniqueId()) != null) {
            sender.sendMessage(msg("muted", self));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            sender.sendMessage(msg("unknown-player", self, TextService.placeholder("target", targetName)));
            return;
        }
        if (self != null && target.getUniqueId().equals(self.getUniqueId())) {
            sender.sendMessage(msg("self", self));
            return;
        }
        int maxLength = plugin.getConfig().getInt("mail.max-length", 256);
        if (text.length() > maxLength) {
            sender.sendMessage(msg("too-long", self, TextService.placeholder("max", String.valueOf(maxLength))));
            return;
        }
        String name = target.getName() != null ? target.getName() : targetName;
        List<Mail> inbox = inboxes.computeIfAbsent(target.getUniqueId(), id -> new ArrayList<>());
        if (inbox.size() >= plugin.getConfig().getInt("mail.max-per-inbox", 50)) {
            sender.sendMessage(msg("inbox-full", self, TextService.placeholder("target", name)));
            return;
        }
        String from = self != null ? self.getName() : "Konsole";
        inbox.add(new Mail(from, System.currentTimeMillis(), text, false));
        save();
        sender.sendMessage(msg("sent", self, TextService.placeholder("target", name), Placeholder.unparsed("message", text)));
        plugin.notifications().spy("mail", from, name, text);
        Player online = target.getPlayer();
        if (online != null) online.sendMessage(msg("new", online, TextService.placeholder("sender", from)));
    }

    private void read(Player player) {
        List<Mail> inbox = inboxes.get(player.getUniqueId());
        if (inbox == null || inbox.isEmpty()) {
            player.sendMessage(msg("empty", player));
            return;
        }
        player.sendMessage(msg("header", player, TextService.placeholder("count", String.valueOf(inbox.size()))));
        SimpleDateFormat date = new SimpleDateFormat("dd.MM. HH:mm");
        for (int i = 0; i < inbox.size(); i++) {
            Mail m = inbox.get(i);
            player.sendMessage(msg(m.read() ? "entry" : "entry-unread", player,
                    TextService.placeholder("number", String.valueOf(i + 1)),
                    TextService.placeholder("sender", m.from()),
                    TextService.placeholder("time", date.format(new Date(m.time()))),
                    // Mailtext immer als reiner Text – keine eingeschleuste Formatierung
                    Placeholder.unparsed("message", m.text())));
            inbox.set(i, m.markRead());
        }
        player.sendMessage(msg("footer", player));
        save();
    }

    private void delete(Player player, int number) {
        List<Mail> inbox = inboxes.get(player.getUniqueId());
        if (inbox == null || number < 1 || number > inbox.size()) {
            player.sendMessage(msg("no-such-mail", player, TextService.placeholder("number", String.valueOf(number))));
            return;
        }
        inbox.remove(number - 1);
        if (inbox.isEmpty()) inboxes.remove(player.getUniqueId());
        save();
        player.sendMessage(msg("deleted", player, TextService.placeholder("number", String.valueOf(number))));
    }

    private void clear(Player player) {
        List<Mail> removed = inboxes.remove(player.getUniqueId());
        if (removed != null) save();
        player.sendMessage(msg("cleared", player));
    }

    private long unread(UUID id) {
        List<Mail> inbox = inboxes.get(id);
        return inbox == null ? 0 : inbox.stream().filter(m -> !m.read()).count();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        knownNames.add(player.getName());
        long unread = unread(player.getUniqueId());
        if (unread == 0) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.sendMessage(msg("unread", player, TextService.placeholder("count", String.valueOf(unread))));
        }, 40L);
    }

    // ------------------------------------------------------------
    // Befehl
    // ------------------------------------------------------------

    public void register(Commands commands) {
        SuggestionProvider<CommandSourceStack> players = (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            String self = ctx.getSource().getSender().getName();
            int count = 0;
            for (String name : knownNames.tailSet(typed, true)) {
                if (!name.toLowerCase(Locale.ROOT).startsWith(typed) || count >= MAX_SUGGESTIONS) break;
                if (name.equalsIgnoreCase(self)) continue;
                builder.suggest(name);
                count++;
            }
            return builder.buildFuture();
        };
        commands.register(
                Commands.literal("mail")
                        .requires(src -> src.getSender().hasPermission(PERM))
                        .executes(ctx -> {
                            if (ctx.getSource().getExecutor() instanceof Player p) read(p);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("read").requires(src -> src.getExecutor() instanceof Player).executes(ctx -> {
                            read((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("send")
                                .then(Commands.argument("spieler", StringArgumentType.word()).suggests(players)
                                        .then(Commands.argument("nachricht", StringArgumentType.greedyString()).executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getExecutor() instanceof Player p ? p : ctx.getSource().getSender();
                                            send(sender, StringArgumentType.getString(ctx, "spieler"), StringArgumentType.getString(ctx, "nachricht"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                        .then(Commands.literal("delete").requires(src -> src.getExecutor() instanceof Player)
                                .then(Commands.argument("nummer", IntegerArgumentType.integer(1)).executes(ctx -> {
                                    delete((Player) ctx.getSource().getExecutor(),
                                            IntegerArgumentType.getInteger(ctx, "nummer"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(Commands.literal("clear").requires(src -> src.getExecutor() instanceof Player).executes(ctx -> {
                            clear((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "Nachrichten an (auch offline) Spieler: send, read, delete, clear",
                List.of());
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("mail.messages." + key, ""), player, extra);
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("mail");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            List<Mail> inbox = new ArrayList<>();
            for (Map<?, ?> m : sec.getMapList(id)) {
                inbox.add(new Mail(String.valueOf(m.get("from")), m.get("time") instanceof Number n ? n.longValue() : 0,
                        String.valueOf(m.get("text")), Boolean.TRUE.equals(m.get("read"))));
            }
            if (!inbox.isEmpty()) inboxes.put(UUID.fromString(id), inbox);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        inboxes.forEach((id, inbox) -> yml.set("mail." + id, inbox.stream().map(m -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("from", m.from());
            map.put("time", m.time());
            map.put("text", m.text());
            map.put("read", m.read());
            return map;
        }).toList()));
        writer.write(file, yml.saveToString());
    }
}
