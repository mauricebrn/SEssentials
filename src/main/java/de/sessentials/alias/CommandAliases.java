package de.sessentials.alias;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.player.TypedCommand;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Eigene Befehle aus der Config ({@code command-aliases}): ein Alias führt einen oder mehrere Befehle aus,
 * mit Platzhaltern für Argumente ({@code $1}, {@code $2}, {@code $1-} = ab dem 1. bis zum Ende) und den
 * Absender ({@code <player>}). Spieler führen die Befehle wie selbst getippt aus (eigene Rechte, Mute-Sperren);
 * Zeilen mit {@code console:} laufen als Konsole – dort sind nur einfache Argumente erlaubt.
 * Welche Aliase es gibt, wird beim Start festgelegt; ihre Befehle gelten nach /sessentials reload sofort.
 * Ein Alias darf keinen anderen Alias aufrufen – sonst wären Endlosschleifen möglich.
 */
public final class CommandAliases {

    private record Alias(List<String> commands, String permission, String description) {}

    private static final String CONSOLE_PREFIX = "console:";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$(\\d)(-?)");
    /** Argumente in Konsolen-Befehlen: keine Selektoren (@a), keine Sonderzeichen. */
    private static final Pattern SAFE_ARG = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]+");

    private final SEssentials plugin;

    public CommandAliases(SEssentials plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("command-aliases");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            String name = key.toLowerCase(Locale.ROOT);
            Alias alias = alias(name);
            if (!NAME.matcher(name).matches() || alias == null || alias.commands().isEmpty()) {
                plugin.getLogger().warning("command-aliases." + key + ": ungültiger Name oder keine Befehle – übersprungen.");
                continue;
            }
            String nested = alias.commands().stream().filter(this::callsAlias).findFirst().orElse(null);
            if (nested != null) {
                plugin.getLogger().warning("command-aliases." + key + ": '" + nested + "' ruft einen Alias auf – das ist nicht erlaubt, übersprungen.");
                continue;
            }
            commands.register(
                    Commands.literal(name)
                            .requires(src -> allowed(src.getSender(), name))
                            .executes(ctx -> run(ctx.getSource(), name, ""))
                            .then(Commands.argument("argumente", StringArgumentType.greedyString())
                                    .executes(ctx -> run(ctx.getSource(), name, StringArgumentType.getString(ctx, "argumente"))))
                            .build(),
                    alias.description(),
                    List.of());
        }
    }

    /** Liest den Alias jedes Mal frisch aus der Config, damit Änderungen nach /sessentials reload gelten. */
    private Alias alias(String name) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("command-aliases");
        if (sec == null) return null;
        String key = sec.getKeys(false).stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (key == null) return null;
        if (sec.isConfigurationSection(key)) {
            ConfigurationSection a = sec.getConfigurationSection(key);
            List<String> cmds = a.isList("commands") ? a.getStringList("commands") : listOf(a.getString("commands"));
            return new Alias(cmds, a.getString("permission", ""), a.getString("description", "Alias: /" + name));
        }
        List<String> cmds = sec.isList(key) ? sec.getStringList(key) : listOf(sec.getString(key));
        return new Alias(cmds, "", "Alias für /" + String.join(", /", cmds));
    }

    private static List<String> listOf(String command) {
        return command == null || command.isBlank() ? List.of() : List.of(command);
    }

    private boolean allowed(CommandSender sender, String name) {
        Alias alias = alias(name);
        return alias != null && (alias.permission().isEmpty() || sender.hasPermission(alias.permission()));
    }

    private int run(CommandSourceStack source, String name, String rawArgs) {
        CommandSender sender = source.getExecutor() instanceof Player p ? p : source.getSender();
        Alias alias = alias(name);
        if (alias == null) {
            sender.sendMessage(msg("removed", sender, TextService.placeholder("alias", name)));
            return 0;
        }
        String[] args = rawArgs.isBlank() ? new String[0] : rawArgs.strip().split("\\s+");
        // erst alle Zeilen auflösen, damit bei fehlenden Argumenten nichts halb ausgeführt wird
        List<String> lines = new ArrayList<>();
        for (String line : alias.commands()) {
            boolean console = line.startsWith(CONSOLE_PREFIX);
            String body = (console ? line.substring(CONSOLE_PREFIX.length()) : line).strip().replaceFirst("^/+", "");
            String expanded = expand(body, args, sender, console);
            if (expanded == null) {
                sender.sendMessage(msg(console ? "unsafe-argument" : "missing-argument", sender, TextService.placeholder("alias", name)));
                return 0;
            }
            lines.add((console ? CONSOLE_PREFIX : "") + expanded);
        }
        // nach /sessentials reload könnte ein Alias jetzt einen anderen aufrufen – dann gar nichts ausführen
        if (alias.commands().stream().anyMatch(this::callsAlias)) {
            plugin.getLogger().warning("Alias /" + name + " ruft einen anderen Alias auf – nicht ausgeführt.");
            sender.sendMessage(msg("nested", sender, TextService.placeholder("alias", name)));
            return 0;
        }
        for (String line : lines) {
            if (line.startsWith(CONSOLE_PREFIX)) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.substring(CONSOLE_PREFIX.length()));
            } else if (sender instanceof Player p) {
                // wie selbst getippt: mit eigenen Rechten, Mute-Sperren und Befehls-Protokoll
                TypedCommand.run(p, line);
            } else {
                Bukkit.dispatchCommand(sender, line);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    /** Ist das erste Wort des Befehls (ohne Namespace wie "sessentials:") ein Alias? */
    private boolean callsAlias(String line) {
        String body = (line.startsWith(CONSOLE_PREFIX) ? line.substring(CONSOLE_PREFIX.length()) : line).strip().replaceFirst("^/+", "");
        String label = body.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        label = label.substring(label.indexOf(':') + 1);
        return alias(label) != null;
    }

    /** Setzt $1, $2, $1- und &lt;player&gt; ein; {@code null}, wenn ein Argument fehlt oder (Konsole) unsicher ist. */
    private static String expand(String command, String[] args, CommandSender sender, boolean console) {
        if (console) {
            for (String arg : args) if (!SAFE_ARG.matcher(arg).matches()) return null;
        }
        Matcher m = PLACEHOLDER.matcher(command.replace("<player>", sender.getName()));
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            boolean rest = !m.group(2).isEmpty();
            String value;
            if (index < 1 || index > args.length) {
                if (!rest) return null;
                value = ""; // "$1-" darf leer sein
            } else {
                value = rest ? String.join(" ", Arrays.copyOfRange(args, index - 1, args.length)) : args[index - 1];
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString().strip().replaceAll("\\s{2,}", " ");
    }

    private Component msg(String key, CommandSender sender, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("command-aliases-messages." + key, ""),
                sender instanceof Player p ? p : null, extra);
    }
}
