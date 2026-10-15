package de.sessentials.fakeplayer;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Ein Fake-Spieler: echter Spieler auf dem Server (zählt online, lädt Chunks, lässt Pflanzen wachsen,
 * ist Ziel von Befehlen), nur ohne Client. Die KI steuert ihn über dieselben Eingaben wie eine Tastatur:
 * vorwärts, sprinten, springen, schauen, schlagen. Keine Wegfindung – Ein-Block-Hindernisse werden
 * übersprungen, Abgründe über 3 Blöcke, Wasser und Lava gemieden.
 */
final class FakePlayer {

    enum Goal {
        WANDER("wander", "Herumlaufen"),
        MOBS("mobs", "Mobs bekämpfen"),
        PLAYERS("players", "Spieler bekämpfen"),
        ALL("all", "Mobs & Spieler bekämpfen");

        final String key;
        final String label;

        Goal(String key, String label) {
            this.key = key;
            this.label = label;
        }

        static Goal byKey(String key) {
            for (Goal g : values()) if (g.key.equalsIgnoreCase(key)) return g;
            return null;
        }

        boolean mobs() {
            return this == MOBS || this == ALL;
        }

        boolean players() {
            return this == PLAYERS || this == ALL;
        }
    }

    /** Einstellungen aus der Config, für alle Fake-Spieler gleich. */
    record Tuning(double wanderRadius, double aggroRange, int attackCooldown) {}

    private static final double REACH = 2.8;
    private static final double LOSE_TARGET_RANGE = 24;
    private static final int MAX_DROP = 3;

    final String name;
    /** UUID des Spielers – stabil pro Name, damit Inventar und Position (Spielerdaten) erhalten bleiben. */
    final UUID id;
    String skin;
    /** Signierte Skin-Daten; {@code null}, solange (oder falls) der Skin nicht gefunden wurde. */
    String textureValue, textureSignature;
    Location home;
    /** Soll online sein (beim Serverstart wieder beitreten). {@code false} nach /fp leave oder einem Kick. */
    boolean online;
    /** Beim ersten Beitritt an den Heimatpunkt setzen statt an den Welt-Spawn. */
    boolean placeAtHome;
    boolean ai;
    Goal goal;

    /** Der laufende Spieler oder {@code null}, wenn offline. */
    FakeServerPlayer handle;

    // KI-Zustand (nur Main-Thread)
    private LivingEntity target;
    private Location waypoint;
    private int pauseTicks, cooldown, stuckTicks, retargetTicks;
    private double lastDistance = Double.MAX_VALUE;

    FakePlayer(String name) {
        this.name = name;
        this.id = idFor(name);
    }

    static UUID idFor(String name) {
        return UUID.nameUUIDFromBytes(("SEssentials-FakePlayer:" + name.toLowerCase()).getBytes());
    }

    Player bukkit() {
        return handle != null && !handle.isRemoved() ? handle.getBukkitEntity() : null;
    }

    /** Von außen gesetztes Ziel (Gegenangriff), sofern das Ziel es erlaubt. */
    void provoke(LivingEntity attacker) {
        if (ai && allowed(attacker)) target = attacker;
    }

    void clearState() {
        target = null;
        waypoint = null;
        pauseTicks = 0;
        if (handle != null) stop();
    }

    // ------------------------------------------------------------
    // KI
    // ------------------------------------------------------------

    void tick(Player body, Tuning t, Predicate<Player> attackable) {
        // satt halten: so regeneriert er wie ein Spieler und verhungert nicht
        if (body.getFoodLevel() < 20) body.setFoodLevel(20);
        if (cooldown > 0) cooldown--;
        if (body.isDead()) return;

        // im Wasser nach oben schwimmen statt zu ertrinken
        boolean swim = body.isInWater();
        if (!ai || body.getGameMode() == GameMode.SPECTATOR) {
            stop();
            handle.setJumping(swim);
            lookAtNearestPlayer(body);
            return;
        }
        if (goal != Goal.WANDER && --retargetTicks <= 0) {
            retargetTicks = 10;
            if (!validTarget(body, target, LOSE_TARGET_RANGE, attackable)) target = findTarget(body, t.aggroRange(), attackable);
        }
        if (target != null && validTarget(body, target, LOSE_TARGET_RANGE, attackable)) {
            fight(body, t);
        } else {
            target = null;
            wander(body, t);
        }
        if (swim) handle.setJumping(true);
    }

