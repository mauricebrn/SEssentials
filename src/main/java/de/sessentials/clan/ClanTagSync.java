package de.sessentials.clan;

import de.sessentials.SEssentials;
import de.sessentials.hook.PermissionInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Collection;
import java.util.UUID;

/**
 * Schreibt den Clan-Tag als Suffix (oder Prefix) direkt an die Mitglieder in LuckPerms ({@code clans.luckperms-tag}),
 * damit ihn auch andere Plugins sehen. Die Priorität ist reserviert: SEssentials entfernt dort alle Einträge dieser Art.
 * Wechseln Art oder Priorität, räumt der nächste Abgleich die alten Einträge mit ab. Ohne LuckPerms passiert nichts.
 */
final class ClanTagSync {

    private final SEssentials plugin;
    private final ClanManager clans;

    ClanTagSync(SEssentials plugin, ClanManager clans) {
        this.plugin = plugin;
        this.clans = clans;
    }

    /** Ist der Tag in LuckPerms aktiv (dann bleibt {@code <clan>} leer, damit er nicht doppelt erscheint)? */
    boolean active() {
        return perms().supportsOwnTag() && plugin.getConfig().getBoolean("clans.luckperms-tag.enabled");
    }

    /** Tag des Spielers an seinen aktuellen Clan anpassen (oder entfernen). */
    void sync(UUID player) {
        if (!perms().supportsOwnTag()) return;
        boolean suffix = suffix();
        int priority = priority();
        String oldType = clans.appliedTagType();
        int oldPriority = clans.appliedTagPriority();
        Clan clan = clans.of(player);
        perms().setOwnTag(player, suffix, priority, active() && clan != null ? tag(clan) : null,
                oldType == null ? suffix : oldType.equals("suffix"), oldPriority < 0 ? priority : oldPriority);
    }

    void sync(Collection<UUID> players) {
        players.forEach(this::sync);
    }

    /** Beim Start und nach {@code /sess reload}: alle Clan-Mitglieder neu setzen, alte Art/Priorität abräumen. */
    void reload() {
        if (!perms().supportsOwnTag()) return;
        clans.all().forEach(clan -> sync(clan.members));
        clans.setAppliedTag(suffix() ? "suffix" : "prefix", priority());
    }

    private String tag(Clan clan) {
        String code = LegacyComponentSerializer.legacyAmpersand().serialize(Component.text("x", clan.color));
        return plugin.getConfig().getString("clans.luckperms-tag.format", "")
                .replace("<color>", code.substring(0, code.length() - 1))
                .replace("<name>", clan.name);
    }

    private boolean suffix() {
        return !"prefix".equalsIgnoreCase(plugin.getConfig().getString("clans.luckperms-tag.type", "suffix"));
    }

    private int priority() {
        return plugin.getConfig().getInt("clans.luckperms-tag.priority");
    }

    private PermissionInfo perms() {
        return plugin.permissions();
    }
}
