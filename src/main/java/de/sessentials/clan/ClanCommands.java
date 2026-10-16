package de.sessentials.clan;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import de.sessentials.SEssentials;
import de.sessentials.cost.Cost;
import de.sessentials.text.TextService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * /clan: Gründen, Farbe, Einladungen, Mitglieder, Home-Abstimmung und -Teleport, Truhe und Clan-Konto.
 * Einladungen und Abstimmungen liegen nur im Speicher (verfallen nach Ablauf oder Neustart).
 */
public final class ClanCommands implements Listener {

    static final String PERM_USE = "sess.clan";
    static final String PERM_ADMIN = "sess.clan.admin";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");

    /** Laufende Abstimmung über ein neues Clan-Home. */
    private record Vote(Location location, String proposer, Set<UUID> yes, Set<UUID> no, long expires) {}

    private final SEssentials plugin;
    private final ClanManager clans;
    private final ClanChest chest;
    private final ClanFriendlyFire friendlyFire;
    /** Eingeladener → (Clan-Name klein → Ablaufzeit ms), neueste zuletzt. */
    private final Map<UUID, LinkedHashMap<String, Long>> invites = new HashMap<>();
    private final Map<Clan, Vote> votes = new HashMap<>();

    private volatile Cost createCost;
    private volatile Cost homeCost;
    private volatile Cost remoteCost;
    private volatile String tagFormat;

    public ClanCommands(SEssentials plugin, ClanManager clans) {
        this.plugin = plugin;
        this.clans = clans;
        this.chest = new ClanChest(plugin, clans, this);
        this.friendlyFire = new ClanFriendlyFire(plugin, clans);
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        createCost = Cost.fromConfig(cfg, "clans.create-cost", plugin.bank(), plugin.bankExchange(), plugin.getLogger());
        homeCost = Cost.fromConfig(cfg, "clans.home.cost", plugin.bank(), plugin.bankExchange(), plugin.getLogger());
        remoteCost = Cost.fromConfig(cfg, "clans.chest.remote-cost", plugin.bank(), plugin.bankExchange(), plugin.getLogger());
        tagFormat = cfg.getString("clans.tag-format");
    }

    /** Truhe für das Admin-Panel (am Block geöffnet, also mit Einlagern). */
    void openChest(Player player, Clan clan) {
        chest.open(player, clan, false);
    }

    /** Listener gegen Schaden unter Clan-Mitgliedern. */
    public Listener friendlyFireListener() {
        return friendlyFire;
    }

    /** Listener der Clan-Truhe (Block und Inventar). */
    public Listener chestListener() {
        return chest;
    }

    /** {@code <clan>} für Chat, TAB und Nametags: formatierter Clan-Tag oder leer. Auch asynchron aufrufbar. */
    public Component tag(Player player) {
        Clan clan = clans.of(player.getUniqueId());
        if (clan == null) return Component.empty();
        return plugin.text().render(tagFormat, Placeholder.component("name", Component.text(clan.name, clan.color)));
    }

    // ------------------------------------------------------------
    // Gründen und Verwalten
    // ------------------------------------------------------------

    private void create(Player player, String name, String colorName) {
        if (clans.of(player.getUniqueId()) != null) {
            player.sendMessage(msg("already-in-clan", player));
            return;
        }
        int min = plugin.getConfig().getInt("clans.name.min-length");
        int max = plugin.getConfig().getInt("clans.name.max-length");
        if (!NAME.matcher(name).matches() || name.length() < min || name.length() > max) {
            player.sendMessage(msg("invalid-name", player, TextService.placeholder("min", String.valueOf(min)),
                    TextService.placeholder("max", String.valueOf(max))));
            return;
        }
        if (clans.byName(name) != null) {
            player.sendMessage(msg("name-taken", player, TextService.placeholder("name", name)));
            return;
        }
        NamedTextColor color = color(player, colorName);
        if (color == null) return;
        Cost cost = createCost;
        if (!cost.take(player)) {
            player.sendMessage(msg("not-enough", player, cost.tags()));
            return;
        }
        Clan clan = clans.create(name, color, player.getUniqueId());
        player.sendMessage(msg("created", player, clanTags(clan), cost.tags()));
    }

