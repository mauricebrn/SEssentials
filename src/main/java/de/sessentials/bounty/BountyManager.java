package de.sessentials.bounty;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import de.sessentials.SEssentials;
import de.sessentials.bank.BankManager;
import de.sessentials.storage.AsyncFileWriter;
import de.sessentials.text.Durations;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /kopfgeld <spieler> <betrag> setzt ein Kopfgeld vom eigenen Bankkonto aus, /kopfgeld zeigt die höchsten.
 * Mehrere Spieler können auf dasselbe Ziel bieten. Wer das Ziel tötet, bekommt alle Kopfgelder, die er nicht
 * selbst ausgesetzt hat, und das Inventar des Opfers. Eigene Kopfgelder bleiben stehen.
 * Das Geld ist ab dem Aussetzen vom Konto abgebucht und liegt in bounties.yml. Abgelaufene oder von Admins
 * entfernte Kopfgelder gehen an den Aussetzenden zurück.
 */
public final class BountyManager implements Listener {

    static final String PERM_USE = "sess.bounty";
    public static final String PERM_ADMIN = "sess.bounty.admin";

    /** Ein Gebot; {@code placed} = letztes Aussetzen bzw. Nachlegen (ms), danach läuft die Zeit neu. */
    public record Bounty(long amount, long placed) {}

    public record Target(UUID id, long total) {}

    private final SEssentials plugin;
    private final BankManager bank;
    private final File file;
    private final AsyncFileWriter writer;
    /** Ziel → (Aussetzender → Gebot). */
    private final Map<UUID, LinkedHashMap<UUID, Bounty>> bounties = new HashMap<>();
    private final BukkitTask expiryTask;

    /** {@code null} = Kopfgelder laufen nicht ab. */
    private Duration maxDuration;

