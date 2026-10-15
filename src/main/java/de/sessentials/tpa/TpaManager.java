package de.sessentials.tpa;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import de.sessentials.cost.Cost;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * /tpa <spieler>, /tpaccept [spieler], /tpdeny [spieler].
 * Der Anfragende zahlt erst bei Annahme (kostenlos mit {@code sess.tpa.free}).
 */
public final class TpaManager implements Listener {

    private static final String PERM_USE = "sess.tpa";
    private static final String PERM_FREE = "sess.tpa.free";

    private final SEssentials plugin;
    /** Ziel → (Anfragender → Ablaufzeit in ms), in Eingangsreihenfolge. */
    private final Map<UUID, LinkedHashMap<UUID, Long>> requests = new HashMap<>();

    private volatile Cost cost;
    private volatile long expireMillis;

    public TpaManager(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Ohne explizite Defaults lesen: fehlende Keys in älteren Configs fallen auf die config.yml im JAR zurück. */
    public void reload() {
        cost = Cost.fromConfig(plugin.getConfig(), "tpa.cost", plugin.bank(), plugin.bankExchange(), plugin.getLogger());
        expireMillis = Math.max(1L, plugin.getConfig().getLong("tpa.expire-seconds")) * 1000L;
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("tpa")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_USE))
                        .then(Commands.argument("spieler", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    Player self = (Player) ctx.getSource().getExecutor();
                                    String typed = builder.getRemainingLowerCase();
                                    for (Player p : Bukkit.getOnlinePlayers()) {
                                        if (p != self && self.canSee(p) && p.getName().toLowerCase().startsWith(typed)) {
                                            builder.suggest(p.getName());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> {
                                    request((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(),
                "Fragt an, dich zu einem Spieler zu teleportieren",
                List.of());
        commands.register(answer("tpaccept", this::accept), "Nimmt eine Teleport-Anfrage an", List.of());
        commands.register(answer("tpdeny", this::deny), "Lehnt eine Teleport-Anfrage ab", List.of());
    }

    /** /tpaccept bzw. /tpdeny – ohne Namen gilt die neueste offene Anfrage. */
    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> answer(String label, BiConsumer<Player, Player> action) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(label)
                .requires(src -> src.getExecutor() instanceof Player)
                .executes(ctx -> {
                    answer((Player) ctx.getSource().getExecutor(), null, action);
                    return Command.SINGLE_SUCCESS;
                })
                .then(Commands.argument("spieler", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            Player self = (Player) ctx.getSource().getExecutor();
                            for (UUID id : openRequests(self).keySet()) {
                                Player p = Bukkit.getPlayer(id);
                                if (p != null) builder.suggest(p.getName());
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            answer((Player) ctx.getSource().getExecutor(), StringArgumentType.getString(ctx, "spieler"), action);
                            return Command.SINGLE_SUCCESS;
                        }));
        return node.build();
    }

    private void request(Player requester, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        // versteckte (Vanish) Spieler gelten als nicht online
        if (target == null || !requester.canSee(target)) {
            requester.sendMessage(msg("not-found", requester, TextService.placeholder("target", targetName)));
            return;
        }
        TagResolver tags = tags(requester, target);
        if (target == requester) {
            requester.sendMessage(msg("self", requester, tags));
            return;
        }
        // früh prüfen, damit niemand umsonst anfragt; abgezogen wird erst bei Annahme
        if (pays(requester) && !cost.canAfford(requester)) {
            requester.sendMessage(msg("not-enough", requester, tags));
            return;
        }
        LinkedHashMap<UUID, Long> open = requests.computeIfAbsent(target.getUniqueId(), id -> new LinkedHashMap<>());
        // erneute Anfrage ans Ende verschieben, damit /tpaccept ohne Namen die neueste trifft
        open.remove(requester.getUniqueId());
        open.put(requester.getUniqueId(), System.currentTimeMillis() + expireMillis);
        requester.sendMessage(msg(pays(requester) ? "sent-paid" : "sent", requester, tags));
        target.sendMessage(msg("received", target, tags));
    }

    private void answer(Player target, String requesterName, BiConsumer<Player, Player> action) {
        Map<UUID, Long> open = openRequests(target);
        Player requester = null;
        if (requesterName == null) {
            // neueste noch gültige Anfrage, deren Absender online ist
            for (UUID id : open.keySet()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) requester = p;
            }
        } else {
            Player p = Bukkit.getPlayerExact(requesterName);
            if (p != null && open.containsKey(p.getUniqueId())) requester = p;
        }
        if (requester == null) {
            target.sendMessage(msg("no-request", target));
            return;
        }
        open.remove(requester.getUniqueId());
        action.accept(target, requester);
    }

    private void accept(Player target, Player requester) {
        TagResolver tags = tags(requester, target);
        Cost cost = this.cost;
        boolean pays = pays(requester);
        if (pays && !cost.take(requester)) {
            requester.sendMessage(msg("not-enough", requester, tags));
            target.sendMessage(msg("requester-cannot-pay", target, tags));
            return;
        }
        target.sendMessage(msg("accepted-target", target, tags));
        requester.teleportAsync(target.getLocation()).thenAccept(ok -> {
            if (!ok) {
                if (pays) cost.refund(requester);
                requester.sendMessage(msg("failed", requester, tags));
                return;
            }
            requester.sendMessage(msg(pays ? "accepted-paid" : "accepted", requester, tags));
        });
    }

    private void deny(Player target, Player requester) {
        TagResolver tags = tags(requester, target);
        target.sendMessage(msg("denied-target", target, tags));
        requester.sendMessage(msg("denied", requester, tags));
    }

    /** Offene, nicht abgelaufene Anfragen an {@code target}; Abgelaufene werden dabei entfernt. */
    private Map<UUID, Long> openRequests(Player target) {
        LinkedHashMap<UUID, Long> open = requests.get(target.getUniqueId());
        if (open == null) return new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        open.values().removeIf(expiry -> expiry < now);
        return open;
    }

    private boolean pays(Player requester) {
        return !requester.hasPermission(PERM_FREE) && !cost.isFree();
    }

    private TagResolver tags(Player requester, Player target) {
        return TagResolver.resolver(
                TextService.placeholder("requester", requester.getName()),
                TextService.placeholder("target", target.getName()),
                TextService.placeholder("seconds", String.valueOf(expireMillis / 1000)),
                cost.tags());
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("tpa.messages." + key), player, extra);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        requests.remove(id);
        requests.values().forEach(open -> open.remove(id));
    }
}
