package de.sessentials;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;

/**
 * Config in drei Ebenen:
 * <ul>
 *   <li>{@code config.yml} – global: Sprache, Prefix, Module</li>
 *   <li>{@code config/<modul>.yml} – Einstellungen je Modul (Top-Level-Key)</li>
 *   <li>{@code lang/<sprache>.yml} – alle Texte; welche gilt, steht in {@code language}</li>
 * </ul>
 * Nach außen gibt es eine zusammengeführte Config ({@link #config()}) mit den alten Pfaden: Texte aus der
 * Sprachdatei liegen wieder unter {@code <modul>.messages.<key>}, damit der restliche Code unverändert bleibt.
 * Standardwerte kommen aus {@code config.yml} und {@code lang/*.yml} im JAR; fehlende Optionen werden samt
 * Kommentaren in die passende Datei ergänzt. Eine alte, ungeteilte config.yml wird beim ersten Start aufgeteilt
 * und als {@code config.yml.old} behalten.
 */
final class ConfigFiles {

    /** Top-Level-Keys der globalen config.yml. */
    private static final Set<String> GLOBAL = Set.of("language", "prefix", "modules");
    /** Top-Level-Keys, die nicht in einer eigenen Modul-Datei liegen → Dateiname. */
    private static final Map<String, String> GROUPS = Map.of("nametag", "tab");
    /** Mitgelieferte Sprachen; unbekannte Sprachen bekommen die englischen Texte als Vorlage. */
    private static final List<String> BUNDLED_LANGUAGES = List.of("de_DE", "en_US");
    private static final String DEFAULT_LANGUAGE = "de_DE";
    private static final String TEMPLATE_LANGUAGE = "en_US";
    /** Texte außerhalb von messages-Abschnitten, die ebenfalls in die Sprachdatei gehören (Pfad bleibt gleich). */
    private static final List<String> TEXTS = List.of(
            "softrestart.bossbar.text", "softrestart.time-format",
            "help.title", "help.empty", "help.suggest", "help.link", "help.text-entry",
            "msg", "clans.chest.item-name", "clans.chest.title",
            "news.priorities", "news.feed", "news.read", "admin.title");

    private final JavaPlugin plugin;
    private final File root;
    private final File configDir;
    private final File langDir;
    /** Globale config.yml und Modul-Dateien → Inhalt samt Kommentaren. */
    private final Map<File, YamlConfiguration> configs = new LinkedHashMap<>();
    private volatile YamlConfiguration merged = new YamlConfiguration();
    private volatile String language = DEFAULT_LANGUAGE;