    public BountyManager(SEssentials plugin) {
        this.plugin = plugin;
        this.bank = plugin.bank();
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        this.writer = new AsyncFileWriter("Bounty", plugin.getLogger());
        load();
        reload();
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L * 60, 20L * 60);
    }

    public void reload() {
        String raw = plugin.getConfig().getString("bounty.max-duration", "0").trim();
        maxDuration = raw.equals("0") ? null : Durations.parse(raw);
        if (maxDuration == null && !raw.equals("0")) {
            plugin.getLogger().warning("bounty.max-duration ungültig: '" + raw + "' – Kopfgelder laufen nicht ab.");
        }
    }

    // ------------------------------------------------------------
    // Aussetzen und Anzeigen
    // ------------------------------------------------------------

    private void place(Player setter, String targetName, long amount) {
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            setter.sendMessage(msg("unknown-player", setter, TextService.placeholder("target", targetName)));
            return;
        }
        String name = nameOf(target.getUniqueId(), targetName);
        if (target.getUniqueId().equals(setter.getUniqueId())) {
            setter.sendMessage(msg("self", setter));
            return;
        }
        if (!bank.withdraw(setter.getUniqueId(), amount)) {
            setter.sendMessage(msg("not-enough", setter, money("amount", amount)));
            return;
        }
        long now = System.currentTimeMillis();
        bounties.computeIfAbsent(target.getUniqueId(), id -> new LinkedHashMap<>())
                .merge(setter.getUniqueId(), new Bounty(amount, now),
                        (old, add) -> new Bounty(Math.addExact(old.amount(), add.amount()), now));
        save();
        TagResolver tags = TagResolver.resolver(
                TextService.placeholder("setter", setter.getName()),
                TextService.placeholder("target", name),
                TextService.placeholder("duration", maxDuration == null ? "unbegrenzt" : Durations.format(maxDuration)),
                money("amount", amount),
                money("total", total(target.getUniqueId())));
        setter.sendMessage(msg("placed", setter, tags));
        Bukkit.broadcast(msg("placed-broadcast", null, tags));
    }

    private void list(CommandSender sender) {
        Player self = sender instanceof Player p ? p : null;
        List<Target> top = targets();
        if (top.isEmpty()) {
            sender.sendMessage(msg("list-empty", self));
            return;
        }
        sender.sendMessage(msg("list-header", self));
        int rank = 1;
        for (Target t : top.subList(0, Math.min(10, top.size()))) {
            sender.sendMessage(msg("list-entry", self,
                    TextService.placeholder("rank", String.valueOf(rank++)),
                    TextService.placeholder("target", nameOf(t.id(), "?")),
                    money("total", t.total())));
        }
    }

    /** Alle Ziele, höchstes Kopfgeld zuerst. */
    public List<Target> targets() {
        List<Target> list = new ArrayList<>();
        bounties.keySet().forEach(id -> list.add(new Target(id, total(id))));
        list.sort(Comparator.comparingLong(Target::total).reversed());
        return list;
    }

    /** Gebote auf {@code target} (Kopie), leer wenn keine. */
    public Map<UUID, Bounty> bountiesOn(UUID target) {
        Map<UUID, Bounty> open = bounties.get(target);
        return open == null ? Map.of() : new LinkedHashMap<>(open);
    }

    public long total(UUID target) {
        Map<UUID, Bounty> open = bounties.get(target);
        return open == null ? 0 : open.values().stream().mapToLong(Bounty::amount).sum();
    }

    /** Restzeit bis zum Ablauf, {@code null} = unbegrenzt. */
    public Duration remaining(Bounty bounty) {
        if (maxDuration == null) return null;
        return Duration.ofMillis(bounty.placed() + maxDuration.toMillis() - System.currentTimeMillis());
    }

    // ------------------------------------------------------------
    // Entfernen (Admin, Ablauf) – immer mit Rückerstattung
    // ------------------------------------------------------------

    /** Entfernt ein Gebot und erstattet es. {@code false}, wenn es das Gebot nicht (mehr) gibt. */
    public boolean remove(CommandSender admin, UUID target, UUID setter) {
        Map<UUID, Bounty> open = bounties.get(target);
        Bounty bounty = open == null ? null : open.remove(setter);
        if (bounty == null) return false;
        if (open.isEmpty()) bounties.remove(target);
        save();
        refund(target, setter, bounty.amount(), "removed");
        adminMessage(admin, target, bounty.amount());
        return true;
    }

    /** Entfernt alle Gebote auf {@code target} und erstattet sie. {@code false}, wenn keine da waren. */
    public boolean removeAll(CommandSender admin, UUID target) {
        Map<UUID, Bounty> open = bounties.remove(target);
        if (open == null) return false;
        save();
        open.forEach((setter, bounty) -> refund(target, setter, bounty.amount(), "removed"));
        adminMessage(admin, target, open.values().stream().mapToLong(Bounty::amount).sum());
        return true;
    }

    private void adminMessage(CommandSender admin, UUID target, long amount) {
        admin.sendMessage(msg("admin-removed", admin instanceof Player p ? p : null,
                TextService.placeholder("target", nameOf(target, "?")), money("amount", amount)));
    }

    private void expire() {
        if (maxDuration == null) return;
        long cutoff = System.currentTimeMillis() - maxDuration.toMillis();
        boolean changed = false;
        for (Iterator<Map.Entry<UUID, LinkedHashMap<UUID, Bounty>>> targets = bounties.entrySet().iterator(); targets.hasNext(); ) {
            Map.Entry<UUID, LinkedHashMap<UUID, Bounty>> t = targets.next();
            for (Iterator<Map.Entry<UUID, Bounty>> it = t.getValue().entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Bounty> e = it.next();
                if (e.getValue().placed() > cutoff) continue;
                it.remove();
                changed = true;
                refund(t.getKey(), e.getKey(), e.getValue().amount(), "expired");
            }
            if (t.getValue().isEmpty()) targets.remove();
        }
        if (changed) save();
    }

    private void refund(UUID target, UUID setter, long amount, String key) {
        bank.deposit(setter, amount);
        Player online = Bukkit.getPlayer(setter);
        if (online != null) {
            online.sendMessage(msg(key, online, TextService.placeholder("target", nameOf(target, "?")), money("amount", amount)));
        }
    }

    // ------------------------------------------------------------
    // Einlösen
    // ------------------------------------------------------------

    /** HIGH, damit Grabstein-/Drop-Plugins danach die bereits geleerten Drops sehen. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        long reward = claim(victim.getUniqueId(), killer.getUniqueId());
        if (reward == 0) return;

        // Inventar: ohne keepInventory sind es die Drops, mit keepInventory das Inventar selbst
        List<ItemStack> items = new ArrayList<>();
        if (event.getKeepInventory()) {
            for (ItemStack item : victim.getInventory().getContents()) {
                if (item != null && !item.isEmpty()) items.add(item);
            }
            victim.getInventory().clear();
        } else {
            items.addAll(event.getDrops());
            event.getDrops().clear();
        }
        killer.getInventory().addItem(items.toArray(new ItemStack[0]))
                .values().forEach(rest -> killer.getWorld().dropItemNaturally(killer.getLocation(), rest));
        bank.ensureAccount(killer);
        bank.deposit(killer.getUniqueId(), reward);

        TagResolver tags = TagResolver.resolver(
                TextService.placeholder("killer", killer.getName()),
                TextService.placeholder("target", victim.getName()),
                money("amount", reward));
        killer.sendMessage(msg("claimed", killer, tags));
        Bukkit.broadcast(msg("claimed-broadcast", null, tags));
    }

    /** Entfernt alle Kopfgelder auf {@code target}, die nicht von {@code killer} stammen, und gibt ihre Summe zurück. */
    private long claim(UUID target, UUID killer) {
        Map<UUID, Bounty> open = bounties.get(target);
        if (open == null) return 0;
        long reward = 0;
        for (Iterator<Map.Entry<UUID, Bounty>> it = open.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Bounty> e = it.next();
            if (e.getKey().equals(killer)) continue;
            reward = Math.addExact(reward, e.getValue().amount());
            it.remove();
        }
        if (open.isEmpty()) bounties.remove(target);
        if (reward > 0) save();
        return reward;
    }

    // ------------------------------------------------------------
    // Nachrichten
    // ------------------------------------------------------------

    Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("bounty.messages." + key), player, extra);
    }

    private TagResolver money(String key, long amount) {
        return TextService.placeholder(key, bank.format(amount));
    }

    static String nameOf(UUID id, String fallback) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : fallback;
    }

    // ------------------------------------------------------------
    // Registrierung
    // ------------------------------------------------------------

    public void register(Commands commands) {
        commands.register(
                Commands.literal("kopfgeld")
                        .requires(src -> src.getSender().hasPermission(PERM_USE) || src.getSender().hasPermission(PERM_ADMIN))
                        .executes(ctx -> {
                            list(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(Commands.literal("entfernen")
                                .requires(src -> src.getSender().hasPermission(PERM_ADMIN))
                                .then(Commands.argument("spieler", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            String typed = builder.getRemainingLowerCase();
                                            for (UUID id : bounties.keySet()) {
                                                String name = nameOf(id, null);
                                                if (name != null && name.toLowerCase().startsWith(typed)) builder.suggest(name);
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getSender();
                                            String name = StringArgumentType.getString(ctx, "spieler");
                                            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(name);
                                            if (target == null || !removeAll(sender, target.getUniqueId())) {
                                                sender.sendMessage(msg("none", sender instanceof Player p ? p : null,
                                                        TextService.placeholder("target", name)));
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(Commands.argument("spieler", StringArgumentType.word())
                                .requires(src -> src.getExecutor() instanceof Player && src.getSender().hasPermission(PERM_USE))
                                .suggests((ctx, builder) -> {
                                    Player self = (Player) ctx.getSource().getExecutor();
                                    String typed = builder.getRemainingLowerCase();
                                    for (Player p : Bukkit.getOnlinePlayers()) {
                                        if (p != self && self.canSee(p) && p.getName().toLowerCase().startsWith(typed)) {
                                            builder.suggest(p.getName());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("betrag", LongArgumentType.longArg(1)).executes(ctx -> {
                                    place((Player) ctx.getSource().getExecutor(),
                                            StringArgumentType.getString(ctx, "spieler"),
                                            LongArgumentType.getLong(ctx, "betrag"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .build(),
                "Kopfgeld auf einen Spieler aussetzen oder die höchsten anzeigen",
                List.of("bounty"));
    }

    // ------------------------------------------------------------
    // Speichern
    // ------------------------------------------------------------

    private void load() {
        ConfigurationSection sec = YamlConfiguration.loadConfiguration(file).getConfigurationSection("bounties");
        if (sec == null) return;
        for (String target : sec.getKeys(false)) {
            ConfigurationSection setters = sec.getConfigurationSection(target);
            if (setters == null) continue;
            LinkedHashMap<UUID, Bounty> open = new LinkedHashMap<>();
            for (String setter : setters.getKeys(false)) {
                // erstes Format ohne Zeitstempel: nur der Betrag, Zeit läuft ab jetzt
                Bounty bounty = setters.isConfigurationSection(setter)
                        ? new Bounty(setters.getLong(setter + ".amount"), setters.getLong(setter + ".placed"))
                        : new Bounty(setters.getLong(setter), System.currentTimeMillis());
                open.put(UUID.fromString(setter), bounty);
            }
            if (!open.isEmpty()) bounties.put(UUID.fromString(target), open);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        bounties.forEach((target, open) -> open.forEach((setter, bounty) -> {
            yml.set("bounties." + target + "." + setter + ".amount", bounty.amount());
            yml.set("bounties." + target + "." + setter + ".placed", bounty.placed());
        }));
        writer.write(file, yml.saveToString());
    }

    public void shutdown() {
        expiryTask.cancel();
        writer.shutdown();
    }
}
