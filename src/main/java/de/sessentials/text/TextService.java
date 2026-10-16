package de.sessentials.text;

import de.sessentials.hook.PapiHook;
import de.sessentials.hook.PermissionInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntSupplier;

/**
 * Wandelt konfigurierte Texte in Components um – mit MiniMessage, &-Farbcodes,
 * LuckPerms-Tags und PlaceholderAPI.
 */
public final class TextService {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PermissionInfo perms;
    private final boolean papi;
    private final IntSupplier onlineCount;
    private volatile String prefix = "";
    /** Inhalt von {@code <clan>}; leer, solange das Clan-Modul aus ist. */
    private volatile Function<Player, Component> clanTag = p -> Component.empty();

    /** @param onlineCount Wert für {@code <online>} (z.B. ohne versteckte Spieler) */
    public TextService(PermissionInfo perms, boolean papi, IntSupplier onlineCount) {
        this.perms = perms;
        this.papi = papi;
        this.onlineCount = onlineCount;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix == null ? "" : prefix;
    }

    /** Liefert {@code <clan>} für Texte mit Spielerkontext (muss auch asynchron funktionieren, z.B. im Chat). */
    public void setClanTag(Function<Player, Component> clanTag) {
        this.clanTag = clanTag;
    }

    public PermissionInfo perms() {
        return perms;
    }

    /** Text ohne Spielerkontext (z.B. Broadcasts). */
    public Component render(String format, TagResolver... extra) {
        return render(format, null, extra);
    }

    /** Text mit Spielerkontext. {@code player} darf null sein. */
    public Component render(String format, Player player, TagResolver... extra) {
        if (format == null || format.isEmpty()) return Component.empty();
        String input = format;
        if (papi && player != null && input.indexOf('%') >= 0) {
            input = PapiHook.apply(player, input);
        }
        input = Legacy.toMiniMessage(input);

        List<TagResolver> resolvers = new ArrayList<>();
        resolvers.add(Placeholder.component("prefix", parse(prefix)));
        resolvers.add(placeholder("online", String.valueOf(onlineCount.getAsInt())));
        resolvers.add(placeholder("max_players", String.valueOf(Bukkit.getMaxPlayers())));
        if (player != null) {
            resolvers.add(placeholder("player", player.getName()));
            resolvers.add(Placeholder.component("displayname", player.displayName()));
            resolvers.add(placeholder("world", player.getWorld().getName()));
            resolvers.add(Placeholder.component("lp_prefix", parse(perms.prefix(player))));
            resolvers.add(Placeholder.component("lp_suffix", parse(perms.suffix(player))));
            resolvers.add(placeholder("lp_group", perms.primaryGroup(player)));
            resolvers.add(Placeholder.component("clan", clanTag.apply(player)));
            resolvers.add(TagResolver.resolver("lp_meta", (args, ctx) -> {
                String key = args.popOr("lp_meta benötigt einen Schlüssel, z.B. <lp_meta:color>").value();
                return Tag.selfClosingInserting(parse(perms.meta(player, key)));
            }));
            if (papi) {
                resolvers.add(TagResolver.resolver("papi", (args, ctx) -> {
                    String name = args.popOr("papi benötigt einen Namen, z.B. <papi:player_ping>").value();
                    return Tag.selfClosingInserting(parse(PapiHook.apply(player, "%" + name + "%")));
                }));
            }
        } else {
            resolvers.add(placeholder("player", "Konsole"));
        }
        for (TagResolver r : extra) resolvers.add(r);

        return MM.deserialize(input, TagResolver.resolver(resolvers));
    }

    /**
     * Platzhalter für reinen Text. Werte ohne MiniMessage-Sonderzeichen werden vor dem Parsen eingesetzt
     * und funktionieren so auch in Tag-Argumenten wie {@code <click:run_command:'/news read <id>'>};
     * alle anderen (z.B. Spielereingaben mit {@code <}) bleiben reiner Text und können nichts einschleusen.
     */
    public static TagResolver placeholder(String key, String value) {
        boolean plain = value.chars().noneMatch(c -> c == '<' || c == '\\' || c == '\'' || c == '"');
        return plain ? Placeholder.parsed(key, value) : Placeholder.unparsed(key, value);
    }

    /** Parst einen Einzelwert (z.B. LuckPerms-Prefix) – egal ob MiniMessage oder &-Codes. */
    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        return MM.deserialize(Legacy.toMiniMessage(raw));
    }
}
