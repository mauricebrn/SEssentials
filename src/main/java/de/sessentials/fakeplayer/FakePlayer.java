package de.sessentials.fakeplayer;

import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Ein Fake-Spieler: Mannequin-Körper in der Welt plus TAB-Eintrag. Die KI steuert den Körper selbst
 * (Mannequins haben keine eigene): Herumlaufen um den Heimatpunkt, Ziele suchen, hinlaufen, zuschlagen.
 * Keine Wegfindung – Hindernisse von einem Block werden übersprungen, Abgründe und Lava gemieden.
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
    record Tuning(double wanderRadius, double aggroRange, int attackCooldown, double walkSpeed, double sprintSpeed) {}

    private static final double REACH = 2.6;
    private static final double LOSE_TARGET_RANGE = 24;
    private static final int MAX_DROP = 3;

    final String name;
    /** UUID des TAB-Eintrags (stabil pro Name, nie die eines echten Spielers). */
    final UUID profileId;
    String skin;
    /** Signierte Skin-Daten für den TAB-Kopf; {@code null}, solange (oder falls) der Skin nicht gefunden wurde. */
    String textureValue, textureSignature;
    UUID entityId;
    Location home;
    /** Zuletzt bekannte Position – um zu erkennen, ob der Körper in einem geladenen Chunk fehlt. */
    Location lastSeen;
    boolean ai;
    Goal goal;
    final int ping = 15 + ThreadLocalRandom.current().nextInt(50);

    // KI-Zustand (nur Main-Thread)
    private LivingEntity target;
    private Location waypoint;
    private int pauseTicks, cooldown, stuckTicks, retargetTicks, regenTicks;
    private double lastDistance = Double.MAX_VALUE;
    boolean respawning;

    FakePlayer(String name, UUID profileId) {
        this.name = name;
        this.profileId = profileId;
    }

    static UUID profileIdFor(String name) {
        return UUID.nameUUIDFromBytes(("SEssentials-FakePlayer:" + name.toLowerCase()).getBytes());
    }

    Mannequin body() {
        return entityId != null && Bukkit.getEntity(entityId) instanceof Mannequin m && m.isValid() ? m : null;
    }

    /** Von außen gesetztes Ziel (Gegenangriff), sofern das aktuelle Ziel es erlaubt. */
    void provoke(LivingEntity attacker) {
        if (ai && allowed(attacker)) target = attacker;
    }

    void clearState() {
        target = null;
        waypoint = null;
        pauseTicks = 0;
    }

    // ------------------------------------------------------------
    // KI
    // ------------------------------------------------------------

    void tick(Mannequin body, Tuning t, Predicate<Player> attackable) {
        lastSeen = body.getLocation();
        // ins Wasser geraten (Rückstoß, geschoben): nach oben paddeln, statt zu ertrinken
        if (body.isInWater()) {
            Vector v = body.getVelocity();
            body.setVelocity(v.setY(Math.max(v.getY(), 0.08)));
        }
        if (++regenTicks >= 40) {
            regenTicks = 0;
            AttributeInstance max = body.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && body.getHealth() < max.getValue()) body.setHealth(Math.min(max.getValue(), body.getHealth() + 1));
        }
        if (cooldown > 0) cooldown--;

        if (!ai) {
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
    }

    private void fight(Mannequin body, Tuning t) {
        Location eye = target.getEyeLocation();
        body.lookAt(eye.getX(), eye.getY(), eye.getZ(), LookAnchor.EYES);
        double distance = body.getLocation().distance(target.getLocation());
        if (distance > REACH) {
            Vector to = target.getLocation().toVector().subtract(body.getLocation().toVector());
            move(body, to, t.sprintSpeed());
        } else if (cooldown <= 0 && body.hasLineOfSight(target)) {
            body.swingMainHand();
            body.attack(target);
            cooldown = t.attackCooldown();
        }
    }

    private void wander(Mannequin body, Tuning t) {
        if (pauseTicks > 0) {
            pauseTicks--;
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
        if (!move(body, to, t.walkSpeed())) arrive();
    }

    private void arrive() {
        waypoint = null;
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
     * Ein Schritt Richtung {@code direction}. Springt über Ein-Block-Hindernisse, meidet Wasser und Lava
     * (Mannequins können nicht schwimmen) und Abgründe über 3 Blöcke. {@code false}, wenn der Weg versperrt ist.
     */
    private boolean move(Mannequin body, Vector direction, double speed) {
        Vector dir = direction.clone().setY(0);
        if (dir.lengthSquared() < 1e-6) return true;
        dir.normalize();
        Location loc = body.getLocation();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        if (target == null) body.setRotation(yaw, 0);
        body.setBodyYaw(yaw);

        Block ahead = loc.clone().add(dir.clone().multiply(0.7)).getBlock();
        double vy = body.getVelocity().getY();
        if (!body.isInWater() && (ahead.isLiquid() || ahead.getRelative(BlockFace.DOWN).isLiquid())) return false;
        if (ahead.isSolid()) {
            boolean room = !ahead.getRelative(BlockFace.UP).isSolid() && !ahead.getRelative(0, 2, 0).isSolid()
                    && !loc.getBlock().getRelative(0, 2, 0).isSolid();
            if (!room) return false;
            if (body.isOnGround()) vy = 0.42;
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
        body.setVelocity(new Vector(dir.getX() * speed, vy, dir.getZ() * speed));
        return true;
    }

    private void lookAtNearestPlayer(Mannequin body) {
        if (body.getTicksLived() % 5 != 0) return;
        Player nearest = null;
        double best = 64;
        for (Player p : body.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(body.getLocation());
            if (d < best && p.getGameMode() != GameMode.SPECTATOR) {
                best = d;
                nearest = p;
            }
        }
        if (nearest != null) {
            Location eye = nearest.getEyeLocation();
            body.lookAt(eye.getX(), eye.getY(), eye.getZ(), LookAnchor.EYES);
        }
    }

    // ------------------------------------------------------------
    // Ziele
    // ------------------------------------------------------------

    private LivingEntity findTarget(Mannequin body, double range, Predicate<Player> attackable) {
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

    private boolean validTarget(Mannequin body, LivingEntity e, double range, Predicate<Player> attackable) {
        if (e == null || !e.isValid() || e.isDead() || e == body || !e.getWorld().equals(body.getWorld())) return false;
        if (e.getLocation().distanceSquared(body.getLocation()) > range * range) return false;
        if (e instanceof Player p) return goal.players() && attackable.test(p);
        return allowed(e);
    }

    /** Spieler werden zusätzlich über {@code attackable} gefiltert (Spielmodus, Vanish, Recht). */
    private boolean allowed(LivingEntity e) {
        if (e instanceof Player) return goal.players();
        return goal.mobs() && e instanceof Enemy;
    }
}
