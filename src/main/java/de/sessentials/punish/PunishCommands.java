package de.sessentials.punish;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.sessentials.SEssentials;
import de.sessentials.punish.PunishmentManager.Punishment;
import de.sessentials.punish.PunishmentManager.Type;
import de.sessentials.text.Durations;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** /punish (Vorlagen), /ban, /mute, /ipban, /unban, /unmute, /unbanip, /history. */
public final class PunishCommands {

    static final String PERM_NOTIFY = "sess.punish.notify";
    private static final String PERM_EXEMPT = "sess.punish.exempt";
    private static final String PERM_HISTORY = "sess.punish.history";
    private static final String NO_REASON = "Kein Grund angegeben";
    private static final Pattern IP = Pattern.compile("^(\\d{1,3}(\\.\\d{1,3}){3}|[0-9a-fA-F:]+:[0-9a-fA-F:]+)$");

    private final SEssentials plugin;
    private final PunishmentManager punishments;

    public PunishCommands(SEssentials plugin, PunishmentManager punishments) {
        this.plugin = plugin;
        this.punishments = punishments;
    }

    static String perm(Type type) {
        return "sess.punish." + type.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------
    // Vergeben
    // ------------------------------------------------------------

    /** Eine Strafe mit Zeit-/Grund-Text wie "7d Griefing", "perm Hacking" oder nur "Spam" (= permanent). */
    void punish(CommandSender sender, Type type, String targetArg, String rest, String template) {
        String reason = rest == null || rest.isBlank() ? NO_REASON : rest.trim();
        Duration duration = null;
        String first = reason.split(" ", 2)[0];
        if (first.equalsIgnoreCase("perm") || first.equalsIgnoreCase("permanent")) {
            reason = reason.length() > first.length() ? reason.substring(first.length()).trim() : NO_REASON;
        } else if (Durations.parse(first) != null) {
            duration = Durations.parse(first);
            reason = reason.length() > first.length() ? reason.substring(first.length()).trim() : NO_REASON;
        }
        apply(sender, null, type, targetArg, reason, duration, template);
    }

    /** {@code actorName} = Name im Verlauf; {@code null} = Absender bzw. "Konsole". */
    private void apply(CommandSender sender, String actorName, Type type, String targetArg, String reason, Duration duration, String template) {
        Player self = sender instanceof Player p ? p : null;
        String actor = actorName != null ? actorName : self != null ? self.getName() : "Konsole";
        String ip = null;
        OfflinePlayer target = null;

        if (type == Type.IPBAN && IP.matcher(targetArg).matches()) {
            ip = targetArg;
        } else {
            target = Bukkit.getOfflinePlayerIfCached(targetArg);
            if (target == null) {
                sender.sendMessage(punishments.msg("unknown-player", self, TextService.placeholder("target", targetArg)));
                return;
            }
            if (self != null && target.getUniqueId().equals(self.getUniqueId())) {
                sender.sendMessage(punishments.msg("self", self));
                return;
            }
            Player online = target.getPlayer();
            // Konsole darf immer; Staff kann sich nicht gegenseitig bestrafen
            if (self != null && online != null && online.hasPermission(PERM_EXEMPT)) {
                sender.sendMessage(punishments.msg("exempt", self, TextService.placeholder("target", online.getName())));
                return;
            }
            if (type == Type.IPBAN) {
                InetSocketAddress address = online != null ? online.getAddress() : null;
                ip = address != null ? address.getAddress().getHostAddress() : punishments.lastIp(target.getUniqueId());
                if (ip == null) {
                    sender.sendMessage(punishments.msg("no-ip", self, TextService.placeholder("target", targetArg)));
                    return;
                }
            }
        }

        Punishment p = punishments.punish(type, target == null ? null : target.getUniqueId(),
                target == null ? null : (target.getName() != null ? target.getName() : targetArg), ip, reason, actor, duration, template);
        punishments.enforce(p);
        announce(sender, p);
    }

    private void announce(CommandSender sender, Punishment p) {
        TagResolver tags = punishments.tags(p);
        Component staff = punishments.msg("notify", null, tags);
        if (!(sender instanceof Player)) sender.sendMessage(staff);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERM_NOTIFY) || player == sender) player.sendMessage(staff);
            else if (plugin.getConfig().getBoolean("punishments.broadcast") && p.type != Type.IPBAN) {
                player.sendMessage(punishments.msg("broadcast", player, tags));
            }
        }
        plugin.getLogger().info("Strafe #" + p.id + ": " + p.type.label + " für " + (p.targetName != null ? p.targetName : p.ip)
                + " von " + p.actor + " – " + p.reason);
    }

    // ------------------------------------------------------------
    // Vorlagen
    // ------------------------------------------------------------

    /** Automatische Strafe nach Vorlage (z.B. vom Fairplay-System) – als Konsole, im Verlauf unter {@code actor}. */
    public void punishAutomatic(String targetName, String templateId, String actor) {
        punishTemplate(Bukkit.getConsoleSender(), actor, targetName, templateId);
    }

    /** Automatischer Ban mit fester Dauer (z.B. Verdachts-Abstimmung) – als Konsole, im Verlauf unter {@code actor}. */
    public void banAutomatic(String targetName, String reason, Duration duration, String actor) {
        apply(Bukkit.getConsoleSender(), actor, Type.BAN, targetName, reason, duration, null);
    }

    private void punishTemplate(CommandSender sender, String targetArg, String id) {
        punishTemplate(sender, null, targetArg, id);
    }

    private void punishTemplate(CommandSender sender, String actor, String targetArg, String id) {
        Player self = sender instanceof Player p ? p : null;
        ConfigurationSection t = plugin.getConfig().getConfigurationSection("punishments.templates." + id);
        Type type = t == null ? null : Type.parse(t.getString("type"));
        if (type == null) {
            sender.sendMessage(punishments.msg("unknown-template", self, TextService.placeholder("id", id)));
            return;
        }
        if (!sender.hasPermission(perm(type))) {
            sender.sendMessage(punishments.msg("no-permission", self));
            return;
        }
        String durationText = t.getString("duration", "permanent");
        Duration duration = durationText.toLowerCase(Locale.ROOT).startsWith("perm") ? null : Durations.parse(durationText);
        if (duration == null && !durationText.toLowerCase(Locale.ROOT).startsWith("perm")) {
            plugin.getLogger().warning("punishments.templates." + id + ".duration '" + durationText + "' ist ungültig – permanent.");
        }
        apply(sender, actor, type, targetArg, t.getString("reason", NO_REASON), duration, id);
    }

    // ------------------------------------------------------------
    // Aufheben und Verlauf
    // ------------------------------------------------------------

    private void unpunish(CommandSender sender, Type type, String targetArg) {
        Player self = sender instanceof Player p ? p : null;
        String actor = self != null ? self.getName() : "Konsole";
        Punishment active;
        if (type == Type.IPBAN && IP.matcher(targetArg).matches()) {
            active = punishments.activeIpBan(targetArg);
        } else {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetArg);
            if (target == null) {
                sender.sendMessage(punishments.msg("unknown-player", self, TextService.placeholder("target", targetArg)));
                return;
            }
            if (type == Type.IPBAN) {
                String ip = punishments.lastIp(target.getUniqueId());
                active = ip == null ? null : punishments.activeIpBan(ip);
            } else {
                active = punishments.activeFor(type, target.getUniqueId());
            }
        }
        Punishment revoked = punishments.revoke(active, actor);
        if (revoked == null) {
            sender.sendMessage(punishments.msg("not-punished", self, TextService.placeholder("target", targetArg),
                    TextService.placeholder("type", type.label)));
            return;
        }
        revokeAnnounce(sender, revoked, actor);
    }

    void revokeAnnounce(CommandSender sender, Punishment p, String actor) {
        TagResolver tags = TagResolver.resolver(punishments.tags(p), TextService.placeholder("revoker", actor));
        Component staff = punishments.msg("revoked", null, tags);
        if (!(sender instanceof Player)) sender.sendMessage(staff);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(PERM_NOTIFY) || player == sender) player.sendMessage(staff);
        }
        if (p.type == Type.MUTE && p.target != null && Bukkit.getPlayer(p.target) != null) {
            Bukkit.getPlayer(p.target).sendMessage(punishments.msg("unmuted", Bukkit.getPlayer(p.target), tags));
        }
    }

    private void history(CommandSender sender, String targetArg) {
        Player self = sender instanceof Player p ? p : null;
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetArg);
        if (target == null) {
            sender.sendMessage(punishments.msg("unknown-player", self, TextService.placeholder("target", targetArg)));
            return;
        }
        List<Punishment> list = punishments.history(target.getUniqueId());
        sender.sendMessage(punishments.msg("history-header", self, TextService.placeholder("target", targetArg),
                TextService.placeholder("count", String.valueOf(list.size()))));
        long now = System.currentTimeMillis();
        for (Punishment p : list.subList(0, Math.min(15, list.size()))) {
            String status = p.isActive(now) ? "aktiv"
                    : p.revokedBy != null ? "aufgehoben von " + p.revokedBy
                    : "abgelaufen am " + PunishmentManager.DATE.format(Instant.ofEpochMilli(p.expires));
            sender.sendMessage(punishments.msg("history-entry", self, punishments.tags(p), TextService.placeholder("status", status)));
        }
    }

    // ------------------------------------------------------------
    // Registrierung
    // ------------------------------------------------------------

    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (ctx, builder) -> {
        CommandSender sender = ctx.getSource().getSender();
        String typed = builder.getRemainingLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean visible = !(sender instanceof Player self) || self.canSee(p);
            if (visible && p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(p.getName());
        }
        return builder.buildFuture();
    };

    public void register(Commands commands) {
        // eigene Befehle statt Aliase, damit sie die Vanilla-Befehle (z.B. /ban) ersetzen
        commands.register(withTarget("ban", perm(Type.BAN), (ctx, rest) -> punish(src(ctx), Type.BAN, target(ctx), rest, null)),
                "Bannt einen Spieler: /ban <spieler> [dauer|perm] [grund]", List.of());
        commands.register(withTarget("mute", perm(Type.MUTE), (ctx, rest) -> punish(src(ctx), Type.MUTE, target(ctx), rest, null)),
                "Mutet einen Spieler: /mute <spieler> [dauer|perm] [grund]", List.of());
        commands.register(withTarget("ipban", perm(Type.IPBAN), (ctx, rest) -> punish(src(ctx), Type.IPBAN, target(ctx), rest, null)),
                "IP-Bann: /ipban <spieler|ip> [dauer|perm] [grund]", List.of());
        commands.register(simple("unban", perm(Type.BAN), ctx -> unpunish(src(ctx), Type.BAN, target(ctx))), "Hebt einen Ban auf", List.of());
        commands.register(simple("unmute", perm(Type.MUTE), ctx -> unpunish(src(ctx), Type.MUTE, target(ctx))), "Hebt einen Mute auf", List.of());
        commands.register(simple("unbanip", perm(Type.IPBAN), ctx -> unpunish(src(ctx), Type.IPBAN, target(ctx))), "Hebt einen IP-Bann auf", List.of());
        commands.register(simple("history", PERM_HISTORY, ctx -> history(src(ctx), target(ctx))), "Strafen eines Spielers", List.of());

        commands.register(
                Commands.literal("punish")
                        .requires(src -> List.of(Type.values()).stream().anyMatch(t -> src.getSender().hasPermission(perm(t))))
                        .then(Commands.argument("spieler", StringArgumentType.word()).suggests(PLAYERS)
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            ConfigurationSection templates = plugin.getConfig().getConfigurationSection("punishments.templates");
                                            if (templates != null) {
                                                for (String id : templates.getKeys(false)) {
                                                    ConfigurationSection t = templates.getConfigurationSection(id);
                                                    if (t == null) continue;
                                                    builder.suggest(id, new LiteralMessage(t.getString("type", "?") + " "
                                                            + t.getString("duration", "permanent") + " – " + t.getString("reason", "")));
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            punishTemplate(src(ctx), target(ctx), StringArgumentType.getString(ctx, "id"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .build(),
                "Bestraft einen Spieler nach Vorlage: /punish <spieler> <id>",
                List.of());
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> withTarget(
            String label, String permission, BiConsumer<CommandContext<CommandSourceStack>, String> action) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(label)
                .requires(src -> src.getSender().hasPermission(permission));
        node.then(Commands.argument("spieler", StringArgumentType.word()).suggests(PLAYERS)
                .executes(ctx -> {
                    action.accept(ctx, null);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("grund", StringArgumentType.greedyString()).executes(ctx -> {
                    action.accept(ctx, StringArgumentType.getString(ctx, "grund"));
                    return Command.SINGLE_SUCCESS;
                })));
        return node.build();
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> simple(
            String label, String permission, Consumer<CommandContext<CommandSourceStack>> action) {
        return Commands.literal(label)
                .requires(src -> src.getSender().hasPermission(permission))
                .then(Commands.argument("spieler", StringArgumentType.word()).suggests(PLAYERS).executes(ctx -> {
                    action.accept(ctx);
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    private static CommandSender src(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private static String target(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "spieler");
    }
}
