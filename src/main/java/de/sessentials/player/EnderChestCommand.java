package de.sessentials.player;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import de.sessentials.cost.Cost;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

import java.util.List;

/** /enderchest (Alias /ec): öffnet die eigene Enderchest – kostenlos mit {@code sess.enderchest.free}, sonst gegen {@code enderchest.cost}. */
public final class EnderChestCommand {

    public static final String PERM_USE = "sess.enderchest";
    private static final String PERM_FREE = "sess.enderchest.free";

    private final SEssentials plugin;
    private volatile Cost cost;

    public EnderChestCommand(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        cost = Cost.fromConfig(plugin.getConfig(), "enderchest.cost", plugin.bank(), plugin.bankExchange(), plugin.getLogger());
    }

    public void register(Commands commands) {
        commands.register(
                Commands.literal("enderchest")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_USE))
                        .executes(ctx -> {
                            open((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                "Öffnet deine Enderchest",
                List.of("ec"));
    }

    public void open(Player player) {
        Cost cost = this.cost;
        boolean pays = !player.hasPermission(PERM_FREE) && !cost.isFree();
        if (pays && !cost.take(player)) {
            player.sendMessage(msg("not-enough", player, cost.tags()));
            return;
        }
        player.openInventory(player.getEnderChest());
        if (pays) player.sendMessage(msg("opened-paid", player, cost.tags()));
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("enderchest.messages." + key), player, extra);
    }
}
