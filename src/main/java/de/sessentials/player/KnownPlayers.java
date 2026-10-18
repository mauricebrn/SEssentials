package de.sessentials.player;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Locale;
import java.util.NavigableSet;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Namen aller Spieler, die schon einmal auf dem Server waren – für die Tab-Vervollständigung von Befehlen,
 * die auch offline Spieler annehmen (/ban, /pay, /mail, …). Beim Start einmal gelesen, danach bei jedem Join ergänzt.
 * Verrät nicht, wer online ist: unsichtbare (Vanish) Spieler stehen genauso drin wie alle anderen.
 */
public final class KnownPlayers implements Listener {

    private static final int MAX_SUGGESTIONS = 50;

    /** Wird auch asynchron gelesen (Tab-Vervollständigung). */
    private final NavigableSet<String> names = new ConcurrentSkipListSet<>(String.CASE_INSENSITIVE_ORDER);

    public KnownPlayers() {
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null) names.add(p.getName());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        names.add(event.getPlayer().getName());
    }

    /** Vorschläge aus allen bekannten Spielern (online und offline), ohne den Absender selbst. */
    public SuggestionProvider<CommandSourceStack> suggestions() {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            String self = ctx.getSource().getSender().getName();
            int count = 0;
            for (String name : names.tailSet(typed, true)) {
                if (!name.toLowerCase(Locale.ROOT).startsWith(typed) || count >= MAX_SUGGESTIONS) break;
                if (name.equalsIgnoreCase(self)) continue;
                builder.suggest(name);
                count++;
            }
            return builder.buildFuture();
        };
    }
}
