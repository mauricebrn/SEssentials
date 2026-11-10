package de.sessentials.tag;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Admin-Panel: Prefix/Suffix von Online-Spielern setzen oder entfernen. Ohne {@code .others}-Recht nur für sich selbst.
 * Die Eingabe läuft über den Chat ("<text> [zeit]") und dann über dieselbe Logik wie /prefix bzw. /suffix.
 */
public final class TagMenu {

    public static final String PERM_PREFIX = "sess.prefix";
    public static final String PERM_SUFFIX = "sess.suffix";

    private final SEssentials plugin;
    private final TagOverrides tags;

    public TagMenu(SEssentials plugin, TagOverrides tags) {
        this.plugin = plugin;
        this.tags = tags;
    }

    public static boolean canUse(Player p) {
        return p.hasPermission(PERM_PREFIX) || p.hasPermission(PERM_SUFFIX);
    }

    private static boolean others(Player p) {
        return p.hasPermission(PERM_PREFIX + ".others") || p.hasPermission(PERM_SUFFIX + ".others");
    }

    /** Liste der Online-Spieler (bzw. nur man selbst). */
    public void openList(Player viewer) {
        List<Player> players = new ArrayList<>();
        if (others(viewer)) {
            for (Player p : Bukkit.getOnlinePlayers()) if (viewer.canSee(p)) players.add(p);
            players.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        } else {
            players.add(viewer);
        }
        int rows = Math.min(6, Math.max(2, (Math.min(players.size(), 45) + 8) / 9 + 1));
        Menu menu = new Menu(rows, Component.text("Prefix & Suffix", NamedTextColor.GOLD));
        for (int i = 0; i < players.size() && i < (rows - 1) * 9; i++) {
            Player target = players.get(i);
            menu.set(i, head(target), p -> openPlayer(p, target.getName()));
        }
        menu.set((rows - 1) * 9 + 4, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.fillEmpty();
        menu.refreshWith(this::openList);
        menu.open(viewer);
    }

    private void openPlayer(Player viewer, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            openList(viewer);
            return;
        }
        Menu menu = new Menu(3, Component.text("Prefix & Suffix: " + targetName, NamedTextColor.GOLD));
        menu.set(4, head(target), p -> {});
        if (viewer.hasPermission(PERM_PREFIX)) {
            menu.set(10, Menu.icon(Material.NAME_TAG, Component.text("Prefix setzen", NamedTextColor.GREEN),
                    hint()), p -> ask(p, "prefix", targetName));
            menu.set(11, Menu.icon(Material.BARRIER, Component.text("Prefix entfernen", NamedTextColor.RED)), p -> {
                tags.clear(p, "prefix", targetName);
                openPlayer(p, targetName);
            });
        }
        if (viewer.hasPermission(PERM_SUFFIX)) {
            menu.set(15, Menu.icon(Material.NAME_TAG, Component.text("Suffix setzen", NamedTextColor.GREEN),
                    hint()), p -> ask(p, "suffix", targetName));
            menu.set(16, Menu.icon(Material.BARRIER, Component.text("Suffix entfernen", NamedTextColor.RED)), p -> {
                tags.clear(p, "suffix", targetName);
                openPlayer(p, targetName);
            });
        }
        menu.set(22, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), this::openList);
        menu.fillEmpty();
        menu.refreshWith(p -> openPlayer(p, targetName));
        menu.open(viewer);
    }

    private void ask(Player viewer, String kind, String targetName) {
        viewer.closeInventory();
        viewer.sendMessage(plugin.text().render(plugin.getConfig().getString("tags.messages.prompt", ""), viewer,
                TextService.placeholder("kind", kind), TextService.placeholder("target", targetName)));
        plugin.chatPrompt().ask(viewer, input -> {
            tags.set(viewer, kind, targetName, input);
            openPlayer(viewer, targetName);
        }, () -> openPlayer(viewer, targetName));
    }

    private static Component hint() {
        return Component.text("Eingabe im Chat: <text> [zeit]", NamedTextColor.GRAY);
    }

    private ItemStack head(Player target) {
        String prefix = tags.own("prefix", target.getUniqueId());
        String suffix = tags.own("suffix", target.getUniqueId());
        ItemStack head = Menu.icon(Material.PLAYER_HEAD, Component.text(target.getName(), NamedTextColor.WHITE),
                line("Prefix", prefix), line("Suffix", suffix));
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(target));
        return head;
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(value == null ? Component.text("–", NamedTextColor.DARK_GRAY) : TextService.parse(value));
    }
}
