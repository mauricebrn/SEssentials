package de.sessentials.storage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Schreibt Dateien nacheinander in einem Hintergrund-Thread, jeweils über eine Temp-Datei,
 * damit nie eine halbe Datei entsteht. Nach {@link #shutdown()} wird direkt geschrieben.
 */
public final class AsyncFileWriter {

    private final Logger log;
    private final ExecutorService executor;

    public AsyncFileWriter(String name, Logger log) {
        this.log = log;
        this.executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "SEssentials-" + name + "-Save"));
    }

    /** {@code data} vorher im Main-Thread bzw. unter Sperre erzeugen – hier wird nur noch geschrieben. */
    public void write(File file, String data) {
        Runnable job = () -> {
            try {
                Files.createDirectories(file.getParentFile().toPath());
                File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
                Files.writeString(tmp.toPath(), data);
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.log(Level.SEVERE, file.getName() + " konnte nicht gespeichert werden", e);
            }
        };
        if (executor.isShutdown()) job.run();
        else executor.execute(job);
    }

    /** Wartet auf ausstehende Schreibvorgänge (beim Deaktivieren des Plugins). */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) log.warning("Speichern hat zu lange gedauert.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
