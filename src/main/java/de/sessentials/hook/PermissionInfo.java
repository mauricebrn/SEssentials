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

    /** {@code true}, wenn {@link #setOwnPrefix} Prefixe im Rechte-Plugin speichern kann. */
    default boolean supportsOwnPrefix() {
        return false;
    }

    /**
     * Setzt einen eigenen Prefix direkt am Spieler (auch offline): entfernt seine Prefixe mit {@code priority} und
     * {@code oldPriority} und setzt {@code value} mit {@code priority} ({@code null} = nur entfernen). Läuft asynchron.
     */
    default void setOwnPrefix(UUID player, int priority, int oldPriority, String value) {}

    PermissionInfo NONE = new PermissionInfo() {
        @Override public String prefix(Player player) { return ""; }
        @Override public String suffix(Player player) { return ""; }
        @Override public String primaryGroup(Player player) { return "default"; }
        @Override public String meta(Player player, String key) { return ""; }
    };
}
