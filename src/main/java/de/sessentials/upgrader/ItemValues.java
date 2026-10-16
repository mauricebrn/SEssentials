package de.sessentials.upgrader;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;
import org.bukkit.inventory.meta.Damageable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Wert jedes Items für den Upgrader. Feste Werte (Admin-Überschreibungen vor den mitgelieferten Grundwerten)
 * gelten immer; alle anderen Items bekommen den Wert ihres günstigsten Rezepts (Crafting, Ofen, Schmiede,
 * Steinsäge), solange alle Zutaten einen Wert haben.
 * Daraus entstehen zwei Seiten: Einsatz-Wert (beim Setzen) und Gewinn-Wert (als Ziel). Beide sind standardmäßig der
 * berechnete Wert und lassen sich getrennt überschreiben ({@code upgrader.input-overrides} / {@code output-overrides});
 * diese Überschreibungen wirken nur auf das Item selbst, nicht auf Rezepte. Ohne Wert auf einer Seite kann ein Item
 * auf dieser Seite nicht verwendet werden.
 */
public final class ItemValues {

    /** Rezept als Ergebnis + Zutaten; jede Zutat ist eine Auswahl möglicher Materialien. */
    private record Formula(Material result, int amount, List<Set<Material>> ingredients) {}

    private static final int MAX_PASSES = 64;

    /** Berechneter Grundwert. */
    private final Map<Material, Double> values;
    private final Map<Material, Double> input;
    private final Map<Material, Double> output;
    /** Mögliche Ziele, aufsteigend nach Gewinn-Wert. */
    private final List<Material> sortedOutput;

    private ItemValues(Map<Material, Double> values, Map<Material, Double> input, Map<Material, Double> output) {
        this.values = values;
        this.input = input;
        this.output = output;
        this.sortedOutput = output.keySet().stream()
                .sorted(Comparator.comparingDouble((Material m) -> output.get(m)).thenComparing(Material::name))
                .toList();
    }

    public static ItemValues compute(ConfigurationSection base, ConfigurationSection overrides,
                                     ConfigurationSection inputOverrides, ConfigurationSection outputOverrides, Logger log) {
        Map<Material, Double> fixed = new EnumMap<>(Material.class);
        read(base, fixed, log, "upgrader-values.yml");
        if (overrides != null) read(overrides, fixed, log, "upgrader.value-overrides");

        Map<Material, Double> values = new EnumMap<>(fixed);
        List<Formula> formulas = formulas(fixed.keySet());
        // kleinster Wert über alle Rezepte, bis sich nichts mehr ändert (Ketten wie Block ↔ Barren pendeln sich ein)
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (Formula f : formulas) {
                double cost = 0;
                for (Set<Material> choice : f.ingredients()) {
                    double cheapest = choice.stream().filter(values::containsKey).mapToDouble(values::get).min().orElse(-1);
                    if (cheapest < 0) {
                        cost = -1;
                        break;
                    }
                    cost += cheapest;
                }
                if (cost <= 0) continue;
                double value = cost / f.amount();
                Double old = values.get(f.result());
                if (old == null || value < old - 1e-9) {
                    values.put(f.result(), value);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        Map<Material, Double> input = new EnumMap<>(values);
        if (inputOverrides != null) read(inputOverrides, input, log, "upgrader.input-overrides");
        Map<Material, Double> output = new EnumMap<>(values);
        if (outputOverrides != null) read(outputOverrides, output, log, "upgrader.output-overrides");
        return new ItemValues(values, input, output);
    }

    /** Material, "#tag" oder Muster mit "*" (z.B. "*_ARMOR_TRIM_SMITHING_TEMPLATE") – jeweils mit Wert > 0. */
    private static void read(ConfigurationSection sec, Map<Material, Double> into, Logger log, String source) {
        for (String key : sec.getKeys(false)) {
            double value = sec.getDouble(key);
            if (value <= 0) {
                log.warning(source + ": " + key + " braucht einen Wert > 0 – übersprungen.");
                continue;
            }
            if (key.startsWith("#")) {
                Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft(key.substring(1)), Material.class);
                if (tag == null) log.warning(source + ": Item-Tag " + key + " gibt es nicht – übersprungen.");
                else tag.getValues().forEach(m -> into.put(m, value));
                continue;
            }
            if (key.contains("*")) {
                Pattern pattern = Pattern.compile(key.toUpperCase(Locale.ROOT).replace("*", "[A-Z0-9_]*"));
                List<Material> matches = allItems().stream().filter(m -> pattern.matcher(m.name()).matches()).toList();
                if (matches.isEmpty()) log.warning(source + ": " + key + " passt auf kein Item – übersprungen.");
                matches.forEach(m -> into.put(m, value));
                continue;
            }
            Material m = Material.matchMaterial(key);
            if (m == null || !m.isItem()) log.warning(source + ": " + key + " ist kein Item – übersprungen.");
            else into.put(m, value);
        }
    }

    private static List<Formula> formulas(Set<Material> fixed) {
        List<Formula> out = new ArrayList<>();
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            Recipe recipe = it.next();
            ItemStack result = recipe.getResult();
            if (result.getType().isAir() || fixed.contains(result.getType())) continue;
            List<RecipeChoice> choices = switch (recipe) {
                case ShapedRecipe r -> {
                    List<RecipeChoice> list = new ArrayList<>();
                    for (String row : r.getShape()) {
                        for (char c : row.toCharArray()) {
                            RecipeChoice choice = r.getChoiceMap().get(c);
                            if (choice != null) list.add(choice);
                        }
                    }
                    yield list;
                }
                case ShapelessRecipe r -> r.getChoiceList();
                case CookingRecipe<?> r -> List.of(r.getInputChoice());
                case StonecuttingRecipe r -> List.of(r.getInputChoice());
                case SmithingTransformRecipe r -> List.of(r.getTemplate(), r.getBase(), r.getAddition());
                case TransmuteRecipe r -> List.of(r.getInput(), r.getMaterial());
                default -> null; // Rüstungsverzierungen, Spezialrezepte (Karten, Feuerwerk, …)
            };
            if (choices == null) continue;
            List<Set<Material>> ingredients = new ArrayList<>();
            for (RecipeChoice choice : choices) {
                Set<Material> options = materials(choice);
                if (options == null) {
                    ingredients = null;
                    break;
                }
                if (!options.isEmpty()) ingredients.add(options);
            }
            if (ingredients != null && !ingredients.isEmpty()) out.add(new Formula(result.getType(), result.getAmount(), ingredients));
        }
        return out;
    }

