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
 * Steinsäge), solange alle Zutaten einen Wert haben. Items ohne Wert können weder gesetzt noch gewonnen werden.
 */
public final class ItemValues {

    /** Rezept als Ergebnis + Zutaten; jede Zutat ist eine Auswahl möglicher Materialien. */
    private record Formula(Material result, int amount, List<Set<Material>> ingredients) {}

    private static final int MAX_PASSES = 64;

    private final Map<Material, Double> values;
    private final List<Material> sorted;

    private ItemValues(Map<Material, Double> values) {
        this.values = values;
        this.sorted = values.keySet().stream()
                .sorted(Comparator.comparingDouble((Material m) -> values.get(m)).thenComparing(Material::name))
                .toList();
    }

    public static ItemValues compute(ConfigurationSection base, ConfigurationSection overrides, Logger log) {
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
        return new ItemValues(values);
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

    public boolean known(Material m) {
        return values.containsKey(m);
    }

    /** Wert eines einzelnen Items dieses Materials, 0 = unbekannt. */
    public double value(Material m) {
        return values.getOrDefault(m, 0.0);
    }

    /** Wert des ganzen Stapels; Werkzeuge und Rüstung anteilig nach verbleibender Haltbarkeit. 0 = unbekannt. */
    public double value(ItemStack item) {
        if (item == null || !known(item.getType())) return 0;
        double each = value(item.getType());
        int max = item.getType().getMaxDurability();
        if (max > 0 && item.getItemMeta() instanceof Damageable d && d.hasDamage()) {
            each *= Math.max(0, max - d.getDamage()) / (double) max;
        }
        return each * item.getAmount();
    }

    /** Alle Items mit Wert, aufsteigend. */
    public List<Material> sorted() {
        return sorted;
    }

    /** Alle Items, auch ohne Wert (für die Admin-Ansicht). */
    public static List<Material> allItems() {
        return Arrays.stream(Material.values()).filter(m -> !m.isLegacy() && m.isItem() && !m.isAir()).toList();
    }
}
