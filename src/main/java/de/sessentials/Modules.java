package de.sessentials;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Welche Module laufen ({@code modules.<name>} in der config.yml, Standard: an). Ein ausgeschaltetes Modul
 * meldet keine Befehle und Listener an, startet keine Aufgaben und hat keine Kachel im Admin-Panel.
 * Der Stand wird beim Start gelesen – Befehle lassen sich zur Laufzeit nicht abmelden.
 */
public final class Modules {

    /** Alle Module in Anzeigereihenfolge. */
    public static final List<String> ALL = List.of(
            "admin-panel", "chat", "join-quit", "tab", "vanish", "help", "command-aliases",
            "player-commands", "durability", "sudo", "enderchest", "spawn", "dimensions", "tpa",
            "messages", "mail", "spy",
            "bank", "chestshops", "bounty", "clans", "gambling", "upgrader", "item-tracker",
            "punishments", "fairplay", "reports", "itemguard", "creativelog", "trust", "client-detector",
            "news", "softrestart", "auto-update", "fake-players", "webpanel");

    /** Modul → Module, ohne die es nicht läuft. */
    private static final Map<String, List<String>> REQUIRES = Map.of(
            "gambling", List.of("bank"),
            "chestshops", List.of("bank"),
            "bounty", List.of("bank"),
            "clans", List.of("bank"));

    private final Map<String, Boolean> state = new LinkedHashMap<>();

    public Modules(FileConfiguration cfg, Logger log) {
        for (String module : ALL) state.put(module, cfg.getBoolean("modules." + module, true));
        REQUIRES.forEach((module, needed) -> {
            for (String n : needed) {
                if (state.get(module) && !state.get(n)) {
                    log.warning("Modul " + module + " braucht " + n + " – wird ebenfalls ausgeschaltet.");
                    state.put(module, false);
                }
            }
        });
        List<String> off = new ArrayList<>();
        state.forEach((module, on) -> {
            if (!on) off.add(module);
        });
        if (!off.isEmpty()) log.info("Ausgeschaltete Module: " + String.join(", ", off));
    }

    public boolean on(String module) {
        Boolean on = state.get(module);
        if (on == null) throw new IllegalArgumentException("Unbekanntes Modul: " + module);
        return on;
    }
}
