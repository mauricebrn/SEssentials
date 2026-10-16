package de.sessentials.clan;

import de.sessentials.SEssentials;
import de.sessentials.hook.PermissionInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Collection;
import java.util.UUID;

/**
 * Schreibt den Clan-Tag als Prefix direkt an die Mitglieder in LuckPerms ({@code clans.luckperms-prefix}), damit ihn
 * auch andere Plugins sehen. Die Priorität ist für diesen Prefix reserviert: SEssentials entfernt dort alle Prefixe.
 * Ohne LuckPerms passiert nichts.
 */
final class ClanPrefixSync {

    private final SEssentials plugin;
    private final ClanManager clans;

    ClanPrefixSync(SEssentials plugin, ClanManager clans) {
        this.plugin = plugin;
        this.clans = clans;
    }

    /** Ist der Prefix in LuckPerms aktiv (dann bleibt {@code <clan>} leer, damit der Tag nicht doppelt erscheint)? */
    boolean active() {
        return perms().supportsOwnPrefix() && plugin.getConfig().getBoolean("clans.luckperms-prefix.enabled");
    }

    /** Prefix des Spielers an seinen aktuellen Clan anpassen (oder entfernen). */
    void sync(UUID player) {
        if (!perms().supportsOwnPrefix()) return;
        int priority = priority();
        int old = clans.appliedPrefixPriority();
        Clan clan = clans.of(player);
        perms().setOwnPrefix(player, priority, old < 0 ? priority : old, active() && clan != null ? prefix(clan) : null);
    }

    void sync(Collection<UUID> players) {
        players.forEach(this::sync);
    }

    /** Nach {@code /sess reload}: alle Clan-Mitglieder neu setzen; eine geänderte Priorität räumt die alte mit ab. */
    void reload() {
        if (!perms().supportsOwnPrefix()) return;
        clans.all().forEach(clan -> sync(clan.members));
        clans.setAppliedPrefixPriority(priority());
    }

    private String prefix(Clan clan) {
        String code = LegacyComponentSerializer.legacyAmpersand().serialize(Component.text("x", clan.color));
        return plugin.getConfig().getString("clans.luckperms-prefix.format", "")
                .replace("<color>", code.substring(0, code.length() - 1))
                .replace("<name>", clan.name);
    }

    private int priority() {
        return plugin.getConfig().getInt("clans.luckperms-prefix.priority");
    }

    private PermissionInfo perms() {
        return plugin.permissions();
    }
}
