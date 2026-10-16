package de.sessentials.clan;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Clan-Verwaltung im Admin-Panel: Clans ansehen, Home/Truhe besuchen, Truhe öffnen, Konto, Mitglieder, Auflösen. */
public final class ClanMenu {

    public static final String PERM = ClanCommands.PERM_ADMIN;

    /** Banner in ungefähr der Clan-Farbe. */
    private static final Map<NamedTextColor, Material> BANNERS = Map.ofEntries(
            Map.entry(NamedTextColor.BLACK, Material.BLACK_BANNER), Map.entry(NamedTextColor.DARK_BLUE, Material.BLUE_BANNER),
            Map.entry(NamedTextColor.DARK_GREEN, Material.GREEN_BANNER), Map.entry(NamedTextColor.DARK_AQUA, Material.CYAN_BANNER),
            Map.entry(NamedTextColor.DARK_RED, Material.RED_BANNER), Map.entry(NamedTextColor.DARK_PURPLE, Material.PURPLE_BANNER),
            Map.entry(NamedTextColor.GOLD, Material.ORANGE_BANNER), Map.entry(NamedTextColor.GRAY, Material.LIGHT_GRAY_BANNER),
            Map.entry(NamedTextColor.DARK_GRAY, Material.GRAY_BANNER), Map.entry(NamedTextColor.BLUE, Material.LIGHT_BLUE_BANNER),
            Map.entry(NamedTextColor.GREEN, Material.LIME_BANNER), Map.entry(NamedTextColor.AQUA, Material.LIGHT_BLUE_BANNER),
            Map.entry(NamedTextColor.RED, Material.RED_BANNER), Map.entry(NamedTextColor.LIGHT_PURPLE, Material.MAGENTA_BANNER),
            Map.entry(NamedTextColor.YELLOW, Material.YELLOW_BANNER), Map.entry(NamedTextColor.WHITE, Material.WHITE_BANNER));

    private final SEssentials plugin;
    private final ClanManager clans;
    private final ClanCommands commands;

    public ClanMenu(SEssentials plugin, ClanManager clans, ClanCommands commands) {
        this.plugin = plugin;
        this.clans = clans;
        this.commands = commands;
    }

