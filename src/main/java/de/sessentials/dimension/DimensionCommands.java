package de.sessentials.dimension;

import com.mojang.brigadier.Command;
import de.sessentials.SEssentials;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * /nether und /end: teleportiert in die Dimension bzw. – wenn man schon dort ist – zurück in die Oberwelt.
 * Zielpunkte wie bei Vanilla-Portalen; Chunks werden asynchron geladen.
 */
public final class DimensionCommands {

    /** Vanilla-Ankunftspunkt im End (Obsidian-Plattform). */
    private static final int END_X = 100, END_Y = 50, END_Z = 0;
    private static final int NETHER_SCALE = 8;

    private final SEssentials plugin;

    public DimensionCommands(SEssentials plugin) {
        this.plugin = plugin;
    }

    public void register(Commands commands) {
        register(commands, "nether", "Teleportiert in den Nether bzw. zurück in die Oberwelt", World.Environment.NETHER);
        register(commands, "end", "Teleportiert ins End bzw. zurück in die Oberwelt", World.Environment.THE_END);
    }

    private void register(Commands commands, String name, String description, World.Environment target) {
        commands.register(
                Commands.literal(name)
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission("sess." + name))
                        .executes(ctx -> {
                            toggle((Player) ctx.getSource().getExecutor(), target);
                            return Command.SINGLE_SUCCESS;
                        })
                        .build(),
                description,
                List.of());
    }

    /** In die Dimension {@code target} oder, wenn man schon dort ist, zurück in die Oberwelt. */
    public void toggle(Player player, World.Environment target) {
        World from = player.getWorld();
        boolean back = from.getEnvironment() == target;
        World to = world(back ? World.Environment.NORMAL : target);
        if (to == null) {
            player.sendMessage(msg("world-missing", player));
            return;
        }
        String messageKey = back ? "overworld" : target == World.Environment.NETHER ? "nether" : "end";
        Consumer<Location> teleport = loc -> player.teleportAsync(loc).thenAccept(ok -> {
            if (ok) player.sendMessage(msg(messageKey, player));
        });

        if (to.getEnvironment() == World.Environment.THE_END) {
            to.getChunkAtAsync(END_X >> 4, END_Z >> 4).thenRun(() -> teleport.accept(endPlatform(to)));
        } else if (to.getEnvironment() == World.Environment.NETHER) {
            Location loc = player.getLocation();
            int x = Math.floorDiv(loc.getBlockX(), NETHER_SCALE), z = Math.floorDiv(loc.getBlockZ(), NETHER_SCALE);
            to.getChunkAtAsync(x >> 4, z >> 4).thenRun(() -> teleport.accept(netherSpot(to, x, z, loc)));
        } else if (from.getEnvironment() == World.Environment.NETHER) {
            Location loc = player.getLocation();
            int x = loc.getBlockX() * NETHER_SCALE, z = loc.getBlockZ() * NETHER_SCALE;
            to.getChunkAtAsync(x >> 4, z >> 4).thenRun(() -> {
                int y = to.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                teleport.accept(new Location(to, x + 0.5, y, z + 0.5, loc.getYaw(), loc.getPitch()));
            });
        } else {
            // aus dem End zurück: wie Vanilla zum Respawn-Punkt (Bett/Anker), sonst Welt-Spawn
            Location respawn = player.getRespawnLocation();
            teleport.accept(respawn != null ? respawn : to.getSpawnLocation());
        }
    }

    private static World world(World.Environment environment) {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == environment) return world;
        }
        return null;
    }

    /** Wie Vanilla beim Betreten des Ends: 5x5-Obsidian-Plattform mit 3 Blöcken Luft darüber. */
    private static Location endPlatform(World end) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                end.getBlockAt(END_X + dx, END_Y - 1, END_Z + dz).setType(Material.OBSIDIAN);
                for (int dy = 0; dy < 3; dy++) end.getBlockAt(END_X + dx, END_Y + dy, END_Z + dz).setType(Material.AIR);
            }
        }
        return new Location(end, END_X + 0.5, END_Y, END_Z + 0.5, 90f, 0f);
    }

    /**
     * Sicherer Platz im Ziel-Chunk unterhalb der Nether-Decke, nächstgelegene Spalte zuerst.
     * Ohne freien Platz: kleine Obsidian-Plattform wie bei Vanilla-Portalen.
     */
    private static Location netherSpot(World nether, int x, int z, Location look) {
        int top = Math.min(nether.getLogicalHeight(), nether.getMaxHeight()) - 3;
        int bottom = nether.getMinHeight() + 1;
        int chunkX = x & ~15, chunkZ = z & ~15;
        Block best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int bx = chunkX; bx < chunkX + 16; bx++) {
            for (int bz = chunkZ; bz < chunkZ + 16; bz++) {
                int dist = (bx - x) * (bx - x) + (bz - z) * (bz - z);
                if (dist >= bestDist) continue;
                for (int y = top; y > bottom; y--) {
                    Block feet = nether.getBlockAt(bx, y, bz);
                    if (isSafe(feet)) {
                        best = feet;
                        bestDist = dist;
                        break;
                    }
                }
            }
        }
        if (best == null) {
            int y = Math.max(bottom + 1, Math.min(top, 70));
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    nether.getBlockAt(x + dx, y - 1, z + dz).setType(Material.OBSIDIAN);
                    nether.getBlockAt(x + dx, y, z + dz).setType(Material.AIR);
                    nether.getBlockAt(x + dx, y + 1, z + dz).setType(Material.AIR);
                }
            }
            best = nether.getBlockAt(x, y, z);
        }
        return new Location(nether, best.getX() + 0.5, best.getY(), best.getZ() + 0.5, look.getYaw(), look.getPitch());
    }

    private static boolean isSafe(Block feet) {
        Block ground = feet.getRelative(0, -1, 0);
        Block head = feet.getRelative(0, 1, 0);
        return ground.getType().isSolid() && ground.getType() != Material.MAGMA_BLOCK
                && feet.isPassable() && !feet.isLiquid() && feet.getType() != Material.FIRE
                && head.isPassable() && !head.isLiquid();
    }

    private net.kyori.adventure.text.Component msg(String key, Player player) {
        return plugin.text().render(plugin.getConfig().getString("dimensions.messages." + key), player);
    }
}
