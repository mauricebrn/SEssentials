package de.sessentials.gamble;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.function.IntConsumer;

/**
 * Dreh-Animation für Glücksrad und Item-Upgrader: ruft {@code step} mit 0, 1, … {@code steps} auf und wird
 * dabei langsamer (gut 3,5 Sekunden bei 30 Schritten). Danach läuft {@code done}.
 */
public final class SpinAnimation {

    private SpinAnimation() {}

    public static void run(Plugin plugin, int steps, IntConsumer step, Runnable done) {
        next(plugin, 0, steps, step, done);
    }

    private static void next(Plugin plugin, int i, int steps, IntConsumer step, Runnable done) {
        step.accept(i);
        if (i >= steps) {
            done.run();
            return;
        }
        double progress = (double) i / steps;
        long delay = 1 + Math.round(6 * progress * progress * progress);
        Bukkit.getScheduler().runTaskLater(plugin, () -> next(plugin, i + 1, steps, step, done), delay);
    }
}