    public void openList(Player player) {
        Menu menu = new Menu(6, Component.text("Clans", NamedTextColor.DARK_GRAY));
        List<Clan> sorted = clans.all().stream().sorted(Comparator.comparingInt((Clan c) -> c.members.size()).reversed()).toList();
        for (int i = 0; i < Math.min(45, sorted.size()); i++) {
            Clan clan = sorted.get(i);
            menu.set(i, banner(clan, line("Leiter", commands.nameOf(clan.leader)),
                    line("Mitglieder", String.valueOf(clan.members.size())),
                    line("Clan-Konto", plugin.bank().format(clan.balance)),
                    Component.empty(),
                    Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » verwalten", NamedTextColor.GRAY))),
                    p -> openClan(p, clan));
        }
        if (sorted.isEmpty()) {
            menu.set(22, Menu.icon(Material.BARRIER, Component.text("Noch keine Clans", NamedTextColor.GRAY)), p -> {});
        }
        menu.set(49, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.fillEmpty();
        menu.open(player);
    }

    private void openClan(Player player, Clan clan) {
        if (!clans.exists(clan)) {
            openList(player);
            return;
        }
        Menu menu = new Menu(5, Component.text("Clan " + clan.name, NamedTextColor.DARK_GRAY));
        menu.set(4, banner(clan, line("Leiter", commands.nameOf(clan.leader)),
                line("Mitglieder", String.valueOf(clan.members.size())),
                line("Clan-Konto", plugin.bank().format(clan.balance)),
                line("Home", position(clan.home)),
                line("Truhe", !clan.chestBought ? "nicht gekauft" : clan.chestBlock == null ? "nicht aufgestellt" : position(clan.chestBlock))), p -> {});

        menu.set(19, Menu.icon(Material.RED_BED, Component.text("Zum Clan-Home", NamedTextColor.AQUA), line("Position", position(clan.home))),
                live(clan, p -> teleport(p, clan.home)));
        menu.set(20, Menu.icon(Material.COMPASS, Component.text("Zur Clan-Truhe", NamedTextColor.AQUA),
                        line("Position", position(clan.chestBlock))),
                live(clan, p -> teleport(p, clan.chestBlock == null ? null : clan.chestBlock.clone().add(0.5, 1, 0.5))));
        menu.set(21, Menu.icon(Material.ENDER_CHEST, Component.text("Truhe öffnen", NamedTextColor.LIGHT_PURPLE),
                        Component.text("Inhalt ansehen und ändern", NamedTextColor.GRAY)),
                live(clan, p -> commands.openChest(p, clan)));
        menu.set(22, Menu.icon(Material.GOLD_INGOT, Component.text("Clan-Konto", NamedTextColor.GOLD),
                        line("Stand", plugin.bank().format(clan.balance)),
                        Component.empty(),
                        Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » ändern", NamedTextColor.GRAY))),
                live(clan, p -> promptBalance(p, clan)));
        menu.set(23, Menu.icon(Material.PLAYER_HEAD, Component.text("Mitglieder", NamedTextColor.GREEN),
                        line("Anzahl", String.valueOf(clan.members.size())),
                        Component.text("Rauswerfen, Leiter bestimmen", NamedTextColor.GRAY)),
                live(clan, p -> openMembers(p, clan)));
        menu.set(25, Menu.icon(Material.TNT, Component.text("Clan auflösen", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("Konto geht an den Leiter,", NamedTextColor.GRAY),
                        Component.text("Truheninhalt an dich", NamedTextColor.GRAY)),
                live(clan, p -> confirmDisband(p, clan)));
        menu.set(40, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), this::openList);
        menu.fillEmpty();
        menu.open(player);
    }

    private void openMembers(Player player, Clan clan) {
        Menu menu = new Menu(6, Component.text("Mitglieder: " + clan.name, NamedTextColor.DARK_GRAY));
        int slot = 0;
        for (UUID id : clan.members) {
            if (slot >= 45) break;
            boolean leader = clan.isLeader(id);
            ItemStack head = leader
                    ? Menu.icon(Material.PLAYER_HEAD, Component.text(commands.nameOf(id), NamedTextColor.GOLD, TextDecoration.BOLD),
                            Component.text("Leiter", NamedTextColor.GOLD))
                    : Menu.icon(Material.PLAYER_HEAD, Component.text(commands.nameOf(id), NamedTextColor.WHITE, TextDecoration.BOLD),
                            Component.text("Linksklick", NamedTextColor.YELLOW).append(Component.text(" » rauswerfen", NamedTextColor.GRAY)),
                            Component.text("Rechtsklick", NamedTextColor.YELLOW).append(Component.text(" » zum Leiter machen", NamedTextColor.GRAY)));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(Bukkit.getOfflinePlayer(id)));
            menu.setClick(slot++, head, (p, click) -> {
                if (!clans.exists(clan) || !clan.members.contains(id) || clan.isLeader(id)) {
                    openClan(p, clan);
                    return;
                }
                if (click == ClickType.RIGHT) commands.changeLeader(clan, id);
                else if (click == ClickType.LEFT) commands.removeMember(clan, id);
                p.sendMessage(commands.msg("admin-updated", p, commands.clanTags(clan)));
                openMembers(p, clan);
            });
        }
        menu.set(49, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), p -> openClan(p, clan));
        menu.fillEmpty();
        menu.open(player);
    }

    private void confirmDisband(Player player, Clan clan) {
        Menu menu = new Menu(3, Component.text("Clan " + clan.name + " auflösen?", NamedTextColor.DARK_GRAY));
        menu.set(11, Menu.icon(Material.LIME_CONCRETE, Component.text("Ja, auflösen", NamedTextColor.RED, TextDecoration.BOLD)),
                live(clan, p -> {
                    p.closeInventory();
                    commands.adminDisband(p, clan);
                }));
        menu.set(15, Menu.icon(Material.RED_CONCRETE, Component.text("Abbrechen", NamedTextColor.GRAY)), p -> openClan(p, clan));
        menu.fillEmpty();
        menu.open(player);
    }

    /** Wie die Bank: {@code +100}, {@code -50} oder {@code =200} bzw. nur eine Zahl. */
    private void promptBalance(Player player, Clan clan) {
        player.closeInventory();
        player.sendMessage(commands.msg("admin-balance-prompt", player, commands.clanTags(clan)));
        plugin.chatPrompt().ask(player, input -> {
            if (!clans.exists(clan)) return;
            String in = input.replace(" ", "").replace(".", "");
            char op = in.isEmpty() ? '?' : in.charAt(0);
            long amount;
            try {
                amount = Long.parseLong(op == '+' || op == '-' || op == '=' ? in.substring(1) : in);
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount < 0) {
                player.sendMessage(commands.msg("admin-invalid-amount", player));
                return;
            }
            long next = switch (op) {
                case '+' -> Math.addExact(clan.balance, amount);
                case '-' -> clan.balance - amount;
                default -> amount;
            };
            clans.setBalance(clan, next);
            player.sendMessage(commands.msg("admin-balance", player, commands.clanTags(clan),
                    TextService.placeholder("balance", plugin.bank().format(clan.balance))));
            openClan(player, clan);
        }, () -> player.sendMessage(commands.msg("admin-cancelled", player)));
    }

    private void teleport(Player player, Location target) {
        if (target == null) {
            player.sendMessage(commands.msg("admin-no-position", player));
            return;
        }
        player.closeInventory();
        player.teleportAsync(target);
    }

    /** Aktion nur, wenn der Clan noch existiert – sonst zurück zur Liste. */
    private Consumer<Player> live(Clan clan, Consumer<Player> action) {
        return p -> {
            if (clans.exists(clan)) action.accept(p);
            else openList(p);
        };
    }

    private ItemStack banner(Clan clan, Component... lore) {
        return Menu.icon(BANNERS.getOrDefault(clan.color, Material.WHITE_BANNER),
                Component.text(clan.name, clan.color, TextDecoration.BOLD), lore);
    }

    private static String position(Location l) {
        return l == null ? "–" : l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
