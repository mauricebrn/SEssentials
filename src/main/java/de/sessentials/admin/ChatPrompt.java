package de.sessentials.admin;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fragt einen Text im Chat ab (z.B. Titel im News-Editor). Die nächste Chatnachricht des Spielers
 * wird abgefangen statt gesendet. "abbrechen"/"cancel" oder {@link #cancel} bricht ab.
 */
public final class ChatPrompt implements Listener {

    /** Wörter, die eine Eingabe abbrechen (je Sprache eins). */
    private static final Set<String> CANCEL_WORDS = Set.of("abbrechen", "cancel");

    private record Pending(Consumer<String> onInput, Runnable onCancel) {}

    private final Plugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Beide Callbacks laufen im Main-Thread. */
    public void ask(Player player, Consumer<String> onInput, Runnable onCancel) {
        pending.put(player.getUniqueId(), new Pending(onInput, onCancel));
    }

    /** Bricht eine offene Eingabe ab. {@code false}, wenn keine offen war. */
    public boolean cancel(Player player) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) return false;
        p.onCancel().run();
        return true;
    }

    // LOWEST: vor Vanish-Schutz und Chat-Format, damit die Eingabe nie im Chat landet
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (CANCEL_WORDS.contains(input.toLowerCase(Locale.ROOT))) p.onCancel().run();
            else p.onInput().accept(input);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }
}