    private void setColor(Player player, String colorName) {
        Clan clan = leaderClan(player);
        if (clan == null) return;
        NamedTextColor color = color(player, colorName);
        if (color == null) return;
        clans.setColor(clan, color);
        notify(clan, "color-changed", clanTags(clan));
    }

    /** Farbe aus {@code clans.colors}; meldet ungültige Eingaben selbst. */
    private NamedTextColor color(Player player, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        NamedTextColor color = NamedTextColor.NAMES.value(key);
        if (color == null || !allowedColors().contains(key)) {
            player.sendMessage(msg("invalid-color", player, TextService.placeholder("colors", String.join(", ", allowedColors()))));
            return null;
        }
        return color;
    }

    private List<String> allowedColors() {
        return plugin.getConfig().getStringList("clans.colors").stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
    }

    private void invite(Player player, String targetName) {
        Clan clan = memberClan(player);
        if (clan == null || !mayDo(player, clan, "invite")) return;
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !player.canSee(target)) {
            player.sendMessage(msg("player-not-found", player, TextService.placeholder("target", targetName)));
            return;
        }
        TagResolver tags = TagResolver.resolver(clanTags(clan), TextService.placeholder("target", target.getName()),
                TextService.placeholder("inviter", player.getName()),
                TextService.placeholder("seconds", String.valueOf(inviteSeconds())));
        if (clans.of(target.getUniqueId()) != null) {
            player.sendMessage(msg("target-in-clan", player, tags));
            return;
        }
        if (full(clan)) {
            player.sendMessage(msg("clan-full", player, tags));
            return;
        }
        LinkedHashMap<String, Long> open = invites.computeIfAbsent(target.getUniqueId(), id -> new LinkedHashMap<>());
        String key = clan.name.toLowerCase(Locale.ROOT);
        open.remove(key);
        open.put(key, System.currentTimeMillis() + inviteSeconds() * 1000L);
        player.sendMessage(msg("invite-sent", player, tags));
        target.sendMessage(msg("invited", target, tags));
    }

    /** Einladung annehmen ({@code accept}) oder ablehnen; ohne Namen gilt die neueste. */
    private void answer(Player player, String clanName, boolean accept) {
        LinkedHashMap<String, Long> open = invites.getOrDefault(player.getUniqueId(), new LinkedHashMap<>());
        long now = System.currentTimeMillis();
        open.values().removeIf(expiry -> expiry < now);
        String key = null;
        if (clanName == null) {
            for (String k : open.keySet()) key = k;
        } else if (open.containsKey(clanName.toLowerCase(Locale.ROOT))) {
            key = clanName.toLowerCase(Locale.ROOT);
        }
        Clan clan = key == null ? null : clans.byName(key);
        if (clan == null) {
            player.sendMessage(msg("invite-not-found", player));
            return;
        }
        open.remove(key);
        TagResolver tags = TagResolver.resolver(clanTags(clan), TextService.placeholder("target", player.getName()));
        if (!accept) {
            player.sendMessage(msg("declined", player, tags));
            notify(clan, "declined-clan", tags);
            return;
        }
        if (clans.of(player.getUniqueId()) != null) {
            player.sendMessage(msg("already-in-clan", player));
            return;
        }
        if (full(clan)) {
            player.sendMessage(msg("clan-full", player, tags));
            return;
        }
        invites.remove(player.getUniqueId());
        clans.join(clan, player.getUniqueId());
        notify(clan, "joined", tags);
    }

    private void leave(Player player) {
        Clan clan = memberClan(player);
        if (clan == null) return;
        if (clan.isLeader(player.getUniqueId())) {
            player.sendMessage(msg("leader-cannot-leave", player));
            return;
        }
        TagResolver tags = TagResolver.resolver(clanTags(clan), TextService.placeholder("target", player.getName()));
        clans.leave(clan, player.getUniqueId());
        player.sendMessage(msg("left", player, tags));
        notify(clan, "left-clan", tags);
    }

    private void kick(Player player, String targetName) {
        Clan clan = leaderClan(player);
        if (clan == null) return;
        UUID target = member(player, clan, targetName);
        if (target == null) return;
        if (clan.isLeader(target)) {
            player.sendMessage(msg("self", player));
            return;
        }
        removeMember(clan, target);
    }

    /** Wirft {@code target} raus (nicht den Leiter – prüft der Aufrufer) und meldet es Clan und Betroffenem. */
    void removeMember(Clan clan, UUID target) {
        TagResolver tags = TagResolver.resolver(clanTags(clan), TextService.placeholder("target", nameOf(target)));
        clans.leave(clan, target);
        notify(clan, "kicked", tags);
        Player online = Bukkit.getPlayer(target);
        if (online != null) online.sendMessage(msg("kicked-target", online, tags));
    }

    private void transferLeader(Player player, String targetName) {
        Clan clan = leaderClan(player);
        if (clan == null) return;
        UUID target = member(player, clan, targetName);
        if (target == null) return;
        if (clan.isLeader(target)) {
            player.sendMessage(msg("self", player));
            return;
        }
        changeLeader(clan, target);
    }

    void changeLeader(Clan clan, UUID target) {
        clans.setLeader(clan, target);
        notify(clan, "new-leader", clanTags(clan), TextService.placeholder("target", nameOf(target)));
    }

    private void disband(Player player) {
        Clan clan = leaderClan(player);
        if (clan == null) return;
        if (!clans.chestEmpty(clan)) {
            player.sendMessage(msg("chest-not-empty", player));
            return;
        }
        TagResolver tags = TagResolver.resolver(clanTags(clan), money("amount", clan.balance));
        notify(clan, "disbanded", tags);
        forget(clan);
        clans.disband(clan);
    }

    /** Admin: auflösen erzwingen; Truheninhalt geht an den Admin (von der Konsole nur bei leerer Truhe). */
    void adminDisband(CommandSender sender, Clan clan) {
        Player self = sender instanceof Player p ? p : null;
        if (!clans.chestEmpty(clan)) {
            if (self == null) {
                sender.sendMessage(msg("chest-not-empty", null));
                return;
            }
            for (ItemStack item : clans.chest(clan).getContents()) {
                if (item == null || item.isEmpty()) continue;
                self.getInventory().addItem(item).values().forEach(rest -> self.getWorld().dropItemNaturally(self.getLocation(), rest));
            }
            clans.chest(clan).clear();
        }
        TagResolver tags = TagResolver.resolver(clanTags(clan), money("amount", clan.balance));
        notify(clan, "disbanded", tags);
        forget(clan);
        clans.disband(clan);
        sender.sendMessage(msg("admin-disbanded", self, tags));
    }

    /** Offene Einladungen und Abstimmungen eines aufgelösten Clans verwerfen. */
    private void forget(Clan clan) {
        votes.remove(clan);
        String key = clan.name.toLowerCase(Locale.ROOT);
        invites.values().forEach(open -> open.remove(key));
    }

    /** {@code on == null} zeigt nur den Stand. */
    private void friendlyFire(Player player, Boolean on) {
        Clan clan = memberClan(player);
        if (clan == null) return;
        if (on == null) {
            player.sendMessage(msg("friendly-fire-status", player, clanTags(clan), ffTag(clan)));
            return;
        }
        if (!mayDo(player, clan, "friendly-fire")) return;
        setFriendlyFire(clan, on, player.getName());
    }

    /** Setzt Friendly Fire und meldet es allen Mitgliedern ({@code by} = wer es geändert hat). */
    void setFriendlyFire(Clan clan, boolean on, String by) {
        clans.setFriendlyFire(clan, on);
        notify(clan, "friendly-fire-changed", clanTags(clan), ffTag(clan), TextService.placeholder("target", by));
    }

    /** {@code <state>}: an/aus aus der Config. */
    TagResolver ffTag(Clan clan) {
        return Placeholder.component("state", msg(clan.friendlyFire ? "state-on" : "state-off", null));
    }

    // ------------------------------------------------------------
    // Anzeige
    // ------------------------------------------------------------

    private void info(CommandSender sender, String clanName) {
        Player self = sender instanceof Player p ? p : null;
        Clan clan = clanName != null ? clans.byName(clanName) : self != null ? clans.of(self.getUniqueId()) : null;
        if (clan == null) {
            sender.sendMessage(clanName != null ? msg("unknown-clan", self, TextService.placeholder("name", clanName)) : msg("no-clan", self));
            return;
        }
        String home = clan.home == null ? plugin.getConfig().getString("clans.messages.home-none")
                : clan.home.getWorld().getName() + " " + clan.home.getBlockX() + " " + clan.home.getBlockY() + " " + clan.home.getBlockZ();
        String chestState = plugin.getConfig().getString("clans.messages.chest-state-"
                + (!clan.chestBought ? "none" : clan.chestBlock == null ? "unplaced" : "placed"));
        sender.sendMessage(msg("info", self, clanTags(clan),
                TextService.placeholder("leader", nameOf(clan.leader)),
                TextService.placeholder("members", String.join(", ", clan.members.stream().map(this::nameOf).toList())),
                TextService.placeholder("count", String.valueOf(clan.members.size())),
                TextService.placeholder("home", home),
                TextService.placeholder("chest", chestState),
                ffTag(clan),
                money("balance", clan.balance)));
    }

    private void list(CommandSender sender) {
        Player self = sender instanceof Player p ? p : null;
        if (clans.all().isEmpty()) {
            sender.sendMessage(msg("list-empty", self));
            return;
        }
        sender.sendMessage(msg("list-header", self, TextService.placeholder("count", String.valueOf(clans.all().size()))));
        clans.all().stream().sorted((a, b) -> b.members.size() - a.members.size()).forEach(clan ->
                sender.sendMessage(msg("list-entry", self, clanTags(clan),
                        TextService.placeholder("count", String.valueOf(clan.members.size())),
                        TextService.placeholder("leader", nameOf(clan.leader)))));
    }

    // ------------------------------------------------------------
    // Home: Abstimmung und Teleport
    // ------------------------------------------------------------

    private void proposeHome(Player player) {
        Clan clan = memberClan(player);
        if (clan == null || !mayDo(player, clan, "propose-home")) return;
        Vote running = vote(clan);
        if (running != null) {
            player.sendMessage(msg("vote-running", player, voteTags(clan, running)));
            return;
        }
        long expires = System.currentTimeMillis() + plugin.getConfig().getLong("clans.home.vote-minutes") * 60_000L;
        Vote vote = new Vote(player.getLocation().clone(), player.getName(), new HashSet<>(Set.of(player.getUniqueId())), new HashSet<>(), expires);
        votes.put(clan, vote);
        notify(clan, "vote-started", voteTags(clan, vote));
        evaluate(clan, vote);
    }

    private void castVote(Player player, boolean yes) {
        Clan clan = memberClan(player);
        if (clan == null) return;
        Vote vote = vote(clan);
        if (vote == null) {
            player.sendMessage(msg("no-vote", player));
            return;
        }
        UUID id = player.getUniqueId();
        (yes ? vote.no() : vote.yes()).remove(id);
        (yes ? vote.yes() : vote.no()).add(id);
        player.sendMessage(msg(yes ? "voted-yes" : "voted-no", player, voteTags(clan, vote)));
        evaluate(clan, vote);
    }

    /** Mehr als {@code vote-percent} % Ja-Stimmen aller Mitglieder → Home gesetzt; nicht mehr erreichbar → gescheitert. */
    private void evaluate(Clan clan, Vote vote) {
        int members = clan.members.size();
        int percent = Math.max(0, Math.min(99, plugin.getConfig().getInt("clans.home.vote-percent")));
        long yes = vote.yes().stream().filter(clan.members::contains).count();
        long no = vote.no().stream().filter(clan.members::contains).count();
        if (yes * 100 > (long) members * percent) {
            votes.remove(clan);
            clans.setHome(clan, vote.location());
            notify(clan, "home-set", voteTags(clan, vote));
        } else if ((members - no) * 100 <= (long) members * percent) {
            votes.remove(clan);
            notify(clan, "vote-failed", voteTags(clan, vote));
        }
    }

    /** Laufende Abstimmung, abgelaufene werden dabei verworfen (und gemeldet). */
    private Vote vote(Clan clan) {
        Vote vote = votes.get(clan);
        if (vote != null && vote.expires() < System.currentTimeMillis()) {
            votes.remove(clan);
            notify(clan, "vote-expired", voteTags(clan, vote));
            return null;
        }
        return vote;
    }

    private TagResolver voteTags(Clan clan, Vote vote) {
        Location l = vote.location();
        return TagResolver.resolver(clanTags(clan),
                TextService.placeholder("proposer", vote.proposer()),
                TextService.placeholder("location", l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()),
                TextService.placeholder("yes", String.valueOf(vote.yes().size())),
                TextService.placeholder("no", String.valueOf(vote.no().size())),
                TextService.placeholder("count", String.valueOf(clan.members.size())),
                TextService.placeholder("percent", String.valueOf(plugin.getConfig().getInt("clans.home.vote-percent"))),
                TextService.placeholder("minutes", String.valueOf(plugin.getConfig().getLong("clans.home.vote-minutes"))));
    }

    private void teleportHome(Player player) {
        Clan clan = memberClan(player);
        if (clan == null) return;
        if (clan.home == null) {
            player.sendMessage(msg("home-not-set", player));
            return;
        }
        Cost cost = homeCost;
        if (!cost.take(player)) {
            player.sendMessage(msg("not-enough", player, cost.tags()));
            return;
        }
        player.teleportAsync(clan.home).thenAccept(ok -> {
            if (!ok) {
                cost.refund(player);
                player.sendMessage(msg("teleport-failed", player));
                return;
            }
            player.sendMessage(msg(cost.isFree() ? "home-teleported" : "home-teleported-paid", player, cost.tags()));
        });
    }

    // ------------------------------------------------------------
    // Truhe
    // ------------------------------------------------------------

    private void buyChest(Player player) {
        Clan clan = memberClan(player);
        if (clan == null || !mayDo(player, clan, "buy-chest")) return;
        long price = Math.max(0, plugin.getConfig().getLong("clans.chest.price"));
        if (clan.chestBlock != null) {
            player.sendMessage(msg("chest-already-placed", player));
            return;
        }
        if (!clan.chestBought) {
            if (!clans.payServer(clan, price)) {
                player.sendMessage(msg("not-enough-clan", player, money("amount", price)));
                return;
            }
            clans.setChestBought(clan);
            notify(clan, "chest-bought", TagResolver.resolver(clanTags(clan), money("amount", price),
                    TextService.placeholder("target", player.getName())));
        }
        player.getInventory().addItem(chest.item()).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        player.sendMessage(msg("chest-given", player, TextService.placeholder("distance",
                String.valueOf(plugin.getConfig().getInt("clans.chest.max-distance-from-home")))));
    }

    private void openRemote(Player player) {
        Clan clan = memberClan(player);
        if (clan == null) return;
        if (!plugin.getConfig().getBoolean("clans.chest.remote-enabled")) {
            player.sendMessage(msg("chest-remote-disabled", player));
            return;
        }
        if (clan.chestBlock == null) {
            player.sendMessage(msg("chest-not-placed", player));
            return;
        }
        Cost cost = remoteCost;
        if (!cost.take(player)) {
            player.sendMessage(msg("not-enough", player, cost.tags()));
            return;
        }
        if (!cost.isFree()) player.sendMessage(msg("chest-remote-paid", player, cost.tags()));
        chest.open(player, clan, true);
    }

    // ------------------------------------------------------------
    // Clan-Konto
    // ------------------------------------------------------------

    private void bankInfo(Player player) {
        Clan clan = memberClan(player);
        if (clan != null) player.sendMessage(msg("bank-balance", player, clanTags(clan), money("balance", clan.balance)));
    }

    private void bankDeposit(Player player, long amount) {
        Clan clan = memberClan(player);
        if (clan == null) return;
        if (!clans.deposit(clan, player.getUniqueId(), amount)) {
            player.sendMessage(msg("not-enough-own", player, money("amount", amount)));
            return;
        }
        notify(clan, "bank-deposited", clanTags(clan), money("amount", amount), money("balance", clan.balance),
                TextService.placeholder("target", player.getName()));
    }

    private void bankWithdraw(Player player, long amount) {
        Clan clan = memberClan(player);
        if (clan == null || !mayDo(player, clan, "withdraw")) return;
        if (!clans.withdraw(clan, player.getUniqueId(), amount)) {
            player.sendMessage(msg("not-enough-clan", player, money("amount", amount)));
            return;
        }
        notify(clan, "bank-withdrawn", clanTags(clan), money("amount", amount), money("balance", clan.balance),
                TextService.placeholder("target", player.getName()));
    }

    // ------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------

    /** Clan des Spielers; sonst Meldung und {@code null}. */
    private Clan memberClan(Player player) {
        Clan clan = clans.of(player.getUniqueId());
        if (clan == null) player.sendMessage(msg("no-clan", player));
        return clan;
    }

    /** Clan, in dem der Spieler Leiter ist; sonst Meldung und {@code null}. */
    private Clan leaderClan(Player player) {
        Clan clan = memberClan(player);
        if (clan != null && !clan.isLeader(player.getUniqueId())) {
            player.sendMessage(msg("not-leader", player));
            return null;
        }
        return clan;
    }

    /** {@code clans.leader-only.<action>}: nur der Leiter darf. Meldet Verbote selbst. */
    private boolean mayDo(Player player, Clan clan, String action) {
        if (!plugin.getConfig().getBoolean("clans.leader-only." + action) || clan.isLeader(player.getUniqueId())) return true;
        player.sendMessage(msg("not-leader", player));
        return false;
    }

    /** Mitglied nach Name; sonst Meldung und {@code null}. */
    private UUID member(Player player, Clan clan, String name) {
        for (UUID id : clan.members) {
            if (name.equalsIgnoreCase(nameOf(id))) return id;
        }
        player.sendMessage(msg("not-member", player, TextService.placeholder("target", name)));
        return null;
    }

    private boolean full(Clan clan) {
        int max = plugin.getConfig().getInt("clans.max-members");
        return max > 0 && clan.members.size() >= max;
    }

    private long inviteSeconds() {
        return Math.max(1, plugin.getConfig().getLong("clans.invite-expire-seconds"));
    }

    String nameOf(UUID id) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        return p.getName() != null ? p.getName() : "?";
    }

    /** {@code <clan>} (farbiger Name) und {@code <clan_name>} (reiner Text). */
    TagResolver clanTags(Clan clan) {
        return TagResolver.resolver(Placeholder.component("clan", Component.text(clan.name, clan.color)),
                TextService.placeholder("clan_name", clan.name));
    }

    private TagResolver money(String key, long amount) {
        return TextService.placeholder(key, plugin.bank().format(amount));
    }

    /** Nachricht an alle Mitglieder, die online sind. */
    private void notify(Clan clan, String key, TagResolver... tags) {
        for (UUID id : clan.members) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(msg(key, p, tags));
        }
    }

    Component msg(String key, Player player, TagResolver... extra) {
        return plugin.text().render(plugin.getConfig().getString("clans.messages." + key), player, extra);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        invites.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------
    // Registrierung
    // ------------------------------------------------------------

    private static Command<CommandSourceStack> player(Consumer<Player> action) {
        return ctx -> {
            action.accept((Player) ctx.getSource().getExecutor());
            return Command.SINGLE_SUCCESS;
        };
    }

    private static String str(CommandContext<CommandSourceStack> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private static Player self(CommandContext<CommandSourceStack> ctx) {
        return (Player) ctx.getSource().getExecutor();
    }

    private SuggestionProvider<CommandSourceStack> onlinePlayers() {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            Player self = ctx.getSource().getExecutor() instanceof Player p ? p : null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != self && (self == null || self.canSee(p)) && p.getName().toLowerCase().startsWith(typed)) builder.suggest(p.getName());
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> ownMembers() {
        return (ctx, builder) -> {
            Clan clan = ctx.getSource().getExecutor() instanceof Player p ? clans.of(p.getUniqueId()) : null;
            if (clan != null) {
                String typed = builder.getRemainingLowerCase();
                clan.members.stream().map(this::nameOf).filter(n -> n.toLowerCase().startsWith(typed)).forEach(builder::suggest);
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> clanNames() {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            clans.all().stream().map(Clan::name).filter(n -> n.toLowerCase().startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> invitedClans() {
        return (ctx, builder) -> {
            if (ctx.getSource().getExecutor() instanceof Player p) {
                invites.getOrDefault(p.getUniqueId(), new LinkedHashMap<>()).keySet().stream()
                        .map(k -> clans.byName(k)).filter(java.util.Objects::nonNull).map(Clan::name).forEach(builder::suggest);
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> colors() {
        return (ctx, builder) -> {
            String typed = builder.getRemainingLowerCase();
            allowedColors().stream().filter(c -> c.startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private static LiteralArgumentBuilder<CommandSourceStack> sub(String name) {
        return Commands.literal(name).requires(src -> src.getExecutor() instanceof Player);
    }

    public void register(Commands commands) {
        commands.register(Commands.literal("clan")
                        .requires(src -> src.getSender().hasPermission(PERM_USE) || src.getSender().hasPermission(PERM_ADMIN))
                        .executes(ctx -> {
                            info(ctx.getSource().getSender(), null);
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(sub("erstellen").then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("farbe", StringArgumentType.word()).suggests(colors())
                                        .executes(ctx -> {
                                            create(self(ctx), str(ctx, "name"), str(ctx, "farbe"));
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                        .then(sub("farbe").then(Commands.argument("farbe", StringArgumentType.word()).suggests(colors())
                                .executes(ctx -> {
                                    setColor(self(ctx), str(ctx, "farbe"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(sub("einladen").then(Commands.argument("spieler", StringArgumentType.word()).suggests(onlinePlayers())
                                .executes(ctx -> {
                                    invite(self(ctx), str(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(sub("annehmen").executes(player(p -> answer(p, null, true)))
                                .then(Commands.argument("clan", StringArgumentType.word()).suggests(invitedClans())
                                        .executes(ctx -> {
                                            answer(self(ctx), str(ctx, "clan"), true);
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(sub("ablehnen").executes(player(p -> answer(p, null, false)))
                                .then(Commands.argument("clan", StringArgumentType.word()).suggests(invitedClans())
                                        .executes(ctx -> {
                                            answer(self(ctx), str(ctx, "clan"), false);
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(sub("verlassen").executes(player(this::leave)))
                        .then(sub("kicken").then(Commands.argument("spieler", StringArgumentType.word()).suggests(ownMembers())
                                .executes(ctx -> {
                                    kick(self(ctx), str(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(sub("leiter").then(Commands.argument("spieler", StringArgumentType.word()).suggests(ownMembers())
                                .executes(ctx -> {
                                    transferLeader(self(ctx), str(ctx, "spieler"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(sub("aufloesen")
                                .executes(player(p -> p.sendMessage(msg("disband-confirm", p))))
                                .then(Commands.literal("bestaetigen").executes(player(this::disband))))
                        .then(Commands.literal("info")
                                .executes(ctx -> {
                                    info(ctx.getSource().getSender(), null);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .then(Commands.argument("clan", StringArgumentType.word()).suggests(clanNames())
                                        .executes(ctx -> {
                                            info(ctx.getSource().getSender(), str(ctx, "clan"));
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(Commands.literal("liste").executes(ctx -> {
                            list(ctx.getSource().getSender());
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(sub("home").executes(player(this::teleportHome))
                                .then(Commands.literal("vorschlagen").executes(player(this::proposeHome))))
                        .then(sub("abstimmen")
                                .then(Commands.literal("ja").executes(player(p -> castVote(p, true))))
                                .then(Commands.literal("nein").executes(player(p -> castVote(p, false)))))
                        .then(sub("truhe").executes(player(this::openRemote))
                                .then(Commands.literal("kaufen").executes(player(this::buyChest))))
                        .then(sub("friendlyfire").executes(player(p -> friendlyFire(p, null)))
                                .then(Commands.literal("an").executes(player(p -> friendlyFire(p, true))))
                                .then(Commands.literal("aus").executes(player(p -> friendlyFire(p, false)))))
                        .then(sub("bank").executes(player(this::bankInfo)))
                        .then(sub("einzahlen").then(Commands.argument("betrag", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    bankDeposit(self(ctx), LongArgumentType.getLong(ctx, "betrag"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(sub("auszahlen").then(Commands.argument("betrag", LongArgumentType.longArg(1))
                                .executes(ctx -> {
                                    bankWithdraw(self(ctx), LongArgumentType.getLong(ctx, "betrag"));
                                    return Command.SINGLE_SUCCESS;
                                })))
                        .then(Commands.literal("admin")
                                .requires(src -> src.getSender().hasPermission(PERM_ADMIN))
                                .then(Commands.literal("aufloesen").then(Commands.argument("clan", StringArgumentType.word()).suggests(clanNames())
                                        .executes(ctx -> {
                                            CommandSender sender = ctx.getSource().getSender();
                                            Clan clan = clans.byName(str(ctx, "clan"));
                                            if (clan == null) {
                                                sender.sendMessage(msg("unknown-clan", sender instanceof Player p ? p : null,
                                                        TextService.placeholder("name", str(ctx, "clan"))));
                                            } else {
                                                adminDisband(sender, clan);
                                            }
                                            return Command.SINGLE_SUCCESS;
                                        }))))
                        .build(),
                "Clans: gründen, einladen, Home, Truhe, Clan-Konto",
                List.of());
    }
}