    /** Mögliche Materialien einer Zutat; leer = keine Zutat, {@code null} = unbekannte Art (Rezept wird ignoriert). */
    private static Set<Material> materials(RecipeChoice choice) {
        return switch (choice) {
            case null -> Set.of();
            case RecipeChoice.MaterialChoice c -> Set.copyOf(c.getChoices());
            case RecipeChoice.ExactChoice c -> c.getChoices().stream().map(ItemStack::getType).collect(Collectors.toSet());
            case RecipeChoice.ItemTypeChoice c -> c.itemTypes().values().stream()
                    .map(k -> Material.matchMaterial(k.key().asString())).filter(m -> m != null).collect(Collectors.toSet());
            default -> choice == RecipeChoice.empty() ? Set.of() : null;
        };
    }

    // ------------------------------------------------------------
    // Abfragen
    // ------------------------------------------------------------

    /** Hat das Item einen berechneten Grundwert? */
    public boolean known(Material m) {
        return values.containsKey(m);
    }

    /** Berechneter Grundwert eines Items, 0 = unbekannt. */
    public double value(Material m) {
        return values.getOrDefault(m, 0.0);
    }

    public boolean knownInput(Material m) {
        return input.containsKey(m);
    }

    public boolean knownOutput(Material m) {
        return output.containsKey(m);
    }

    /** Wert eines Items beim Setzen, 0 = kann nicht gesetzt werden. */
    public double inputValue(Material m) {
        return input.getOrDefault(m, 0.0);
    }

    /** Wert eines Items als Gewinn, 0 = kann nicht gewonnen werden. */
    public double outputValue(Material m) {
        return output.getOrDefault(m, 0.0);
    }

    /** Einsatz-Wert des ganzen Stapels; Werkzeuge und Rüstung anteilig nach verbleibender Haltbarkeit. 0 = unbekannt. */
    public double inputValue(ItemStack item) {
        if (item == null || !knownInput(item.getType())) return 0;
        double each = inputValue(item.getType());
        int max = item.getType().getMaxDurability();
        if (max > 0 && item.getItemMeta() instanceof Damageable d && d.hasDamage()) {
            each *= Math.max(0, max - d.getDamage()) / (double) max;
        }
        return each * item.getAmount();
    }

    /** Alle möglichen Ziele, aufsteigend nach Gewinn-Wert. */
    public List<Material> sortedOutput() {
        return sortedOutput;
    }

    /** Alle Items, auch ohne Wert (für die Admin-Ansicht). */
    public static List<Material> allItems() {
        return Arrays.stream(Material.values()).filter(m -> !m.isLegacy() && m.isItem() && !m.isAir()).toList();
    }
}