    ConfigFiles(JavaPlugin plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "config.yml");
        this.configDir = new File(plugin.getDataFolder(), "config");
        this.langDir = new File(plugin.getDataFolder(), "lang");
    }

    YamlConfiguration config() {
        return merged;
    }

    // ------------------------------------------------------------
    // Pfade
    // ------------------------------------------------------------

    /** Pfad in der Sprachdatei zu einem Config-Pfad, oder {@code null}, wenn er kein Text ist. */
    static String langPath(String path) {
        String[] segs = path.split("\\.");
        if (GLOBAL.contains(segs[0])) return null; // z.B. modules.messages ist ein Modul-Schalter
        if (segs[0].equals("messages")) return join("general", segs, 1);
        if (segs[0].equals("command-aliases-messages")) return join("command-aliases", segs, 1);
        int i = Arrays.asList(segs).indexOf("messages");
        if (i > 0) {
            List<String> rest = new ArrayList<>(Arrays.asList(segs));
            rest.remove(i);
            return String.join(".", rest);
        }
        return isText(path) ? path : null;
    }

    /** Umkehrung von {@link #langPath(String)}. */
    static String configPath(String langPath) {
        String[] segs = langPath.split("\\.");
        if (segs[0].equals("general")) return join("messages", segs, 1);
        if (segs[0].equals("command-aliases")) return join("command-aliases-messages", segs, 1);
        if (isText(langPath)) return langPath;
        return join(segs[0] + ".messages", segs, 1);
    }

    private static boolean isText(String path) {
        return TEXTS.stream().anyMatch(t -> path.equals(t) || path.startsWith(t + "."));
    }

    private static String join(String head, String[] segs, int from) {
        StringBuilder sb = new StringBuilder(head);
        for (int i = from; i < segs.length; i++) sb.append('.').append(segs[i]);
        return sb.toString();
    }

    /** Gehört der Pfad in eine Config-Datei? Abschnitte nur, wenn sie leer sind oder Einstellungen (nicht nur Texte) enthalten. */
    private static boolean isSetting(ConfigurationSection root, String path) {
        if (langPath(path) != null) return false;
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null) return true;
        Set<String> keys = section.getKeys(true);
        return keys.isEmpty() || keys.stream().anyMatch(k -> !section.isConfigurationSection(k) && langPath(path + "." + k) == null);
    }

    private static String top(String path) {
        return path.split("\\.", 2)[0];
    }

    /** Datei, in die ein Top-Level-Key gehört (sofern er noch in keiner Datei steht). */
    private File defaultFile(String topKey) {
        return GLOBAL.contains(topKey) ? root : new File(configDir, GROUPS.getOrDefault(topKey, topKey) + ".yml");
    }

    /** Datei, die den Top-Level-Key schon enthält, sonst {@link #defaultFile(String)}. */
    private File fileOf(String topKey) {
        return configs.entrySet().stream().filter(e -> e.getValue().contains(topKey, true))
                .map(Map.Entry::getKey).findFirst().orElse(defaultFile(topKey));
    }

    private File langFile(String lang) {
        return new File(langDir, lang + ".yml");
    }

    // ------------------------------------------------------------
    // Laden
    // ------------------------------------------------------------

    synchronized void load() {
        YamlConfiguration defaults = resource("config.yml");
        configs.clear();
        boolean fresh = !root.exists();
        if (!fresh && isLegacy()) migrate();

        if (root.exists()) configs.put(root, YamlConfiguration.loadConfiguration(root));
        File[] modules = configDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (modules != null) for (File f : modules) configs.put(f, YamlConfiguration.loadConfiguration(f));
        addMissingSettings(defaults, fresh);

        language = configs.get(root).getString("language", DEFAULT_LANGUAGE);
        YamlConfiguration texts = loadLanguages(fresh);

        YamlConfiguration result = new YamlConfiguration();
        for (YamlConfiguration yml : configs.values()) {
            for (String path : yml.getKeys(true)) copyPath(yml, path, result, path, false);
        }
        for (String path : texts.getKeys(true)) {
            if (!texts.isConfigurationSection(path)) result.set(configPath(path), texts.get(path));
        }

        YamlConfiguration combinedDefaults = new YamlConfiguration();
        for (String path : defaults.getKeys(true)) copyPath(defaults, path, combinedDefaults, path, false);
        YamlConfiguration langDefaults = bundledLanguage(language);
        for (String path : langDefaults.getKeys(true)) {
            if (!langDefaults.isConfigurationSection(path)) combinedDefaults.set(configPath(path), langDefaults.get(path));
        }
        result.setDefaults(combinedDefaults);
        merged = result;
    }

    private YamlConfiguration resource(String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Mitgelieferte " + name + " nicht lesbar", e);
        }
        return new YamlConfiguration();
    }

    /** Mitgelieferte Texte einer Sprache; für unbekannte Sprachen die englischen. */
    private YamlConfiguration bundledLanguage(String lang) {
        return resource("lang/" + (BUNDLED_LANGUAGES.contains(lang) ? lang : TEMPLATE_LANGUAGE) + ".yml");
    }

    /** Alte config.yml mit allen Einstellungen (vor der Aufteilung)? */
    private boolean isLegacy() {
        File[] modules = configDir.listFiles((d, n) -> n.endsWith(".yml"));
        if (modules != null && modules.length > 0) return false;
        return YamlConfiguration.loadConfiguration(root).getKeys(false).stream().anyMatch(k -> !GLOBAL.contains(k));
    }

    /** Teilt die alte config.yml auf global, Module und Sprachdatei auf; Sicherung als config.yml.old. */
    private void migrate() {
        File backup = new File(plugin.getDataFolder(), "config.yml.old");
        try {
            Files.copy(root.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "config.yml konnte nicht gesichert werden – wird nicht aufgeteilt.", e);
            return;
        }
        YamlConfiguration old = YamlConfiguration.loadConfiguration(root);
        String lang = old.getString("language", DEFAULT_LANGUAGE);
        YamlConfiguration global = new YamlConfiguration();
        global.options().setHeader(old.options().getHeader());
        YamlConfiguration texts = new YamlConfiguration();
        Map<File, YamlConfiguration> out = new LinkedHashMap<>();
        out.put(root, global);
        for (String path : old.getKeys(true)) {
            String lp = langPath(path);
            if (lp != null) {
                // Abschnitts-Beschreibung (z.B. Platzhalter) auch in der Sprachdatei
                if (!texts.contains(top(lp))) {
                    texts.createSection(top(lp));
                    texts.setComments(top(lp), trimmed(old.getComments(top(path))));
                }
                copyPath(old, path, texts, lp, true);
            } else if (isSetting(old, path)) {
                copyPath(old, path, out.computeIfAbsent(defaultFile(top(path)), f -> new YamlConfiguration()), path, true);
            }
        }
        out.put(langFile(lang), texts);
        boolean ok = true;
        for (Map.Entry<File, YamlConfiguration> e : out.entrySet()) ok &= save(e.getKey(), e.getValue());
        if (ok) {
            plugin.getLogger().info("config.yml aufgeteilt in config.yml (global), " + (out.size() - 2)
                    + " Dateien in config/ und lang/" + lang + ".yml – Sicherung: config.yml.old");
        } else {
            plugin.getLogger().warning("config.yml nicht vollständig aufgeteilt – Sicherung liegt in config.yml.old.");
        }
    }

    /** Ergänzt Einstellungen aus der config.yml im JAR, die in keiner Datei stehen. */
    private void addMissingSettings(YamlConfiguration defaults, boolean fresh) {
        Set<File> changed = new HashSet<>();
        Set<File> created = new LinkedHashSet<>();
        List<String> added = new ArrayList<>();
        for (String path : defaults.getKeys(true)) {
            if (!isSetting(defaults, path)) continue;
            if (configs.values().stream().anyMatch(yml -> yml.contains(path, true))) continue;
            File file = fileOf(top(path));
            YamlConfiguration yml = configs.get(file);
            if (yml == null) {
                yml = new YamlConfiguration();
                if (file.equals(root)) yml.options().setHeader(defaults.options().getHeader());
                configs.put(file, yml);
                created.add(file);
            }
            copyPath(defaults, path, yml, path, true);
            changed.add(file);
            if (!created.contains(file)) added.add(path);
        }
        for (File file : changed) save(file, configs.get(file));
        if (!fresh && !created.isEmpty()) {
            plugin.getLogger().info("Neue Config-Dateien: " + created.stream().map(this::relative).toList());
        }
        if (!added.isEmpty()) plugin.getLogger().info("Config um " + added.size() + " neue Optionen ergänzt: " + String.join(", ", added));
    }

    /**
     * Legt die mitgelieferten Sprachdateien an bzw. ergänzt fehlende Texte; ebenso die eingestellte Sprache,
     * falls es eine eigene ist (Vorlage: Englisch). Liefert die Texte der eingestellten Sprache.
     */
    private YamlConfiguration loadLanguages(boolean fresh) {
        Set<String> languages = new LinkedHashSet<>(BUNDLED_LANGUAGES);
        languages.add(language);
        YamlConfiguration selected = new YamlConfiguration();
        for (String lang : languages) {
            File file = langFile(lang);
            boolean existed = file.exists();
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            YamlConfiguration bundled = bundledLanguage(lang);
            if (!existed) yml.options().setHeader(bundled.options().getHeader());
            List<String> added = new ArrayList<>();
            for (String path : bundled.getKeys(true)) {
                if (yml.contains(path, true)) continue;
                copyPath(bundled, path, yml, path, true);
                added.add(path);
            }
            if (!added.isEmpty() || !existed) save(file, yml);
            if (existed && !added.isEmpty()) {
                plugin.getLogger().info("lang/" + lang + ".yml um " + added.size() + " neue Texte ergänzt: " + String.join(", ", added));
            } else if (!existed && !fresh) {
                plugin.getLogger().info("Sprachdatei angelegt: lang/" + lang + ".yml");
            }
            if (lang.equals(language)) selected = yml;
        }
        if (!BUNDLED_LANGUAGES.contains(language)) {
            plugin.getLogger().info("Sprache " + language + " wird nicht mitgeliefert – fehlende Texte kommen aus " + TEMPLATE_LANGUAGE + ".");
        }
        return selected;
    }

    // ------------------------------------------------------------
    // Speichern (Änderungen aus Admin-Menüs)
    // ------------------------------------------------------------

    /** Schreibt geänderte Einstellungen zurück in ihre Dateien; Kommentare bleiben erhalten. Texte bleiben unberührt. */
    synchronized void save() {
        YamlConfiguration source = merged;
        Set<File> changed = new HashSet<>();
        configs.forEach((file, yml) -> {
            for (String path : yml.getKeys(true)) {
                if (yml.contains(path, true) && !source.contains(path, true)) {
                    yml.set(path, null);
                    changed.add(file);
                }
            }
        });
        for (String path : source.getKeys(true)) {
            if (!isSetting(source, path)) continue;
            File file = fileOf(top(path));
            YamlConfiguration yml = configs.computeIfAbsent(file, f -> new YamlConfiguration());
            if (source.isConfigurationSection(path)) {
                if (!yml.isConfigurationSection(path)) {
                    yml.createSection(path);
                    changed.add(file);
                }
            } else if (!Objects.equals(yml.get(path), source.get(path))) {
                yml.set(path, source.get(path));
                changed.add(file);
            }
        }
        for (File file : changed) save(file, configs.get(file));
    }

    private boolean save(File file, YamlConfiguration yml) {
        yml.options().width(10_000); // lange Texte nicht umbrechen
        try {
            yml.save(file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, relative(file) + " konnte nicht gespeichert werden", e);
            return false;
        }
    }

    private String relative(File file) {
        return plugin.getDataFolder().toPath().relativize(file.toPath()).toString();
    }

    /** Kopiert einen Wert (oder legt den Abschnitt an), auf Wunsch mit Kommentaren. */
    private static void copyPath(ConfigurationSection from, String fromPath, YamlConfiguration to, String toPath, boolean comments) {
        if (from.isConfigurationSection(fromPath)) {
            if (!to.isConfigurationSection(toPath)) to.createSection(toPath);
        } else {
            to.set(toPath, from.get(fromPath));
        }
        if (comments && from instanceof YamlConfiguration yml) {
            List<String> lines = yml.getComments(fromPath);
            // Top-Level-Keys stehen oft am Dateianfang: keine Leerzeilen davor
            if (!toPath.contains(".")) lines = trimmed(lines);
            if (!lines.isEmpty() || to.getComments(toPath).isEmpty()) to.setComments(toPath, lines);
            to.setInlineComments(toPath, yml.getInlineComments(fromPath));
        }
    }

    private static List<String> trimmed(List<String> comments) {
        List<String> lines = new ArrayList<>(comments);
        while (!lines.isEmpty() && (lines.getFirst() == null || lines.getFirst().isBlank())) lines.removeFirst();
        return lines;
    }
}
