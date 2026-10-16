package de.sessentials.bounty;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.text.Durations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Kopfgeld-Verwaltung im Admin-Panel: Ziele ansehen, einzelne Gebote oder alle entfernen (mit Rückerstattung). */
public final class BountyMenu {

    private final SEssentials plugin;
    private final BountyManager bounties;

    public BountyMenu(SEssentials plugin, BountyManager bounties) {
        this.plugin = plugin;
        this.bounties = bounties;
    }

    public void openTargets(Player player) {
        Menu menu = new Menu(6, Component.text("Kopfgelder", NamedTextColor.DARK_GRAY));
        List<BountyManager.Target> targets = bounties.targets();
        for (int i = 0; i < Math.min(45, targets.size()); i++) {
            BountyManager.Target t = targets.get(i);
            ItemStack head = head(t.id(), Component.text(BountyManager.nameOf(t.id(), "?"), NamedTextColor.WHITE, TextDecoration.BOLD),
                    line("Kopfgeld", plugin.bank().format(t.total())),
                    line("Gebote", String.valueOf(bounties.bountiesOn(t.id()).size())),
                    Component.empty(),
                    Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » Gebote verwalten", NamedTextColor.GRAY)));
            menu.set(i, head, p -> openTarget(p, t.id()));
        }
        if (targets.isEmpty()) {
            menu.set(22, Menu.icon(Material.BARRIER, Component.text("Kein Kopfgeld ausgesetzt", NamedTextColor.GRAY)), p -> {});
        }
        menu.set(49, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.fillEmpty();
        menu.open(player);
    }

    private void openTarget(Player player, UUID target) {
        Map<UUID, BountyManager.Bounty> open = bounties.bountiesOn(target);
        if (open.isEmpty()) {
            openTargets(player);
            return;
        }
        String name = BountyManager.nameOf(target, "?");
        Menu menu = new Menu(6, Component.text("Kopfgeld auf " + name, NamedTextColor.DARK_GRAY));
        int slot = 0;
        for (Map.Entry<UUID, BountyManager.Bounty> e : open.entrySet()) {
            if (slot >= 45) break;
            UUID setter = e.getKey();
            Duration remaining = bounties.remaining(e.getValue());
            menu.set(slot++, head(setter, Component.text(BountyManager.nameOf(setter, "?"), NamedTextColor.WHITE, TextDecoration.BOLD),
                            line("Betrag", plugin.bank().format(e.getValue().amount())),
                            line("Läuft ab", remaining == null ? "nie" : "in " + Durations.format(remaining)),
                            Component.empty(),
                            Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » entfernen und erstatten", NamedTextColor.GRAY))),
                    p -> {
                        bounties.remove(p, target, setter);
                        openTarget(p, target);
                    });
        }
        menu.set(45, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), this::openTargets);
        menu.set(53, Menu.icon(Material.LAVA_BUCKET, Component.text("Alle entfernen", NamedTextColor.RED, TextDecoration.BOLD),
                line("Gesamt", plugin.bank().format(bounties.total(target))),
                Component.text("Alle Gebote werden erstattet", NamedTextColor.GRAY)), p -> {
            bounties.removeAll(p, target);
            openTargets(p);
        });
        menu.fillEmpty();
        menu.open(player);
    }

    private static ItemStack head(UUID id, Component name, Component... lore) {
        ItemStack head = Menu.icon(Material.PLAYER_HEAD, name, lore);
        head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(id)));
        return head;
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
