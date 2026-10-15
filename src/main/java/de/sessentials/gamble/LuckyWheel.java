package de.sessentials.gamble;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.admin.Menu;
import de.sessentials.bank.BankManager;
import de.sessentials.cost.Cost;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /gluecksrad: Einsatz einstellen und das Rad drehen. Einsatz und Gebühr gehen in den Server-Topf
 * ({@link BankManager#pot()}), Gewinne werden daraus bezahlt. Ein Einsatz ist nur möglich, wenn der Topf
 * den höchsten Gewinn decken kann. Das Ergebnis steht vor der Animation fest und ist sofort verbucht.
 */
public final class LuckyWheel {

    public static final String PERM_USE = "sess.gamble";
    public static final String PERM_ADMIN = "sess.gamble.admin";

    /**
     * Feld des Rads: Gewinn = Einsatz × {@code multiplier} bzw. beim Jackpot der ganze Topf;
     * Wahrscheinlichkeit = {@code weight} / Summe aller Gewichte.
     */
    public record Segment(double multiplier, int weight, boolean jackpot) {}

    private record Settings(List<Segment> segments, int totalWeight, double maxMultiplier, long minBet, long maxBet, Cost fee) {}

    private static final int POINTER_SLOT = 4;
    private static final int STRIP_START = 9;
    private static final int STRIP_CENTER = 4;
    private static final int MIN_STEPS = 30;
    private static final long[] STEPS = {-1000, -100, -10, 0, 0, 0, 10, 100, 1000};
    private static final Sound CLICK = Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 0.6f, 1.6f);
    private static final Sound WIN = Sound.sound(org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1f, 1f);
    private static final Sound LOSE = Sound.sound(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, Sound.Source.MASTER, 1f, 0.6f);

    private final SEssentials plugin;
    private final BankManager bank;
    private final Map<UUID, Long> stakes = new HashMap<>();
    /** Zuletzt angezeigte Rad-Position je Spieler, damit das Rad dort weiterdreht. */
    private final Map<UUID, Integer> offsets = new HashMap<>();
    private final Set<UUID> spinning = new HashSet<>();
    private volatile Settings settings;
    private final GambleStats stats;

    public LuckyWheel(SEssentials plugin) {
        this.plugin = plugin;
        this.bank = plugin.bank();
        this.stats = new GambleStats(plugin);
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        List<Segment> segments = new ArrayList<>();
        for (Map<?, ?> map : cfg.getMapList("gambling.segments")) {
            boolean jackpot = Boolean.TRUE.equals(map.get("jackpot"));
            double multiplier = jackpot ? 0 : map.get("multiplier") instanceof Number n ? n.doubleValue() : -1;
            int weight = map.get("weight") instanceof Number n ? n.intValue() : 0;
            if (multiplier < 0 || weight <= 0) {
                plugin.getLogger().warning("gambling.segments: " + map + " braucht multiplier >= 0 (oder jackpot: true) und weight > 0 – übersprungen.");
                continue;
            }
            segments.add(new Segment(multiplier, weight, jackpot));
        }
        if (segments.isEmpty()) plugin.getLogger().warning("gambling.segments ist leer – das Glücksrad ist deaktiviert.");
        long minBet = Math.max(1, cfg.getLong("gambling.min-bet", 1));
        long maxBet = Math.max(minBet, cfg.getLong("gambling.max-bet", minBet));
        settings = new Settings(List.copyOf(segments),
                segments.stream().mapToInt(Segment::weight).sum(),
                // der Jackpot zahlt genau den Topf und braucht deshalb keine Deckung
                segments.stream().filter(seg -> !seg.jackpot()).mapToDouble(Segment::multiplier).max().orElse(0),
                minBet, maxBet,
                Cost.fromConfig(cfg, "gambling.fee", bank, plugin.bankExchange(), plugin.getLogger()));
    }

    public GambleStats stats() {
        return stats;
    }

    public List<Segment> segments() {
        return settings.segments();
    }

    /** Auszahlungsquote laut Config: erwarteter Gewinn je eingesetzter Einheit (ohne Gebühr und ohne Jackpot, der vom Topf abhängt). */
    public double expectedReturn() {
        Settings s = settings;
        if (s.totalWeight() == 0) return 0;
        return s.segments().stream().mapToDouble(seg -> seg.multiplier() * seg.weight()).sum() / s.totalWeight();
    }

    public void shutdown() {
        stats.shutdown();
    }

    // ------------------------------------------------------------
    // Menü
    // ------------------------------------------------------------

    public void open(Player player) {
        Menu menu = new Menu(4, Component.text("Glücksrad", NamedTextColor.DARK_GRAY));
        menu.set(POINTER_SLOT, Menu.icon(Material.HOPPER, Component.text("▼", NamedTextColor.GOLD, TextDecoration.BOLD)), p -> {});
        render(menu, player);
        menu.fillEmpty();
        menu.open(player);
    }

    private void render(Menu menu, Player player) {
        Settings s = settings;
        long stake = stake(player, s);
        drawStrip(menu, s, offsets.getOrDefault(player.getUniqueId(), 0), stake);

        for (int i = 0; i < STEPS.length; i++) {
            long step = STEPS[i];
            if (step == 0) continue;
            Material icon = step < 0 ? Material.RED_DYE : Material.LIME_DYE;
            String label = (step < 0 ? "−" : "+") + bank.format(Math.abs(step));
            menu.set(18 + i, Menu.icon(icon, Component.text(label, step < 0 ? NamedTextColor.RED : NamedTextColor.GREEN)),
                    p -> changeStake(p, menu, step));
        }
        menu.set(22, Menu.icon(Material.GOLD_INGOT, Component.text("Einsatz: " + bank.format(stake), NamedTextColor.GOLD, TextDecoration.BOLD),
                line("Erlaubt", bank.format(s.minBet()) + " – " + bank.format(s.maxBet())),
                Component.empty(),
                Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » Betrag eingeben", NamedTextColor.GRAY))),
                this::askStake);

        menu.set(27, Menu.icon(Material.CHEST, Component.text("Topf", NamedTextColor.AQUA, TextDecoration.BOLD),
                line("Im Topf", bank.format(bank.pot())),
                line("Höchster Einsatz gerade", bank.format(maxStake(s))),
                line("Dein Kontostand", bank.format(bank.balance(player.getUniqueId()))),
                Component.empty(),
                Component.text("Befehlskosten und Gebühren", NamedTextColor.GRAY),
                Component.text("fließen in den Topf.", NamedTextColor.GRAY)), p -> {});
        menu.set(31, Menu.icon(Material.LIME_CONCRETE, Component.text("Drehen!", NamedTextColor.GREEN, TextDecoration.BOLD),
                line("Einsatz", bank.format(stake)),
                Component.text("Gebühr: ", NamedTextColor.GRAY).append(s.fee().isFree()
                        ? Component.text("keine", NamedTextColor.WHITE)
                        : plugin.text().render("<white><price>", s.fee().tags()))), p -> spin(p, menu));
        menu.set(35, Menu.icon(Material.BARRIER, Component.text("Schließen", NamedTextColor.RED)), Player::closeInventory);
    }

    /** Zeigt die Felder als Band, {@code offset} = Feld unter dem Zeiger. */
    private void drawStrip(Menu menu, Settings s, int offset, long stake) {
        int n = s.segments().size();
        for (int i = 0; i < 9; i++) {
            if (n == 0) {
                menu.set(STRIP_START + i, Menu.icon(Material.GRAY_STAINED_GLASS_PANE, Component.text("–", NamedTextColor.GRAY)), p -> {});
                continue;
            }
            Segment seg = s.segments().get(Math.floorMod(offset + i - STRIP_CENTER, n));
            menu.set(STRIP_START + i, Menu.icon(material(seg, s), Component.text(multiplier(seg), color(seg), TextDecoration.BOLD),
                    line("Chance", percent(seg.weight(), s.totalWeight())),
                    line("Gewinn", seg.jackpot() ? "der ganze Topf (" + bank.format(bank.pot() + stake) + ")"
                            : bank.format(win(stake, seg.multiplier())))), p -> {});
        }
    }

    private void changeStake(Player player, Menu menu, long delta) {
        if (spinning.contains(player.getUniqueId())) return;
        Settings s = settings;
        stakes.put(player.getUniqueId(), clamp(stake(player, s) + delta, s));
        render(menu, player);
    }

    private void askStake(Player player) {
        if (spinning.contains(player.getUniqueId())) return;
        player.closeInventory();
        player.sendMessage(msg("prompt-stake", player));
        plugin.chatPrompt().ask(player, input -> {
            long amount = parseAmount(input);
            if (amount < 0) {
                player.sendMessage(msg("invalid-amount", player));
            } else {
                stakes.put(player.getUniqueId(), clamp(amount, settings));
            }
            open(player);
        }, () -> player.sendMessage(msg("cancelled", player)));
    }

    // ------------------------------------------------------------
    // Admin-Panel
    // ------------------------------------------------------------

    public void openAdmin(Player player) {
        Settings s = settings;
        Menu menu = new Menu(3, Component.text("Glücksrad – Verwaltung", NamedTextColor.DARK_GRAY));
        menu.set(11, Menu.icon(Material.CHEST, Component.text("Topf: " + bank.format(bank.pot()), NamedTextColor.GOLD, TextDecoration.BOLD),
                line("Höchster Einsatz gerade", bank.format(maxStake(s))),
                Component.empty(),
                Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » Topf ändern", NamedTextColor.GRAY))),
                this::askPot);

        List<Component> fields = new ArrayList<>();
        for (Segment seg : s.segments()) {
            fields.add(Component.text(multiplier(seg), color(seg)).append(Component.text(" – " + percent(seg.weight(), s.totalWeight()), NamedTextColor.WHITE)));
        }
        if (fields.isEmpty()) fields.add(Component.text("Keine Felder – Glücksrad deaktiviert", NamedTextColor.RED));
        fields.add(Component.empty());
        fields.add(line("Einsatz", bank.format(s.minBet()) + " – " + bank.format(s.maxBet())));
        fields.add(Component.text("Gebühr: ", NamedTextColor.GRAY).append(s.fee().isFree()
                ? Component.text("keine", NamedTextColor.WHITE)
                : plugin.text().render("<white><price>", s.fee().tags())));
        fields.add(Component.empty());
        fields.add(Component.text("Ändern in config.yml (gambling)", NamedTextColor.DARK_GRAY));
        menu.set(13, Menu.icon(Material.PAPER, Component.text("Felder & Chancen", NamedTextColor.AQUA, TextDecoration.BOLD),
                fields.toArray(Component[]::new)), p -> {});

        menu.set(15, Menu.icon(Material.CLOCK, Component.text("Glücksrad öffnen", NamedTextColor.GREEN, TextDecoration.BOLD)), this::open);
        menu.set(22, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), plugin.adminPanel()::open);
        menu.fillEmpty();
        menu.open(player);
    }

    /** Topf per Chat: {@code +100}, {@code -50} oder {@code =200} (bzw. nur die Zahl). */
    private void askPot(Player player) {
        player.closeInventory();
        player.sendMessage(msg("prompt-pot", player));
        plugin.chatPrompt().ask(player, input -> {
            String in = input.replace(" ", "");
            char op = in.isEmpty() ? '?' : in.charAt(0);
            long amount = parseAmount(op == '+' || op == '-' || op == '=' ? in.substring(1) : in);
            if (amount < 0) {
                player.sendMessage(msg("invalid-amount", player));
            } else {
                switch (op) {
                    case '+' -> bank.addToPot(amount);
                    case '-' -> bank.addToPot(-amount);
                    default -> bank.setPot(amount);
                }
                player.sendMessage(msg("pot-set", player));
            }
            openAdmin(player);
        }, () -> player.sendMessage(msg("cancelled", player)));
    }

    // ------------------------------------------------------------
    // Drehen
    // ------------------------------------------------------------

    private void spin(Player player, Menu menu) {
        UUID id = player.getUniqueId();
        if (spinning.contains(id)) return;
        Settings s = settings;
        if (s.segments().isEmpty()) {
            player.sendMessage(msg("disabled", player));
            return;
        }
        long stake = stake(player, s);
        long maxWin = win(stake, s.maxMultiplier());
        if (bank.balance(id) < stake) {
            player.sendMessage(msg("not-enough", player, money("amount", stake)));
            return;
        }
        if (bank.pot() + stake < maxWin) {
            player.sendMessage(msg("pot-too-small", player, money("amount", maxStake(s))));
            return;
        }
        Cost fee = s.fee();
        if (!fee.take(player)) {
            player.sendMessage(msg("not-enough-fee", player, fee.tags()));
            return;
        }

        int index = pick(s);
        Segment result = s.segments().get(index);
        long win = result.jackpot() ? bank.wagerJackpot(id, stake) : win(stake, result.multiplier());
        if (win < 0 || !result.jackpot() && !bank.wager(id, stake, maxWin, win)) {
            // Gebühr (Geld) hat das Konto unter den Einsatz gedrückt, oder der Topf hat sich geändert
            fee.refund(player);
            player.sendMessage(bank.balance(id) < stake
                    ? msg("not-enough", player, money("amount", stake))
                    : msg("pot-too-small", player, money("amount", maxStake(s))));
            return;
        }

        stats.record(id, new GambleStats.Spin(System.currentTimeMillis(), player.getName(), stake, fee.value(),
                result.multiplier(), result.jackpot(), win), bank.pot());

        spinning.add(id);
        int from = offsets.getOrDefault(id, 0);
        int n = s.segments().size();
        int steps = MIN_STEPS + Math.floorMod(index - from - MIN_STEPS, n);
        SpinAnimation.run(plugin, steps, i -> {
            drawStrip(menu, s, from + i, stake);
            if (player.isOnline()) player.playSound(CLICK);
        }, () -> {
            spinning.remove(id);
            offsets.put(id, index);
            if (!player.isOnline()) return;
            TagResolver tags = TagResolver.resolver(money("amount", win), money("stake", stake),
                    TextService.placeholder("multiplier", multiplier(result)));
            if (result.jackpot()) {
                Bukkit.broadcast(msg("jackpot-broadcast", null, tags, TextService.placeholder("winner", player.getName())));
                player.sendMessage(msg("jackpot", player, tags));
            } else {
                player.sendMessage(msg(win > 0 ? "won" : "lost", player, tags));
            }
            player.playSound(win > stake ? WIN : LOSE);
            render(menu, player);
        });
    }

    private static int pick(Settings s) {
        int roll = ThreadLocalRandom.current().nextInt(s.totalWeight());
        for (int i = 0; i < s.segments().size(); i++) {
            roll -= s.segments().get(i).weight();
            if (roll < 0) return i;
        }
        throw new IllegalStateException("weights");
    }

    // ------------------------------------------------------------
    // Beträge
    // ------------------------------------------------------------

    private long stake(Player player, Settings s) {
        return clamp(stakes.getOrDefault(player.getUniqueId(), s.minBet()), s);
    }

    private static long clamp(long stake, Settings s) {
        return Math.max(s.minBet(), Math.min(s.maxBet(), stake));
    }

    private static long win(long stake, double multiplier) {
        return (long) Math.floor(stake * multiplier);
    }

    /** Größter Einsatz, dessen Höchstgewinn der Topf (plus Einsatz) noch deckt. */
    private long maxStake(Settings s) {
        if (s.maxMultiplier() <= 1) return s.maxBet();
        return Math.min(s.maxBet(), (long) Math.floor(bank.pot() / (s.maxMultiplier() - 1)));
    }

    /** Ganze Zahl ≥ 0, Tausenderpunkte erlaubt; -1 bei ungültiger Eingabe. */
    private static long parseAmount(String input) {
        try {
            return Math.max(-1, Long.parseLong(input.replace(".", "").replace(" ", "")));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------
    // Anzeige
    // ------------------------------------------------------------

    private static Material material(Segment seg, Settings s) {
        if (seg.jackpot()) return Material.NETHER_STAR;
        double m = seg.multiplier();
        if (m == 0) return Material.RED_CONCRETE;
        if (m < 1) return Material.ORANGE_CONCRETE;
        if (m == 1) return Material.YELLOW_CONCRETE;
        return m == s.maxMultiplier() ? Material.DIAMOND_BLOCK : Material.LIME_CONCRETE;
    }

    private static NamedTextColor color(Segment seg) {
        if (seg.jackpot()) return NamedTextColor.LIGHT_PURPLE;
        double m = seg.multiplier();
        return m == 0 ? NamedTextColor.RED : m < 1 ? NamedTextColor.GOLD : m == 1 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
    }

    private static String multiplier(Segment seg) {
        if (seg.jackpot()) return "JACKPOT";
        NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
        f.setMaximumFractionDigits(2);
        return "x" + f.format(seg.multiplier());
    }

    private static String percent(int weight, int total) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.GERMANY);
        f.setMaximumFractionDigits(1);
        return f.format(100.0 * weight / total) + " %";
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY).append(Component.text(value, NamedTextColor.WHITE));
    }

    private TagResolver money(String key, long amount) {
        return TextService.placeholder(key, bank.format(amount));
    }

    /** {@code <pot>} ist immer gesetzt. */
    private Component msg(String key, Player player, TagResolver... extra) {
        TagResolver[] all = new TagResolver[extra.length + 1];
        System.arraycopy(extra, 0, all, 0, extra.length);
        all[extra.length] = money("pot", bank.pot());
        return plugin.text().render(plugin.getConfig().getString("gambling.messages." + key), player, all);
    }

    // ------------------------------------------------------------
    // Befehl
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("gluecksrad")
                        .requires(src -> src.getSender().hasPermission(PERM_USE))
                        .executes(ctx -> {
                            if (ctx.getSource().getExecutor() instanceof Player player) open(player);
                            else showPot(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("topf")
                                .executes(ctx -> {
                                    showPot(ctx.getSource().getSender());
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.literal("setzen")
                                        .requires(src -> src.getSender().hasPermission(PERM_ADMIN))
                                        .then(Commands.argument("betrag", LongArgumentType.longArg(0)).executes(ctx -> {
                                            bank.setPot(LongArgumentType.getLong(ctx, "betrag"));
                                            CommandSender sender = ctx.getSource().getSender();
                                            sender.sendMessage(msg("pot-set", sender instanceof Player p ? p : null));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                        .build(),
                "Öffnet das Glücksrad",
                List.of("gamble", "gambling", "wheel"));
    }

    private void showPot(CommandSender sender) {
        sender.sendMessage(msg("pot", sender instanceof Player p ? p : null));
    }
}
