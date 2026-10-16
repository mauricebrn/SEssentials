package de.sessentials.hook;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Liefert Gruppen-/Prefix-Infos. Ohne LuckPerms wird {@link #NONE} verwendet. */
public interface PermissionInfo {

    String prefix(Player player);

    String suffix(Player player);

    String primaryGroup(Player player);

    String meta(Player player, String key);

    /**
     * Lädt die Rechte-Daten eines Spielers, der ohne normalen Login beitritt (Fake-Spieler) – sonst
     * würde das Rechte-Plugin ihn beim Login abweisen. Ohne LuckPerms: nichts zu tun.
     */
    default void loadUser(UUID id, String name) {}

    /** {@code true}, wenn {@link #setOwnTag} Prefixe/Suffixe im Rechte-Plugin speichern kann. */
    default boolean supportsOwnTag() {
        return false;
    }

    /**
     * Setzt einen eigenen Prefix ({@code suffix = false}) bzw. Suffix direkt am Spieler (auch offline). Vorher werden
     * seine Einträge dieser Art mit {@code priority} entfernt und die der zuletzt genutzten Art ({@code oldSuffix})
     * mit {@code oldPriority}. {@code value == null} = nur entfernen. Läuft asynchron.
     */
    default void setOwnTag(UUID player, boolean suffix, int priority, String value, boolean oldSuffix, int oldPriority) {}

    PermissionInfo NONE = new PermissionInfo() {
        @Override public String prefix(Player player) { return ""; }
        @Override public String suffix(Player player) { return ""; }
        @Override public String primaryGroup(Player player) { return "default"; }
        @Override public String meta(Player player, String key) { return ""; }
    };
}