    private void fight(Player body, Tuning t) {
        look(body, target.getEyeLocation());
        double distance = body.getLocation().distance(target.getLocation());
        if (distance > REACH) {
            Vector to = target.getLocation().toVector().subtract(body.getLocation().toVector());
            if (!walk(body, to, true)) stop();
        } else {
            stop();
            if (cooldown <= 0 && body.hasLineOfSight(target)) {
                body.swingMainHand();
                body.attack(target);
                cooldown = t.attackCooldown();
            }
        }
    }

    private void wander(Player body, Tuning t) {
        if (pauseTicks > 0) {
            pauseTicks--;
            stop();
            lookAtNearestPlayer(body);
            return;
        }
        Location here = body.getLocation();
        if (waypoint == null || !waypoint.getWorld().equals(here.getWorld())) {
            waypoint = pickWaypoint(here, t.wanderRadius());
            lastDistance = Double.MAX_VALUE;
            stuckTicks = 0;
            if (waypoint == null) {
                pauseTicks = 40;
                return;
            }
        }
        Vector to = waypoint.toVector().subtract(here.toVector()).setY(0);
        double distance = to.length();
        // angekommen, festgefahren oder Weg versperrt: kurz stehen bleiben, dann neues Ziel
        if (distance < 0.8) {
            arrive();
            return;
        }
        if (distance < lastDistance - 0.02) {
            lastDistance = distance;
            stuckTicks = 0;
        } else if (++stuckTicks > 40) {
            arrive();
            return;
        }
        if (!walk(body, to, false)) arrive();
    }

    private void arrive() {
        waypoint = null;
        stop();
        pauseTicks = 40 + ThreadLocalRandom.current().nextInt(80);
    }

