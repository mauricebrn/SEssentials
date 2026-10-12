package de.sessentials.chat;

import de.sessentials.SEssentials;
import de.sessentials.text.Legacy;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class ChatListener implements Listener {

    /** Spieler-MiniMessage nur mit "harmlosen" Tags (keine Click-Events, keine Selektoren). */
    private static final MiniMessage PLAYER_MM = MiniMessage.builder()
            .tags(TagResolver.resolver(
                    StandardTags.color(),
                    StandardTags.decorations(),
                    StandardTags.gradient(),
                    StandardTags.rainbow(),
                    StandardTags.reset(),
                    StandardTags.shadowColor()))
            .build();

    private final SEssentials plugin;
    private volatile boolean enabled;
    private volatile String defaultFormat;
    private volatile Map<String, String> groupFormats = Map.of();

    public ChatListener(SEssentials plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("chat.enabled", true);
        defaultFormat = cfg.getString("chat.default-format", "<player> » <message>");
        Map<String, String> formats = new HashMap<>();
        ConfigurationSection sec = cfg.getConfigurationSection("chat.group-formats");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                formats.put(key.toLowerCase(Locale.ROOT), sec.getString(key));
            }
        }
        groupFormats = Map.copyOf(formats);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!enabled) return;
        Player player = event.getPlayer();

        String group = plugin.text().perms().primaryGroup(player).toLowerCase(Locale.ROOT);
        String format = groupFormats.getOrDefault(group, defaultFormat);

        Component message = event.message();
        if (player.hasPermission("sess.chat.minimessage")) {
            String raw = PlainTextComponentSerializer.plainText().serialize(message);
            message = PLAYER_MM.deserialize(Legacy.toMiniMessage(raw));
        } else if (player.hasPermission("sess.chat.color")) {
            String raw = PlainTextComponentSerializer.plainText().serialize(message);
            // nur &-Codes erlauben: vorhandene MiniMessage-Tags escapen
            String escaped = MiniMessage.miniMessage().escapeTags(raw);
            message = PLAYER_MM.deserialize(Legacy.toMiniMessage(escaped));
        }

        Component rendered = plugin.text().render(format, player, Placeholder.component("message", message));
        event.renderer((source, sourceDisplayName, msg, viewer) -> rendered);
    }
}
