package de.sessentials.player;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.WeatherType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;

/**
 * Eigene Uhrzeit (/ptime) und eigenes Wetter (/pweather) pro Spieler – nur für ihn sichtbar, die Welt bleibt gleich.
 * Die Wahl wird am Spieler gespeichert und beim Join wieder gesetzt.
 */
public final class PersonalEnvironment implements Listener {

    private static final String PERM_TIME = "sess.ptime";
    private static final String PERM_WEATHER = "sess.pweather";
    /** Voreinstellungen in Ticks (0 = Sonnenaufgang). */
    private static final Map<String, Integer> TIMES = Map.of("day", 1000, "noon", 6000, "night", 13000, "midnight", 18000);

    private final SEssentials plugin;
    private final NamespacedKey timeKey;
    private final NamespacedKey weatherKey;

    public PersonalEnvironment(SEssentials plugin) {
        this.plugin = plugin;
        this.timeKey = new NamespacedKey(plugin, "ptime");
        this.weatherKey = new NamespacedKey(plugin, "pweather");
    }

    private Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("ptime.messages." + key, ""), player, extra);
    }

    public void register(Commands commands) {
        var ptime = Commands.literal("ptime")
                .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_TIME))
                .then(Commands.literal("reset").executes(ctx -> {
                    resetTime((Player) ctx.getSource().getExecutor());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 23999)).executes(ctx -> {
                    setTime((Player) ctx.getSource().getExecutor(), IntegerArgumentType.getInteger(ctx, "ticks"), null);
                    return Command.SINGLE_SUCCESS;
                }));
        TIMES.forEach((name, ticks) -> ptime.then(Commands.literal(name).executes(ctx -> {
            setTime((Player) ctx.getSource().getExecutor(), ticks, name);
            return Command.SINGLE_SUCCESS;
        })));
        commands.register(ptime.build(), "Eigene Uhrzeit, nur für dich sichtbar", List.of("pzeit"));

        commands.register(
                Commands.literal("pweather")
                        .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission(PERM_WEATHER))
                        .then(Commands.literal("clear").executes(ctx -> {
                            setWeather((Player) ctx.getSource().getExecutor(), WeatherType.CLEAR);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("rain").executes(ctx -> {
                            setWeather((Player) ctx.getSource().getExecutor(), WeatherType.DOWNFALL);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("reset").executes(ctx -> {
                            resetWeather((Player) ctx.getSource().getExecutor());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .build(),
                "Eigenes Wetter, nur für dich sichtbar",
                List.of("pwetter"));
    }

    /** Feste Uhrzeit (läuft nicht weiter), bis /ptime reset. */
    private void setTime(Player player, int ticks, String preset) {
        player.setPlayerTime(ticks, false);
        player.getPersistentDataContainer().set(timeKey, PersistentDataType.INTEGER, ticks);
        player.sendMessage(msg("time-set", player, TextService.placeholder("time", preset != null ? preset : String.valueOf(ticks))));
    }

    private void resetTime(Player player) {
        player.resetPlayerTime();
        player.getPersistentDataContainer().remove(timeKey);
        player.sendMessage(msg("time-reset", player));
    }

    private void setWeather(Player player, WeatherType weather) {
        player.setPlayerWeather(weather);
        player.getPersistentDataContainer().set(weatherKey, PersistentDataType.STRING, weather.name());
        player.sendMessage(msg(weather == WeatherType.CLEAR ? "weather-clear" : "weather-rain", player));
    }

    private void resetWeather(Player player) {
        player.resetPlayerWeather();
        player.getPersistentDataContainer().remove(weatherKey);
        player.sendMessage(msg("weather-reset", player));
    }

    /** Gespeicherte Wahl wieder setzen; ohne Recht (mehr) wird sie verworfen. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Integer ticks = pdc.get(timeKey, PersistentDataType.INTEGER);
        if (ticks != null) {
            if (player.hasPermission(PERM_TIME)) player.setPlayerTime(ticks, false);
            else pdc.remove(timeKey);
        }
        String weather = pdc.get(weatherKey, PersistentDataType.STRING);
        if (weather != null) {
            if (player.hasPermission(PERM_WEATHER)) player.setPlayerWeather(WeatherType.valueOf(weather));
            else pdc.remove(weatherKey);
        }
    }
}
