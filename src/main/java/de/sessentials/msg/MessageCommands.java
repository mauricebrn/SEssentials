package de.sessentials.msg;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private Nachrichten: /msg (auch /tell, /w, /whisper) ersetzt die Vanilla-Variante, damit /r die letzte
 * Konversation kennt. Versteckte Spieler (Vanish) können nicht angeschrieben werden.
 */
public final class MessageCommands implements Listener {

    private static final String PERM = "sess.msg";

    private final SEssentials plugin;
    /** Spieler → letzter Gesprächspartner (in beide Richtungen gesetzt). */
    private final Map<UUID, UUID> lastPartner = new ConcurrentHashMap<>();

    public MessageCommands(SEssentials plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        SuggestionProvider<CommandSourceStack> visiblePlayers = (ctx, builder) -> {
            CommandSender sender = ctx.getSource().getSender();
            String typed = builder.getRemainingLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean visible = !(sender instanceof Player self) || (self != p && self.canSee(p));
                if (visible && p.getName().toLowerCase().startsWith(typed)) builder.suggest(p.getName());
            }
            return builder.buildFuture();
        };
        // eigene Befehle statt Aliase: als Alias würden sie gegen die Vanilla-Befehle verlieren
        for (String label : List.of("msg", "tell", "w", "whisper")) {
            commands.register(
                    Commands.literal(label)
                            .requires(src -> src.getSender().hasPermission(PERM))
                            .then(Commands.argument("spieler", StringArgumentType.word()).suggests(visiblePlayers)
                                    .then(Commands.argument("nachricht", StringArgumentType.greedyString()).executes(ctx -> {
                                        send(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "spieler"),
                                                StringArgumentType.getString(ctx, "nachricht"));
                                        return Command.SINGLE_SUCCESS;
                                    })))
                            .build(),
                    "Private Nachricht an einen Spieler",
                    List.of());
        }
        for (String label : List.of("r", "reply")) {
            commands.register(
                    Commands.literal(label)
                            .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM))
                            .then(Commands.argument("nachricht", StringArgumentType.greedyString()).executes(ctx -> {
                                reply((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "nachricht"));
                                return Command.SINGLE_SUCCESS;
                            }))
                            .build(),
                    "Antwortet auf die letzte private Nachricht",
                    List.of());
        }
    }

    private void send(CommandSender sender, String targetName, String message) {
        Player target = Bukkit.getPlayerExact(targetName);
        Player self = sender instanceof Player p ? p : null;
        if (target == null || (self != null && !self.canSee(target))) {
            sender.sendMessage(msg("not-online", self, TextService.placeholder("target", targetName)));
            return;
        }
        if (target == self) {
            sender.sendMessage(msg("self", self));
            return;
        }
        deliver(sender, target, message);
    }

    private void reply(Player player, String message) {
        UUID partnerId = lastPartner.get(player.getUniqueId());
        Player partner = partnerId == null ? null : Bukkit.getPlayer(partnerId);
        if (partnerId == null) {
            player.sendMessage(msg("no-partner", player));
            return;
        }
        // Wer einem schreibt, bekommt auch im Vanish eine Antwort – er hat das Gespräch selbst begonnen
        if (partner == null) {
            player.sendMessage(msg("partner-offline", player));
            return;
        }
        deliver(player, partner, message);
    }

    private void deliver(CommandSender sender, Player target, String message) {
        String senderName = sender instanceof Player p ? p.getName() : "Konsole";
        TagResolver tags = TagResolver.resolver(
                TextService.placeholder("sender", senderName),
                TextService.placeholder("target", target.getName()),
                // Nachricht immer als reiner Text – Spieler können keine Formatierung einschleusen
                Placeholder.unparsed("message", message));
        sender.sendMessage(msg("sent", sender instanceof Player p ? p : null, tags));
        target.sendMessage(msg("received", target, tags));
        plugin.notifications().spy("msg", senderName, target.getName(), message);
        if (sender instanceof Player p) {
            lastPartner.put(p.getUniqueId(), target.getUniqueId());
            lastPartner.put(target.getUniqueId(), p.getUniqueId());
        }
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("msg." + key), player, extra);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastPartner.remove(event.getPlayer().getUniqueId());
    }
}
