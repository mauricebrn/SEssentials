package de.sessentials.clan;

import de.sessentials.SEssentials;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffectType;

/**
 * Verhindert Schaden unter Mitgliedern desselben Clans, solange der Clan Friendly Fire aus hat.
 * Erfasst Nahkampf, Projektile (auch Schaden-Tränke und Raketen), selbst gezündetes TNT und negative Wurftränke.
 */
final class ClanFriendlyFire implements Listener {

    private final SEssentials plugin;
    private final ClanManager clans;

    ClanFriendlyFire(SEssentials plugin, ClanManager clans) {
        this.plugin = plugin;
        this.clans = clans;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event.getDamager());
        if (attacker == null || !protectedFrom(attacker, victim)) return;
        event.setCancelled(true);
        attacker.sendActionBar(plugin.text().render(plugin.getConfig().getString("clans.messages.friendly-fire-blocked"), attacker));
    }

    /** Negative Wurftränke (Gift, Schwäche, …) wirken nicht auf Clan-Mitglieder. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player thrower)) return;
        boolean harmful = event.getPotion().getEffects().stream().anyMatch(e -> e.getType().getEffectCategory() == PotionEffectType.Category.HARMFUL);
        if (!harmful) return;
        for (LivingEntity target : event.getAffectedEntities()) {
            if (target instanceof Player victim && protectedFrom(thrower, victim)) event.setIntensity(target, 0);
        }
    }

    private boolean protectedFrom(Player attacker, Player victim) {
        if (attacker.equals(victim)) return false;
        Clan clan = clans.of(attacker.getUniqueId());
        return clan != null && !clan.friendlyFire && clan.members.contains(victim.getUniqueId());
    }

    private static Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }
}
