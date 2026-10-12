package de.sessentials.player;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.sessentials.SEssentials;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * /fly, /heal, /feed und /god – jeweils für sich selbst ({@code sess.<befehl>})
 * oder einen anderen Spieler ({@code sess.<befehl>.others}).
 */
public final class PlayerCommands implements Listener {

    private final SEssentials plugin;
    // God-Modus gilt nur bis zum Verlassen des Servers
    private final Set<UUID> god = ConcurrentHashMap.newKeySet();

    public PlayerCommands(SEssentials plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        register(commands, "fly", "Flugmodus ein/aus", this::fly);
        register(commands, "heal", "Volle Gesundheit", this::heal);
        register(commands, "feed", "Hunger stillen", this::feed);
        register(commands, "god", "Unverwundbarkeit ein/aus", this::god);
    }

    /** {@code action} wendet den Befehl an und liefert den Nachrichten-Key für den Zielspieler. */
    private void register(Commands commands, String name, String description, Function<Player, String> action) {
        String perm = "sess." + name;
        commands.register(
                Commands.literal(name)
                        .requires(src -> src.getSender().hasPermission(perm))
                        .executes(ctx -> {
                            if (!(ctx.getSource().getExecutor() instanceof Player self)) {
                                ctx.getSource().getSender().sendMessage(render("player-only", null, Placeholder.unparsed("command", name)));
                                return 0;
                            }
                            run(ctx.getSource().getSender(), self, name, action);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.argument("spieler", ArgumentTypes.player())
                                .requires(src -> src.getSender().hasPermission(perm + ".others"))
                                .executes(ctx -> {
                                    run(ctx.getSource().getSender(), target(ctx), name, action);
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .build(),
                description,
                List.of());
    }

    private static Player target(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getArgument("spieler", PlayerSelectorArgumentResolver.class).resolve(ctx.getSource()).getFirst();
    }

    private void run(CommandSender sender, Player target, String name, Function<Player, String> action) {
        target.sendMessage(render(action.apply(target), target));
        if (sender != target) sender.sendMessage(render("other", target, Placeholder.unparsed("command", name)));
    }

    private String fly(Player player) {
        boolean on = !player.getAllowFlight();
        player.setAllowFlight(on);
        if (!on) player.setFlying(false);
        return on ? "fly-on" : "fly-off";
    }

    private String heal(Player player) {
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        player.setFireTicks(0);
        return "heal";
    }

    private String feed(Player player) {
        player.setFoodLevel(20);
        player.setSaturation(10f);
        player.setExhaustion(0f);
        return "feed";
    }

    private String god(Player player) {
        boolean on = god.add(player.getUniqueId());
        if (!on) god.remove(player.getUniqueId());
        return on ? "god-on" : "god-off";
    }

    private Component render(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("player-commands.messages." + key), player, extra);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && god.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        god.remove(event.getPlayer().getUniqueId());
    }
}
