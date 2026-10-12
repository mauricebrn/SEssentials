package de.sessentials.bank;

import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.text.TextService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /bank-Menü für Spieler und Konten-Verwaltung für Admins. */
public final class BankMenu {

    private static final int[] WITHDRAW_SLOTS = {13, 14, 15};

    private final SEssentials plugin;
    private final BankManager bank;
    private BankCommands commands;

    public BankMenu(SEssentials plugin, BankManager bank) {
        this.plugin = plugin;
        this.bank = bank;
    }

    void setCommands(BankCommands commands) {
        this.commands = commands;
    }

    // ------------------------------------------------------------
    // Spieler: /bank
    // ------------------------------------------------------------

    public void openAccount(Player player) {
        BankExchange exchange = plugin.bankExchange();
        Menu menu = new Menu(3, Component.text("Bank", NamedTextColor.DARK_GRAY));

        menu.set(4, Menu.icon(exchange.base(), Component.text("Kontostand", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(bank.format(bank.balance(player.getUniqueId())), NamedTextColor.WHITE)), p -> {});

        menu.set(10, Menu.icon(Material.CHEST, Component.text("Alles einzahlen", NamedTextColor.GREEN, TextDecoration.BOLD),
                        line("Wert im Inventar", bank.format(exchange.inventoryValue(player))),
                        Component.text("Zahlt alle passenden Items ein.", NamedTextColor.GRAY)),
                commands::depositAll);
        menu.set(11, Menu.icon(Material.HOPPER, Component.text("Hand einzahlen", NamedTextColor.GREEN, TextDecoration.BOLD),
                        Component.text("Zahlt den Stack in deiner Hand ein.", NamedTextColor.GRAY)),
                commands::depositHand);

        if (exchange.withdrawEnabled()) {
            List<Long> presets = plugin.getConfig().getLongList("bank.withdraw.presets");
            for (int i = 0; i < Math.min(presets.size(), WITHDRAW_SLOTS.length); i++) {
                long amount = presets.get(i);
                menu.set(WITHDRAW_SLOTS[i], Menu.icon(Material.GOLD_NUGGET,
                                Component.text(bank.format(amount) + " auszahlen", NamedTextColor.YELLOW, TextDecoration.BOLD)),
                        p -> commands.withdraw(p, amount));
            }
            menu.set(16, Menu.icon(Material.OAK_SIGN, Component.text("Betrag auszahlen", NamedTextColor.YELLOW, TextDecoration.BOLD),
                    Component.text("Betrag im Chat eingeben.", NamedTextColor.GRAY)), p -> {
                p.closeInventory();
                p.sendMessage(commands.msg("prompt-withdraw", p));
                plugin.chatPrompt().ask(p, input -> {
                    long amount = parseAmount(input);
                    if (amount <= 0) {
                        p.sendMessage(commands.msg("invalid-amount", p));
                        return;
                    }
                    commands.withdraw(p, amount);
                    openAccount(p);
                }, () -> p.sendMessage(commands.msg("cancelled", p)));
            });
        }

        List<Component> rates = new ArrayList<>();
        for (Map.Entry<Material, Long> e : exchange.values().entrySet()) {
            rates.add(Component.translatable(e.getKey().translationKey(), NamedTextColor.GRAY)
                    .append(Component.text(" = " + bank.format(e.getValue()), NamedTextColor.WHITE)));
        }
        if (!exchange.withdrawEnabled()) {
            rates.add(Component.empty());
            rates.add(Component.text("Auszahlen ist deaktiviert.", NamedTextColor.RED));
        }
        menu.set(22, Menu.icon(Material.PAPER, Component.text("Kurse", NamedTextColor.AQUA, TextDecoration.BOLD),
                rates.toArray(Component[]::new)), p -> {});
        menu.set(26, Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory);
        menu.fillEmpty();
        menu.refreshWith(this::openAccount);
        menu.open(player);
    }

    // ------------------------------------------------------------
    // Admin: Konten-Verwaltung
    // ------------------------------------------------------------

    public void openAdmin(Player player) {
        Menu menu = new Menu(6, Component.text("Bank – Konten", NamedTextColor.DARK_GRAY));
        int slot = 0;
        for (BankManager.Account account : bank.top(45)) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(account.id());
            ItemStack head = Menu.icon(Material.PLAYER_HEAD, Component.text(account.name(), NamedTextColor.WHITE, TextDecoration.BOLD),
                    line("Kontostand", bank.format(account.balance())),
                    Component.empty(),
                    Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » Kontostand ändern", NamedTextColor.GRAY)));
            head.editMeta(SkullMeta.class, meta -> meta.setOwningPlayer(target));
            menu.set(slot++, head, p -> {
                p.closeInventory();
                p.sendMessage(commands.msg("prompt-admin", p, TextService.placeholder("target", account.name())));
                plugin.chatPrompt().ask(p, input -> {
                    String in = input.replace(" ", "");
                    char op = in.isEmpty() ? '?' : in.charAt(0);
                    long amount = parseAmount(op == '+' || op == '-' || op == '=' ? in.substring(1) : in);
                    if (amount < 0) {
                        p.sendMessage(commands.msg("invalid-amount", p));
                        return;
                    }
                    commands.adjust(p, target, op == '+' || op == '-' ? op : '=', amount);
                    openAdmin(p);
                }, () -> p.sendMessage(commands.msg("cancelled", p)));
            });
        }
        menu.set(45, Menu.icon(Material.GOLD_BLOCK, Component.text("Im Umlauf", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(bank.format(bank.total()), NamedTextColor.WHITE)), p -> {});
        menu.set(49, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.fillEmpty();
        menu.open(player);
    }

    /** Ganze Zahl ≥ 0, Tausenderpunkte erlaubt; -1 bei ungültiger Eingabe. */
    private static long parseAmount(String input) {
        try {
            return Math.max(-1, Long.parseLong(input.replace(".", "").trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }
}
