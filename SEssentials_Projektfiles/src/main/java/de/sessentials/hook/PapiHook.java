package de.sessentials.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/** Wird nur aufgerufen, wenn PlaceholderAPI installiert ist. */
public final class PapiHook {

    private PapiHook() {}

    public static String apply(Player player, String text) {
        return PlaceholderAPI.setPlaceholders(player, text);
    }
}
