package de.sessentials.player;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * Führt einen Befehl so aus, als hätte der Spieler ihn getippt: erst das Preprocess-Event (Mute-Sperren,
 * Befehls-Protokoll, andere Plugins), dann der Befehl mit den Rechten des Spielers.
 * Nicht über {@code player.chat("/…")}: Befehle mit Chat-Text (/say, /me) verlangen dort eine Signatur
 * vom Client, die es hier nicht gibt.
 */
public final class TypedCommand {

    private TypedCommand() {}

    /** @param command mit oder ohne führenden "/" */
    public static void run(Player player, String command) {
        String line = command.startsWith("/") ? command : "/" + command;
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, line);
        if (!event.callEvent()) return;
        player.performCommand(event.getMessage().substring(1));
    }
}
