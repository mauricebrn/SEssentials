package de.sessentials.bank;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** /bank, /balance, /pay, /baltop und /eco (Admin). */
public final class BankCommands {

    static final String PERM_USE = "sess.bank";
    static final String PERM_PAY = "sess.bank.pay";
    static final String PERM_ADMIN = "sess.bank.admin";

    private final SEssentials plugin;
    private final BankManager bank;
    private final BankMenu menu;

    public BankCommands(SEssentials plugin, BankManager bank, BankMenu menu) {
        this.plugin = plugin;
        this.bank = bank;
        this.menu = menu;
        menu.setCommands(this);
    }

    // ------------------------------------------------------------
    // Aktionen (auch aus dem Menü)
    // ------------------------------------------------------------

    void depositAll(Player player) {
        long value = plugin.bankExchange().takeAll(player);
        if (value == 0) {
            player.sendMessage(msg("nothing-to-deposit", player));
            return;
        }
        bank.deposit(player.getUniqueId(), value);
        player.sendMessage(msg("deposited", player, money(value)));
    }

    void depositHand(Player player) {
        long value = plugin.bankExchange().takeHand(player);
        if (value == 0) {
            player.sendMessage(msg("nothing-in-hand", player));
            return;
        }
        bank.deposit(player.getUniqueId(), value);
        player.sendMessage(msg("deposited", player, money(value)));
    }

    void withdraw(Player player, long amount) {
        BankExchange exchange = plugin.bankExchange();
        if (!exchange.withdrawEnabled()) {
            player.sendMessage(msg("withdraw-disabled", player));
            return;
        }
        BankExchange.Payout payout = exchange.split(amount);
        if (payout.value() == 0) {
            player.sendMessage(msg("withdraw-too-small", player, money(exchange.smallestUnit())));
            return;
        }
        if (!bank.withdraw(player.getUniqueId(), payout.value())) {
            player.sendMessage(msg("not-enough", player, money(amount)));
            return;
        }
        BankExchange.give(player, payout.items());
        player.sendMessage(msg(payout.value() < amount ? "withdrawn-partial" : "withdrawn", player, money(payout.value())));
    }

