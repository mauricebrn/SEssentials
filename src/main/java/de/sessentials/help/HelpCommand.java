package de.sessentials.help;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.player.TypedCommand;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /help: Menü mit Kategorien aus der Config, darin die Befehle als Kacheln. Ein Klick führt den Befehl aus,
 * schreibt ihn zum Ergänzen in den Chat oder zeigt einen Link. Kategorien und Einträge können an ein Recht
 * gebunden sein. Die Konsole bekommt dieselbe Hilfe als Text.
 */
public final class HelpCommand {

    private enum Click { RUN, SUGGEST, URL, NONE }

    /** Ein Eintrag; {@code permission} leer = für alle sichtbar. */
    private record Entry(String command, String name, String description, Material icon, Click click, String url, String permission) {}

    private record Category(String name, List<String> description, Material icon, String permission, List<Entry> entries) {}

    private final SEssentials plugin;
    private volatile List<Category> categories = List.of();

    public HelpCommand(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        List<Category> parsed = new ArrayList<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("help.categories");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                ConfigurationSection c = sec.getConfigurationSection(key);
                if (c == null) continue;
                List<Entry> entries = new ArrayList<>();
                for (Map<?, ?> e : c.getMapList("commands")) {
                    String command = string(e, "command");
                    String url = string(e, "url");
                    Click click = click(string(e, "click"), url, key);
                    entries.add(new Entry(command, string(e, "name").isEmpty() ? command : string(e, "name"),
                            string(e, "description"), icon(string(e, "icon"), Material.PAPER, key), click, url, string(e, "permission")));
                }
                parsed.add(new Category(c.getString("name", key), c.getStringList("description"),
                        icon(c.getString("icon"), Material.BOOK, key), c.getString("permission", ""), List.copyOf(entries)));
            }
        }
        categories = List.copyOf(parsed);
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private Click click(String value, String url, String category) {
        if (value.isEmpty()) return url.isEmpty() ? Click.SUGGEST : Click.URL;
        try {
            return Click.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("help.categories." + category + ": click '" + value + "' gibt es nicht (run, suggest, url, none).");
            return Click.NONE;
        }
    }

    private Material icon(String name, Material fallback, String category) {
        if (name == null || name.isEmpty()) return fallback;
        Material m = Material.matchMaterial(name);
        if (m != null && m.isItem()) return m;
        plugin.getLogger().warning("help.categories." + category + ": icon '" + name + "' ist kein Item.");
        return fallback;
    }

    public void register(Commands commands) {
        // "?" als eigener Befehl: als Alias würde er gegen Bukkits /? verlieren
        for (String label : List.of("help", "?")) {
            commands.register(
                    Commands.literal(label)
                            .executes(ctx -> {
                                CommandSender sender = ctx.getSource().getExecutor() instanceof Player p ? p : ctx.getSource().getSender();
                                if (sender instanceof Player p) open(p);
                                else showText(sender);
                                return Command.SINGLE_SUCCESS;
                            })
                            .build(),
                    "Zeigt die Hilfe",
                    List.of());
        }
    }

    private List<Category> visible(CommandSender sender) {
        List<Category> out = new ArrayList<>();
        for (Category c : categories) {
            if (!allowed(sender, c.permission())) continue;
            List<Entry> entries = c.entries().stream().filter(e -> allowed(sender, e.permission()) && exists(e)).toList();
            if (!entries.isEmpty()) out.add(new Category(c.name(), c.description(), c.icon(), c.permission(), entries));
        }
        return out;
    }

    /** Befehle ausgeschalteter Module (oder fehlender Plugins) nicht anbieten. */
    private static boolean exists(Entry e) {
        if (e.command().isBlank()) return true;
        String label = e.command().strip().replaceFirst("^/+", "").split("\\s+", 2)[0];
        return Bukkit.getCommandMap().getCommand(label) != null;
    }

    private static boolean allowed(CommandSender sender, String permission) {
        return permission.isEmpty() || sender.hasPermission(permission);
    }

    // ------------------------------------------------------------
    // Menü
    // ------------------------------------------------------------

    public void open(Player player) {
        List<Category> visible = visible(player);
        if (visible.isEmpty()) {
            player.sendMessage(msg("empty", player));
            return;
        }
        // nur eine Kategorie: gleich ihre Befehle zeigen
        if (visible.size() == 1) {
            openCategory(player, visible.getFirst(), false);
            return;
        }
        int rows = Math.min(6, (visible.size() + 8) / 9 + 2);
        Menu menu = new Menu(rows, msg("title", player));
        int first = visible.size() < 9 ? 9 + (9 - visible.size()) / 2 : 9;
        for (int i = 0; i < visible.size() && first + i < (rows - 1) * 9; i++) {
            Category c = visible.get(i);
            List<Component> lore = new ArrayList<>(c.description().stream().map(line -> text(line, player)).toList());
            lore.add(Component.empty());
            lore.add(Component.text(c.entries().size() + " Befehle", NamedTextColor.DARK_GRAY));
            menu.set(first + i, Menu.icon(c.icon(), text(c.name(), player), lore.toArray(Component[]::new)),
                    p -> openCategory(p, c, true));
        }
        menu.set(rows * 9 - 5, Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory);
        menu.fillEmpty();
        menu.open(player);
    }

    private void openCategory(Player player, Category category, boolean back) {
        List<Entry> entries = category.entries();
        int rows = Math.min(6, (entries.size() + 8) / 9 + 1);
        Menu menu = new Menu(rows, text(category.name(), player));
        for (int i = 0; i < entries.size() && i < (rows - 1) * 9; i++) {
            Entry e = entries.get(i);
            List<Component> lore = new ArrayList<>();
            if (!e.description().isEmpty()) lore.add(text(e.description(), player));
            if (!e.command().isEmpty() && !e.name().equals(e.command())) lore.add(Component.text(e.command(), NamedTextColor.DARK_GRAY));
            String hint = switch (e.click()) {
                case RUN -> "Klick » ausführen";
                case SUGGEST -> "Klick » in den Chat schreiben";
                case URL -> "Klick » Link öffnen";
                case NONE -> null;
            };
            if (hint != null) {
                lore.add(Component.empty());
                lore.add(Component.text(hint, NamedTextColor.YELLOW));
            }
            menu.set(i, Menu.icon(e.icon(), text(e.name(), player), lore.toArray(Component[]::new)), p -> click(p, e));
        }
        int bottom = (rows - 1) * 9;
        if (back) menu.set(bottom, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), this::open);
        menu.set(bottom + 4, Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory);
        menu.fillEmpty();
        menu.open(player);
    }

    private void click(Player player, Entry e) {
        switch (e.click()) {
            case RUN -> {
                player.closeInventory();
                String command = e.command().startsWith("/") ? e.command() : "/" + e.command();
                // wie selbst getippt: mit den eigenen Rechten
                TypedCommand.run(player, command);
            }
            case SUGGEST -> {
                player.closeInventory();
                // ein Menü kann den Chat nicht vorbelegen – deshalb ein Klick-Text, der das übernimmt
                player.sendMessage(msg("suggest", player, TextService.placeholder("command", e.command().strip() + " ")));
            }
            case URL -> {
                player.closeInventory();
                player.sendMessage(msg("link", player, TextService.placeholder("url", e.url())));
            }
            case NONE -> {
            }
        }
    }

    // ------------------------------------------------------------
    // Text (Konsole)
    // ------------------------------------------------------------

    private void showText(CommandSender sender) {
        List<Category> visible = visible(sender);
        if (visible.isEmpty()) {
            sender.sendMessage(msg("empty", null));
            return;
        }
        for (Category c : visible) {
            sender.sendMessage(text(c.name(), null));
            for (Entry e : c.entries()) {
                sender.sendMessage(msg("text-entry", null,
                        Placeholder.component("name", text(e.name(), null)),
                        Placeholder.component("description", text(e.description(), null))));
            }
        }
    }

    /** Nachricht aus {@code help.<key>}. */
    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("help." + key, ""), player, extra);
    }

    /** Text aus einer Kategorie oder einem Eintrag. */
    private Component text(String text, Player player) {
        return plugin.text().render(text, player);
    }
}
