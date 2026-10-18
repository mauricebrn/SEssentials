package de.sessentials.rules;

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
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Regeln: /regeln (Übersicht), /regeln <nr> (ganze Regel mit Strafe), /regeln akzeptieren, /regeladmin (Verwaltung).
 * Wer die aktuelle Version noch nicht akzeptiert hat, wird beim Join erinnert. Nach wichtigen Änderungen erhöhen
 * Admins die Version – dann müssen alle erneut akzeptieren. Gespeichert in {@code data/rules.yml}.
 */
public final class RulesManager implements Listener {

    static final String PERM_ADMIN = "sess.rules.admin";
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    /** Leerzeilen, mit denen der Chat beim Blättern geleert wird (wie im alten Skript). */
    private static final int CLEAR_LINES = 100;
    private static final long JOIN_DELAY_TICKS = 4 * 20L;

    static final class Rule {
        final int id;
        final long created;
        String title;
        String content;
        /** {@code null} = keine Strafe angegeben. */
        String penalty;
        boolean visible;

        Rule(int id, long created) {
            this.id = id;
            this.created = created;
        }
    }

    /** Akzeptierte Version und Zeitpunkt. */
    record Acceptance(int version, long time) {}

    private final SEssentials plugin;
    private final File file;
    private final RulesMenu menu;
    private final Map<Integer, Rule> rules = new HashMap<>();
    /** Reihenfolge aller Regeln (auch unsichtbarer). */
    private final List<Integer> order = new ArrayList<>();
    private final Map<UUID, Acceptance> accepted = new HashMap<>();
    /** Version → Anzahl Bestätigungen (für den Rollout-Tracker). */
    private final Map<Integer, Integer> confirmations = new TreeMap<>();
    private int version = 1;
    private int nextId;

    public RulesManager(SEssentials plugin) {
        this.plugin = plugin;
        this.file = plugin.dataFile("rules.yml");
        this.menu = new RulesMenu(plugin, this);
        load();
    }

    public RulesMenu menu() {
        return menu;
    }