    private void pay(Player sender, String targetName, long amount) {
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            sender.sendMessage(msg("unknown-player", sender, TextService.placeholder("target", targetName)));
            return;
        }
        TagResolver tags = TagResolver.resolver(money(amount),
                TextService.placeholder("target", target.getName() != null ? target.getName() : targetName),
                TextService.placeholder("sender", sender.getName()));
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(msg("pay-self", sender));
            return;
        }
        bank.ensureAccount(target);
        if (!bank.transfer(sender.getUniqueId(), target.getUniqueId(), amount)) {
            sender.sendMessage(msg("not-enough", sender, tags));
            return;
        }
        sender.sendMessage(msg("paid", sender, tags));
        if (target.getPlayer() != null) target.getPlayer().sendMessage(msg("received", target.getPlayer(), tags));
    }

    private void balance(CommandSender sender, String targetName) {
        Player self = sender instanceof Player p ? p : null;
        if (targetName == null) {
            if (self == null) return;
            sender.sendMessage(msg("balance", self));
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null || !bank.hasAccount(target.getUniqueId())) {
            sender.sendMessage(msg("unknown-player", self, TextService.placeholder("target", targetName)));
            return;
        }
        sender.sendMessage(msg("balance-other", self,
                TextService.placeholder("target", target.getName() != null ? target.getName() : targetName),
                TextService.placeholder("balance", bank.format(bank.balance(target.getUniqueId())))));
    }

    private void baltop(CommandSender sender) {
        Player self = sender instanceof Player p ? p : null;
        sender.sendMessage(msg("baltop-header", self));
        int rank = 1;
        for (BankManager.Account account : bank.top(10)) {
            sender.sendMessage(msg("baltop-entry", self,
                    TextService.placeholder("rank", String.valueOf(rank++)),
                    TextService.placeholder("name", account.name()),
                    TextService.placeholder("balance", bank.format(account.balance()))));
        }
    }

    /** /eco und das Admin-Menü: give, take, set. */
    void adjust(CommandSender sender, OfflinePlayer target, char op, long amount) {
        bank.ensureAccount(target);
        switch (op) {
            case '+' -> bank.deposit(target.getUniqueId(), amount);
            case '-' -> bank.set(target.getUniqueId(), bank.balance(target.getUniqueId()) - amount);
            default -> bank.set(target.getUniqueId(), amount);
        }
        sender.sendMessage(msg("admin-updated", sender instanceof Player p ? p : null,
                TextService.placeholder("target", String.valueOf(target.getName())),
                TextService.placeholder("balance", bank.format(bank.balance(target.getUniqueId())))));
    }

    // ------------------------------------------------------------
    // Nachrichten
    // ------------------------------------------------------------

    /** {@code <balance>} = Kontostand von {@code player}, sofern {@code extra} keinen eigenen liefert. */
    Component msg(String key, Player player, TagResolver... extra) {
        TagResolver[] all = new TagResolver[extra.length + 1];
        // zuerst die übergebenen Platzhalter: bei gleichem Namen gewinnt der erste Treffer
        System.arraycopy(extra, 0, all, 0, extra.length);
        all[extra.length] = player == null ? TagResolver.empty()
                : TextService.placeholder("balance", bank.format(bank.balance(player.getUniqueId())));
        return plugin.text().render(plugin.getConfig().getString("bank.messages." + key), player, all);
    }

    /** {@code <amount>} als formatierter Betrag, z.B. "1.250 Taler". */
    TagResolver money(long amount) {
        return TextService.placeholder("amount", bank.format(amount));
    }

    // ------------------------------------------------------------
    // Registrierung
    // ------------------------------------------------------------

    private static Player player(CommandContext<CommandSourceStack> ctx) {
        return (Player) ctx.getSource().getExecutor();
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("bank")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_USE))
                        .executes(ctx -> {
                            menu.openAccount(player(ctx));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("einzahlen")
                                .executes(ctx -> {
                                    depositAll(player(ctx));
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.literal("hand").executes(ctx -> {
                                    depositHand(player(ctx));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(Commands.literal("auszahlen")
                                .then(Commands.argument("betrag", LongArgumentType.longArg(1)).executes(ctx -> {
                                    withdraw(player(ctx), LongArgumentType.getLong(ctx, "betrag"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .build(),
                "Öffnet deine Bank",
                List.of());

        commands.register(
                Commands.literal("balance")
                        .executes(ctx -> {
                            balance(ctx.getSource().getSender(), null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("spieler", StringArgumentType.word()).suggests(plugin.knownPlayers().suggestions())
                                .executes(ctx -> {
                                    balance(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(),
                "Zeigt den Kontostand",
                List.of("bal", "money", "geld", "kontostand"));

        commands.register(
                Commands.literal("pay")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_PAY))
                        .then(Commands.argument("spieler", StringArgumentType.word()).suggests(plugin.knownPlayers().suggestions())
                                .then(Commands.argument("betrag", LongArgumentType.longArg(1)).executes(ctx -> {
                                    pay(player(ctx), StringArgumentType.getString(ctx, "spieler"), LongArgumentType.getLong(ctx, "betrag"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .build(),
                "Überweist einem Spieler Geld",
                List.of("zahlen"));

        commands.register(
                Commands.literal("baltop")
                        .executes(ctx -> {
                            baltop(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Zeigt die reichsten Spieler",
                List.of());

        var eco = Commands.literal("eco").requires(src -> src.getSender().hasPermission(PERM_ADMIN));
        for (String[] op : new String[][]{{"give", "+"}, {"take", "-"}, {"set", "="}}) {
            eco.then(Commands.literal(op[0])
                    .then(Commands.argument("spieler", StringArgumentType.word()).suggests(plugin.knownPlayers().suggestions())
                            .then(Commands.argument("betrag", LongArgumentType.longArg(0)).executes(ctx -> {
                                CommandSender sender = ctx.getSource().getSender();
                                String name = StringArgumentType.getString(ctx, "spieler");
                                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
                                if (target == null) {
                                    sender.sendMessage(msg("unknown-player", sender instanceof Player p ? p : null,
                                            TextService.placeholder("target", name)));
                                    return 0;
                                }
                                adjust(sender, target, op[1].charAt(0), LongArgumentType.getLong(ctx, "betrag"));
                                return Command.SINGLE_SUCCESS;
                            }))));
        }
        commands.register(eco.build(), "Konten verwalten (give, take, set)", List.of());
    }
}
