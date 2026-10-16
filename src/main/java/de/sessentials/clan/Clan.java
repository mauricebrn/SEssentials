package de.sessentials.clan;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Ein Clan. Wird nur im Main-Thread geändert; {@link #color} ist volatile, weil der Clan-Tag
 * auch im (asynchronen) Chat gerendert wird.
 */
public final class Clan {

    final String name;
    volatile NamedTextColor color;
    UUID leader;
    /** Alle Mitglieder inklusive Leiter. */
    final Set<UUID> members = new LinkedHashSet<>();
    Location home;
    /** Clan-Konto in Bank-Währung. */
    long balance;
    boolean chestBought;
    /** Position des aufgestellten Truhenblocks, {@code null} = nicht aufgestellt. */
    Location chestBlock;
    /** Gemeinsames Truhen-Inventar – alle Betrachter sehen dasselbe Inventar. */
    Inventory chest;

    Clan(String name, NamedTextColor color, UUID leader) {
        this.name = name;
        this.color = color;
        this.leader = leader;
        members.add(leader);
    }

    public String name() {
        return name;
    }

    public NamedTextColor color() {
        return color;
    }

    boolean isLeader(UUID id) {
        return leader.equals(id);
    }

    /** Holder des Truhen-Inventars, damit Listener es erkennen. */
    static final class ChestHolder implements InventoryHolder {
        final Clan clan;

        ChestHolder(Clan clan) {
            this.clan = clan;
        }

        @Override
        public Inventory getInventory() {
            return clan.chest;
        }
    }
}
