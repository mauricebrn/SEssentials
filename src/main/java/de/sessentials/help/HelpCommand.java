package de.sessentials.help;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.sessentials.SEssentials;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /help [seite]: Hilfeseiten aus der Config. Zeilen können an ein Recht gebunden sein;
 * Seiten ohne sichtbare Zeile werden übersprungen.
 */
public final class HelpCommand {

    /** Eine Zeile; {@code permission == null} = für alle sichtbar. */
    private record Line(String text, String permission) {}

    private final SEssentials plugin;
    private volatile List<List<Line>> pages = List.of();

    public HelpCommand(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Ohne explizite Defaults lesen: fehlende Keys in älteren Configs fallen auf die config.yml im JAR zurück. */
    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        List<List<Line>> parsed = new ArrayList<>();
        List<?> configured = cfg.getList("help.pages");
        for (Object page : configured == null ? List.of() : configured) {
            if (!(page instanceof List<?> entries)) continue;
            List<Line> lines = new ArrayList<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("text") != null) {
                    Object perm = map.get("permission");
                    lines.add(new Line(String.valueOf(map.get("text")), perm == null ? null : String.valueOf(perm)));
                } else if (entry != null && !(entry instanceof Map<?, ?>)) {
                    lines.add(new Line(String.valueOf(entry), null));
                }
            }
            parsed.add(List.copyOf(lines));
        }
        pages = List.copyOf(parsed);
    }

    public void register(Commands commands) {
        // "?" als eigener Befehl: als Alias würde er gegen Bukkits /? verlieren
        for (String label : List.of("help", "?")) {
            commands.register(
                    Commands.literal(label)
                            .executes(ctx -> {
                                show(ctx.getSource().getSender(), 1);
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.argument("seite", IntegerArgumentType.integer(1))
                                    .executes(ctx -> {
                                        show(ctx.getSource().getSender(), IntegerArgumentType.getInteger(ctx, "seite"));
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .build(),
                    "Zeigt die Hilfe",
                    List.of());
        }
    }

    public void show(CommandSender sender, int requestedPage) {
        Player player = sender instanceof Player p ? p : null;
        List<List<Line>> visible = new ArrayList<>();
        for (List<Line> page : pages) {
            List<Line> lines = page.stream()
                    .filter(l -> l.permission() == null || sender.hasPermission(l.permission()))
                    .toList();
            if (!lines.isEmpty()) visible.add(lines);
        }
        if (visible.isEmpty()) {
            sender.sendMessage(render("empty", player));
            return;
        }

        int total = visible.size();
        int page = Math.max(1, Math.min(requestedPage, total));
        TagResolver pageTags = TagResolver.resolver(
                Placeholder.unparsed("page", String.valueOf(page)),
                Placeholder.unparsed("pages", String.valueOf(total)));

        sender.sendMessage(render("header", player, pageTags));
        for (Line line : visible.get(page - 1)) {
            sender.sendMessage(plugin.text().render(line.text(), player, pageTags));
        }
        Component prev = page > 1 ? render("previous", player, Placeholder.unparsed("target", String.valueOf(page - 1))) : Component.empty();
        Component next = page < total ? render("next", player, Placeholder.unparsed("target", String.valueOf(page + 1))) : Component.empty();
        sender.sendMessage(render("footer", player, pageTags, Placeholder.component("previous", prev), Placeholder.component("next", next)));
    }

    private Component render(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("help." + key), player, extra);
    }
}
