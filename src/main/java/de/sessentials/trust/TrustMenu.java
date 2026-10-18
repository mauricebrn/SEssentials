package de.sessentials.trust;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Trust-Verwaltung im Admin-Panel: wartende Spieler ansehen, direkt vertrauen oder Stimmen zurücksetzen. */
public final class TrustMenu {

    private final SEssentials plugin;
    private final TrustManager trust;
    private final SuspicionVote suspicion;

    public TrustMenu(SEssentials plugin, TrustManager trust, SuspicionVote suspicion) {
        this.plugin = plugin;
        this.trust = trust;
        this.suspicion = suspicion;
    }

    public void open(Player player) {
        Menu menu = new Menu(6, Component.text("Trust", NamedTextColor.DARK_GRAY));
        List<UUID> candidates = trust.candidates();
        for (int i = 0; i < Math.min(45, candidates.size()); i++) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(candidates.get(i));
            ItemStack head = Menu.icon(Material.PLAYER_HEAD,
                    Component.text(target.getName() != null ? target.getName() : "?", NamedTextColor.WHITE, TextDecoration.BOLD),
                    line("Stimmen", trust.voteCount(target.getUniqueId()) + "/" + trust.votesNeeded()),
                    line("Status", target.isOnline() ? "online" : "offline"),
                    Component.empty(),
                    Component.text("Linksklick", NamedTextColor.YELLOW).append(Component.text(" » vertrauen", NamedTextColor.GRAY)),
                    Component.text("Rechtsklick", NamedTextColor.YELLOW).append(Component.text(" » Stimmen zurücksetzen", NamedTextColor.GRAY)));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(target));
            menu.setClick(i, head, (p, click) -> {
                if (click == ClickType.LEFT) trust.setTrustedBy(p, target, true);
                else if (click == ClickType.RIGHT) trust.resetVotes(p, target);
                open(p);
            });
        }
        if (candidates.isEmpty()) {
            menu.set(22, Menu.icon(Material.BARRIER, Component.text("Niemand wartet auf Vertrauen", NamedTextColor.GRAY)), p -> {});
        }
        menu.set(45, Menu.icon(Material.REDSTONE_TORCH, Component.text("Verdachts-Abstimmungen", NamedTextColor.RED),
                line("Offen", String.valueOf(suspicion.open().size())),
                line("Status", suspicion.enabled() ? "an" : "aus"),
                Component.empty(),
                Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » ansehen", NamedTextColor.GRAY))), this::openSuspicions);
        menu.set(49, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.set(53, Menu.icon(Material.BOOK, Component.text("Einstellungen", NamedTextColor.GOLD),
                line("Abstimmen dürfen", trust.permissionVoters() ? "nur mit sess.trust.vote" : "alle vertrauten Spieler"),
                line("Nötige Stimmen", String.valueOf(trust.votesNeeded())),
                line("Abstimmung beim Join", trust.autoVote() ? "automatisch" : "nur per /trust"),
                line("Automatisch vertraut ab Spielzeit", trust.autoTrustPlaytime()),
                Component.text("Ändern in config/trust.yml", NamedTextColor.GRAY)), p -> {});
        menu.fillEmpty();
        menu.open(player);
    }

    /** Offene Verdachts-Abstimmungen: Stimmen und Abstimmende ansehen, Abstimmung verwerfen. */
    private void openSuspicions(Player player) {
        Menu menu = new Menu(6, Component.text("Verdachts-Abstimmungen", NamedTextColor.DARK_GRAY));
        Map<UUID, List<UUID>> open = suspicion.open();
        int slot = 0;
        for (Map.Entry<UUID, List<UUID>> e : open.entrySet()) {
            if (slot >= 45) break;
            OfflinePlayer target = Bukkit.getOfflinePlayer(e.getKey());
            List<Component> lore = new ArrayList<>();
            lore.add(line("Stimmen", e.getValue().size() + "/" + suspicion.votesNeeded()));
            lore.add(line("Status", target.isOnline() ? "online" : "offline"));
            lore.add(Component.text("Abgestimmt haben:", NamedTextColor.GRAY));
            for (UUID voter : e.getValue()) {
                String name = Bukkit.getOfflinePlayer(voter).getName();
                lore.add(Component.text(" • " + (name != null ? name : "?"), NamedTextColor.WHITE));
            }
            lore.add(Component.empty());
            lore.add(Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » Abstimmung verwerfen", NamedTextColor.GRAY)));
            ItemStack head = Menu.icon(Material.PLAYER_HEAD,
                    Component.text(target.getName() != null ? target.getName() : "?", NamedTextColor.RED, TextDecoration.BOLD),
                    lore.toArray(Component[]::new));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(target));
            menu.set(slot++, head, p -> {
                suspicion.dismiss(p, target);
                openSuspicions(p);
            });
        }
        if (open.isEmpty()) {
            menu.set(22, Menu.icon(Material.BARRIER, Component.text("Keine offenen Abstimmungen", NamedTextColor.GRAY)), p -> {});
        }
        menu.set(49, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), this::open);
        menu.set(53, Menu.icon(Material.BOOK, Component.text("Einstellungen", NamedTextColor.GOLD),
                line("Status", suspicion.enabled() ? "an" : "aus (oder Modul punishments aus)"),
                line("Nötige Stimmen", String.valueOf(suspicion.votesNeeded())),
                line("Ban-Dauer", suspicion.banDuration()),
                Component.text("Ändern in config/trust.yml (suspicion)", NamedTextColor.GRAY)), p -> {});
        menu.fillEmpty();
        menu.open(player);
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