    /** Zufälliger begehbarer Punkt um den Heimatpunkt (Boden höchstens 3 Blöcke über/unter der aktuellen Höhe). */
    private Location pickWaypoint(Location here, double radius) {
        Location center = home != null && home.getWorld().equals(here.getWorld()) ? home : here;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = r.nextDouble(Math.PI * 2), dist = r.nextDouble(2, Math.max(3, radius));
            int x = (int) Math.floor(center.getX() + Math.cos(angle) * dist);
            int z = (int) Math.floor(center.getZ() + Math.sin(angle) * dist);
            if (!here.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int dy = 3; dy >= -3; dy--) {
                Block feet = here.getWorld().getBlockAt(x, here.getBlockY() + dy, z);
                if (standable(feet)) return feet.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    /** Nächster Platz mit festem Boden unter (notfalls über) {@code at} – damit niemand in der Luft erscheint. */
    static Location ground(Location at) {
        var world = at.getWorld();
        int x = at.getBlockX(), z = at.getBlockZ();
        for (int y = at.getBlockY(); y > world.getMinHeight(); y--) {
            if (standable(world.getBlockAt(x, y, z))) return withY(at, y);
        }
        for (int y = at.getBlockY() + 1; y < world.getMaxHeight() - 1; y++) {
            if (standable(world.getBlockAt(x, y, z))) return withY(at, y);
        }
        return at;
    }

    private static Location withY(Location at, int y) {
        Location l = at.clone();
        l.setY(y);
        return l;
    }

    static boolean standable(Block feet) {
        Block ground = feet.getRelative(BlockFace.DOWN);
        return ground.isSolid() && !feet.isSolid() && !feet.getRelative(BlockFace.UP).isSolid()
                && !feet.isLiquid() && ground.getType() != Material.MAGMA_BLOCK;
    }

    /**
     * Läuft einen Tick lang Richtung {@code direction} (Eingabe „vorwärts“, ggf. sprinten und springen).
     * {@code false}, wenn der Weg versperrt ist (Wand, Abgrund, Wasser, Lava).
     */
    private boolean walk(Player body, Vector direction, boolean sprint) {
        Vector dir = direction.clone().setY(0);
        if (dir.lengthSquared() < 1e-6) return true;
        dir.normalize();
        Location loc = body.getLocation();
        if (target == null) {
            handle.setYRot((float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ())));
            handle.setXRot(0);
        }
        handle.setYHeadRot(handle.getYRot());

        Block ahead = loc.clone().add(dir.clone().multiply(0.7)).getBlock();
        boolean jump = false;
        if (!body.isInWater() && (ahead.isLiquid() || ahead.getRelative(BlockFace.DOWN).isLiquid())) return false;
        if (ahead.isSolid()) {
            boolean room = !ahead.getRelative(BlockFace.UP).isSolid() && !ahead.getRelative(0, 2, 0).isSolid()
                    && !loc.getBlock().getRelative(0, 2, 0).isSolid();
            if (!room) return false;
            jump = true;
        } else {
            Block below = ahead.getRelative(BlockFace.DOWN);
            int drop = 0;
            while (!below.isSolid() && drop <= MAX_DROP) {
                if (below.isLiquid()) return false;
                below = below.getRelative(BlockFace.DOWN);
                drop++;
            }
            if (drop > MAX_DROP) return false;
        }
        handle.zza = 1f;
        handle.xxa = 0f;
        handle.setSprinting(sprint);
        handle.setJumping(jump);
        return true;
    }

    private void stop() {
        handle.zza = 0f;
        handle.xxa = 0f;
        handle.setSprinting(false);
        handle.setJumping(false);
    }

    private void look(Player body, Location at) {
        Vector d = at.toVector().subtract(body.getEyeLocation().toVector());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        float pitch = (float) Math.toDegrees(-Math.atan2(d.getY(), Math.sqrt(d.getX() * d.getX() + d.getZ() * d.getZ())));
        handle.setYRot(yaw);
        handle.setXRot(pitch);
        handle.setYHeadRot(yaw);
    }

    private void lookAtNearestPlayer(Player body) {
        if (body.getTicksLived() % 5 != 0) return;
        Player nearest = null;
        double best = 64;
        for (Player p : body.getWorld().getPlayers()) {
            if (p == body || p.getGameMode() == GameMode.SPECTATOR) continue;
            double d = p.getLocation().distanceSquared(body.getLocation());
            if (d < best) {
                best = d;
                nearest = p;
            }
        }
        if (nearest != null) look(body, nearest.getEyeLocation());
    }

    // ------------------------------------------------------------
    // Ziele
    // ------------------------------------------------------------

    private LivingEntity findTarget(Player body, double range, Predicate<Player> attackable) {
        LivingEntity best = null;
        double bestDistance = range * range;
        for (Entity e : body.getNearbyEntities(range, range / 2, range)) {
            if (!(e instanceof LivingEntity living) || !validTarget(body, living, range, attackable)) continue;
            double d = e.getLocation().distanceSquared(body.getLocation());
            if (d < bestDistance && body.hasLineOfSight(living)) {
                bestDistance = d;
                best = living;
            }
        }
        return best;
    }

    private boolean validTarget(Player body, LivingEntity e, double range, Predicate<Player> attackable) {
        if (e == null || !e.isValid() || e.isDead() || e == body || !e.getWorld().equals(body.getWorld())) return false;
        if (e.getLocation().distanceSquared(body.getLocation()) > range * range) return false;
        if (e instanceof Player p) return goal.players() && attackable.test(p);
        return allowed(e);
    }

    /** Spieler werden zusätzlich über {@code attackable} gefiltert (Spielmodus, Vanish, Recht, andere Fake-Spieler). */
    private boolean allowed(LivingEntity e) {
        if (e instanceof Player) return goal.players();
        return goal.mobs() && e instanceof Enemy;
    }
}
