package de.sessentials.player;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * /sudo &lt;spieler&gt; &lt;befehl&gt; lässt Spieler (auch Fake-Spieler) einen Befehl ausführen, /sudo &lt;spieler&gt; c:&lt;text&gt;
 * etwas in den Chat schreiben. Läuft wie eine echte Eingabe: mit den Rechten des Spielers, Mute-Sperren,
 * Chatformat und Befehls-Protokoll. Spieler mit {@code sess.sudo.exempt} kann nur die Konsole steuern.
 */
public final class SudoCommand {

    private static final String PERM = "sess.sudo";
    private static final String PERM_EXEMPT = "sess.sudo.exempt";
    private static final String CHAT_PREFIX = "c:";

    private final SEssentials plugin;

    public SudoCommand(SEssentials plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("sudo")
                        .requires(src -> src.getSender().hasPermission(PERM))
                        .then(Commands.argument("spieler", ArgumentTypes.players())
                                .then(Commands.argument("eingabe", StringArgumentType.greedyString()).executes(ctx -> {
                                    List<Player> targets = ctx.getArgument("spieler", PlayerSelectorArgumentResolver.class)
                                            .resolve(ctx.getSource());
                                    sudo(ctx.getSource().getSender(), targets, StringArgumentType.getString(ctx, "eingabe"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .build(),
                "Lässt einen Spieler einen Befehl ausführen (c:<text> = Chat)",
                List.of());
    }

    private void sudo(CommandSender sender, List<Player> targets, String input) {
        boolean chat = input.regionMatches(true, 0, CHAT_PREFIX, 0, CHAT_PREFIX.length());
        String text = chat ? input.substring(CHAT_PREFIX.length()).strip() : input.strip().replaceFirst("^/+", "");
        if (text.isEmpty()) {
            sender.sendMessage(msg("empty", sender));
            return;
        }
        for (Player target : targets) {
            TagResolver tags = TagResolver.resolver(
                    TextService.placeholder("target", target.getName()),
                    Placeholder.unparsed("input", chat ? text : "/" + text));
            // Konsole darf alles; Spieler können geschützte Spieler nicht steuern
            if (sender instanceof Player && target != sender && target.hasPermission(PERM_EXEMPT)) {
                sender.sendMessage(msg("exempt", sender, tags));
                continue;
            }
            plugin.getLogger().info(sender.getName() + " /sudo " + target.getName() + ": " + (chat ? "c:" : "/") + text);
            // über chat(): ein führendes "/" wird zum Befehl, mit allen Events (Mute, CreativeLog, …)
            target.chat(chat ? text : "/" + text);
            sender.sendMessage(msg(chat ? "chatted" : "executed", sender, tags));
        }
    }

    private Component msg(String key, CommandSender sender, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("sudo.messages." + key, ""),
                sender instanceof Player p ? p : null, extra);
    }
}