    // ------------------------------------------------------------
    // Daten
    // ------------------------------------------------------------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        version = Math.max(1, yml.getInt("version", 1));
        nextId = yml.getInt("next-id");
        ConfigurationSection sec = yml.getConfigurationSection("rules");
        for (int id : yml.getIntegerList("order")) {
            ConfigurationSection s = sec == null ? null : sec.getConfigurationSection(String.valueOf(id));
            if (s == null) continue;
            Rule r = new Rule(id, s.getLong("created"));
            r.title = s.getString("title", "");
            r.content = s.getString("content", "");
            r.penalty = s.getString("penalty");
            r.visible = s.getBoolean("visible");
            rules.put(id, r);
            order.add(id);
        }
        ConfigurationSection acc = yml.getConfigurationSection("accepted");
        if (acc != null) {
            for (String key : acc.getKeys(false)) {
                try {
                    accepted.put(UUID.fromString(key), new Acceptance(acc.getInt(key + ".version"), acc.getLong(key + ".time")));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("rules.yml: ungültige UUID " + key);
                }
            }
        }
        ConfigurationSection conf = yml.getConfigurationSection("confirmations");
        if (conf != null) for (String key : conf.getKeys(false)) confirmations.put(Integer.parseInt(key), conf.getInt(key));
    }

    void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("version", version);
        yml.set("next-id", nextId);
        yml.set("order", order);
        for (int id : order) {
            Rule r = rules.get(id);
            String path = "rules." + id + ".";
            yml.set(path + "title", r.title);
            yml.set(path + "content", r.content);
            yml.set(path + "penalty", r.penalty);
            yml.set(path + "visible", r.visible);
            yml.set(path + "created", r.created);
        }
        accepted.forEach((id, a) -> {
            yml.set("accepted." + id + ".version", a.version());
            yml.set("accepted." + id + ".time", a.time());
        });
        confirmations.forEach((v, count) -> yml.set("confirmations." + v, count));
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "rules.yml konnte nicht gespeichert werden", e);
        }
    }

    Rule get(int id) {
        return rules.get(id);
    }

    /** Alle Regeln in ihrer Reihenfolge. */
    List<Rule> all() {
        return order.stream().map(rules::get).toList();
    }

    /** Sichtbare Regeln in ihrer Reihenfolge – Position + 1 = Nummer für Spieler. */
    List<Rule> visible() {
        return all().stream().filter(r -> r.visible).toList();
    }

    /** Nummer, die Spieler sehen (§n), oder 0, wenn unsichtbar. */
    int number(Rule r) {
        return visible().indexOf(r) + 1;
    }

    int position(Rule r) {
        return order.indexOf(r.id) + 1;
    }

    Rule create() {
        Rule r = new Rule(++nextId, System.currentTimeMillis());
        r.title = "Neue Regel";
        r.content = "Hier steht die Regel. [Alle Regeln](/regeln)";
        rules.put(r.id, r);
        order.add(r.id);
        save();
        return r;
    }

    void delete(Rule r) {
        rules.remove(r.id);
        order.remove(Integer.valueOf(r.id));
        save();
    }

    /** Verschiebt um {@code dir} Plätze (-1 = nach oben, 1 = nach unten). */
    void move(Rule r, int dir) {
        int i = order.indexOf(r.id), j = i + dir;
        if (i < 0 || j < 0 || j >= order.size()) return;
        order.set(i, order.get(j));
        order.set(j, r.id);
        save();
    }

    int version() {
        return version;
    }

    void bumpVersion() {
        version++;
        save();
    }

    boolean hasAccepted(UUID id) {
        Acceptance a = accepted.get(id);
        return a != null && a.version() >= version;
    }

    Acceptance acceptance(UUID id) {
        return accepted.get(id);
    }

    Map<Integer, Integer> confirmations() {
        return confirmations;
    }

    boolean needsAccept(Player player) {
        return !visible().isEmpty() && !hasAccepted(player.getUniqueId());
    }

    // ------------------------------------------------------------
    // Darstellung
    // ------------------------------------------------------------

    /** {@code <para>} = §-Zeichen; direkt im Text würde es als alter Farbcode gelesen. */
    private static final TagResolver PARA = Placeholder.component("para", Component.text("§"));

    Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("rules.messages." + key, ""), player,
                TagResolver.resolver(TagResolver.resolver(extra), PARA));
    }

    Component title(Rule r, Player player) {
        return plugin.text().render(r.title, player);
    }

    /** Einzeilige Kurzfassung ohne Formatierung, auf {@code max} Zeichen gekürzt. */
    String shortText(Rule r, Player player, int max) {
        String plain = PlainTextComponentSerializer.plainText()
                .serialize(plugin.text().render(TextService.richText(r.content, false), player))
                .replace('\n', ' ');
        return plain.length() > max ? plain.substring(0, max - 3) + "..." : plain;
    }

    private TagResolver tags(Rule r, int number, Player player) {
        return TagResolver.resolver(
                TextService.placeholder("number", String.valueOf(number)),
                Placeholder.component("title", title(r, player)),
                TextService.placeholder("short", shortText(r, player, 40)));
    }

    private void clearChat(Player player) {
        for (int i = 0; i < CLEAR_LINES; i++) player.sendMessage(Component.space());
    }

    /** Übersicht aller sichtbaren Regeln, klickbar. */
    void feed(Player player, boolean clear) {
        if (clear) clearChat(player);
        List<Rule> visible = visible();
        if (visible.isEmpty()) {
            player.sendMessage(msg("none", player));
            return;
        }
        player.sendMessage(msg("line", player));
        player.sendMessage(msg("feed-header", player, TextService.placeholder("count", String.valueOf(visible.size()))));
        player.sendMessage(Component.empty());
        for (int i = 0; i < visible.size(); i++) {
            player.sendMessage(msg("feed-entry", player, tags(visible.get(i), i + 1, player)));
        }
        if (needsAccept(player)) {
            player.sendMessage(Component.empty());
            player.sendMessage(msg("accept-button", player));
        }
        player.sendMessage(msg("line", player));
    }

    /** Ganze Regel; {@code nav} = Blätter-Knöpfe anzeigen. */
    void show(Player player, Rule r, boolean nav, boolean clear) {
        if (clear) clearChat(player);
        int number = number(r);
        TagResolver tags = tags(r, number, player);
        player.sendMessage(msg("line", player));
        player.sendMessage(msg(number > 0 ? "rule-header" : "rule-header-hidden", player, tags));
        player.sendMessage(Component.empty());
        player.sendMessage(plugin.text().render("<white>" + TextService.richText(r.content, true), player));
        if (r.penalty != null) {
            player.sendMessage(Component.empty());
            player.sendMessage(msg("rule-penalty", player, TextService.placeholder("penalty", r.penalty)));
        }
        if (nav) {
            player.sendMessage(Component.empty());
            Component out = msg("nav-all", player);
            if (number > 1) out = out.append(Component.space()).append(msg("nav-prev", player, TextService.placeholder("number", String.valueOf(number - 1))));
            if (number > 0 && number < visible().size()) {
                out = out.append(Component.space()).append(msg("nav-next", player, TextService.placeholder("number", String.valueOf(number + 1))));
            }
            player.sendMessage(out);
        }
        player.sendMessage(msg("line", player));
    }

    /** Hinweis für Spieler, die (die neue Version) noch akzeptieren müssen. */
    void notifyPending(Player player) {
        player.sendMessage(msg("line", player));
        player.sendMessage(msg(accepted.containsKey(player.getUniqueId()) ? "notify-updated" : "notify-new", player));
        player.sendMessage(msg("notify-buttons", player));
        player.sendMessage(msg("line", player));
    }

    /** Erinnert alle Online-Spieler, die noch nicht akzeptiert haben; liefert ihre Anzahl. */
    int remindAll() {
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (needsAccept(p)) {
                notifyPending(p);
                count++;
            }
        }
        return count;
    }

    private void accept(Player player) {
        if (visible().isEmpty()) {
            player.sendMessage(msg("nothing-to-accept", player));
            return;
        }
        if (!needsAccept(player)) {
            player.sendMessage(msg("already-accepted", player));
            return;
        }
        accepted.put(player.getUniqueId(), new Acceptance(version, System.currentTimeMillis()));
        confirmations.merge(version, 1, Integer::sum);
        save();
        player.sendMessage(msg("accepted", player));
    }

    private void showNumber(Player player, int number) {
        List<Rule> visible = visible();
        if (number < 1 || number > visible.size()) {
            player.sendMessage(msg("not-found", player, TextService.placeholder("number", String.valueOf(number))));
            return;
        }
        show(player, visible.get(number - 1), true, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && needsAccept(player)) notifyPending(player);
        }, JOIN_DELAY_TICKS);
    }

    // ------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------

    public void register(Commands commands) {
        var acceptNode = Commands.literal("akzeptieren").executes(ctx -> {
            accept((Player) ctx.getSource().getExecutor());
            return Command.SINGLE_SUCCESS;
        });
        commands.register(
                Commands.literal("regeln")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(ctx -> {
                            feed((Player) ctx.getSource().getExecutor(), true);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(acceptNode)
                        .then(Commands.literal("accept").executes(acceptNode.getCommand()))
                        .then(Commands.argument("nr", IntegerArgumentType.integer(1)).executes(ctx -> {
                            showNumber((Player) ctx.getSource().getExecutor(), IntegerArgumentType.getInteger(ctx, "nr"));
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "Zeigt die Serverregeln",
                List.of("rules", "regel"));

        commands.register(
                Commands.literal("regeladmin")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_ADMIN))
                        .executes(ctx -> {
                            menu.openPanel((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("edit").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(ctx -> {
                            Player p = (Player) ctx.getSource().getExecutor();
                            Rule r = rules.get(IntegerArgumentType.getInteger(ctx, "id"));
                            if (r == null) p.sendMessage(msg("unknown-id", p));
                            else menu.openEditor(p, r);
                            return Command.SINGLE_SUCCESS;
                        })))
                        .then(Commands.literal("rollout").executes(ctx -> {
                            menu.openRollout((Player) ctx.getSource().getExecutor(), true, 1);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("cancel").executes(ctx -> {
                            plugin.chatPrompt().cancel((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "Regel-Verwaltung",
                List.of("rulesadmin"));
    }
}
