package de.sessentials.hook;

import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.WeightNode;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Legt die Gruppenstruktur aus {@code luckperms-setup.groups} in LuckPerms an: Gruppen, Gewicht, farbiger Prefix,
 * Elterngruppe und Rechte. Nur ergänzend – vorhandene Gruppen, Rechte und andere Prefixe bleiben erhalten; nur ein
 * Prefix mit derselben Priorität (= Gewicht) wird ersetzt. Mehrfach ausführen ist daher unbedenklich.
 * Wird nur benutzt, wenn LuckPerms installiert ist.
 */
public final class LuckPermsSetup {

    public static final String PERM = "sess.lpsetup";

    /** Eine Gruppe aus der Config. {@code permissions}: "recht" oder "-recht" (= verboten). */
    private record GroupSpec(String name, int weight, String prefix, String parent, List<String> permissions) {}

    private final SEssentials plugin;

    public LuckPermsSetup(SEssentials plugin) {
        this.plugin = plugin;
    }

    private List<GroupSpec> specs() {
        List<GroupSpec> result = new ArrayList<>();
        ConfigurationSection groups = plugin.getConfig().getConfigurationSection("luckperms-setup.groups");
        if (groups == null) return result;
        for (String name : groups.getKeys(false)) {
            ConfigurationSection g = groups.getConfigurationSection(name);
            if (g == null) continue;
            String parent = g.getString("parent", "");
            result.add(new GroupSpec(name.toLowerCase(Locale.ROOT), g.getInt("weight", 0), g.getString("prefix", ""),
                    parent.isBlank() ? null : parent.toLowerCase(Locale.ROOT), g.getStringList("permissions")));
        }
        return result;
    }

    /** Vorschau: was angelegt bzw. ergänzt würde. */
    public void preview(CommandSender sender) {
        List<GroupSpec> specs = specs();
        if (specs.isEmpty()) {
            sender.sendMessage(msg("empty", sender));
            return;
        }
        LuckPerms api = LuckPermsProvider.get();
        sender.sendMessage(msg("preview-header", sender, TextService.placeholder("count", String.valueOf(specs.size()))));
        for (GroupSpec s : specs) {
            boolean exists = api.getGroupManager().getGroup(s.name()) != null;
            sender.sendMessage(msg(exists ? "preview-existing" : "preview-new", sender,
                    TextService.placeholder("group", s.name()),
                    TextService.placeholder("weight", String.valueOf(s.weight())),
                    TextService.placeholder("parent", s.parent() == null ? "–" : s.parent()),
                    TextService.placeholder("permissions", String.valueOf(s.permissions().size())),
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("group_prefix", TextService.parse(s.prefix()))));
        }
        sender.sendMessage(msg("preview-footer", sender));
    }

    /** Wendet die Struktur an (asynchron über die LuckPerms-API) und meldet das Ergebnis im Main-Thread. */
    public void apply(CommandSender sender) {
        List<GroupSpec> specs = specs();
        if (specs.isEmpty()) {
            sender.sendMessage(msg("empty", sender));
            return;
        }
        LuckPerms api = LuckPermsProvider.get();
        sender.sendMessage(msg("applying", sender));
        // nacheinander, damit Elterngruppen vor den Kindern existieren
        CompletableFuture<int[]> chain = CompletableFuture.completedFuture(new int[2]); // [neu, ergänzt]
        for (GroupSpec s : specs) {
            chain = chain.thenCompose(counts -> {
                boolean existed = api.getGroupManager().getGroup(s.name()) != null;
                return api.getGroupManager().createAndLoadGroup(s.name()).thenCompose(group -> {
                    configure(group, s);
                    counts[existed ? 1 : 0]++;
                    return api.getGroupManager().saveGroup(group).thenApply(v -> counts);
                });
            });
        }
        chain.whenComplete((counts, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "LuckPerms-Einrichtung fehlgeschlagen", error);
                sender.sendMessage(msg("failed", sender, TextService.placeholder("error", String.valueOf(error.getMessage()))));
                return;
            }
            api.runUpdateTask();
            plugin.getLogger().info("LuckPerms-Gruppen eingerichtet: " + counts[0] + " neu, " + counts[1] + " ergänzt.");
            sender.sendMessage(msg("done", sender, TextService.placeholder("created", String.valueOf(counts[0])),
                    TextService.placeholder("updated", String.valueOf(counts[1]))));
        }));
    }

    private static void configure(Group group, GroupSpec s) {
        var data = group.data();
        data.clear(NodeType.WEIGHT::matches);
        data.add(WeightNode.builder(s.weight()).build());
        if (!s.prefix().isBlank()) {
            data.clear(NodeType.PREFIX.predicate(n -> n.getPriority() == s.weight()));
            data.add(PrefixNode.builder(s.prefix(), s.weight()).build());
        }
        if (s.parent() != null) data.add(InheritanceNode.builder(s.parent()).build());
        for (String raw : s.permissions()) {
            String perm = raw.trim();
            if (perm.isEmpty()) continue;
            boolean deny = perm.startsWith("-");
            data.add(Node.builder(deny ? perm.substring(1) : perm).value(!deny).build());
        }
    }

    private Component msg(String key, CommandSender sender, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("luckperms-setup.messages." + key, ""),
                sender instanceof org.bukkit.entity.Player p ? p : null, extra);
    }
}
