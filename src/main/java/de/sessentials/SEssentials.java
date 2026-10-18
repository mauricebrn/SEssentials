package de.sessentials;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.sessentials.admin.AdminPanel;
import de.sessentials.alias.CommandAliases;
import de.sessentials.bank.BankCommands;
import de.sessentials.bank.BankExchange;
import de.sessentials.bank.BankManager;
import de.sessentials.bank.BankMenu;
import de.sessentials.bank.VaultEconomy;
import de.sessentials.bounty.BountyManager;
import de.sessentials.bounty.BountyMenu;
import de.sessentials.clan.ClanCommands;
import de.sessentials.clan.ClanManager;
import de.sessentials.clan.ClanMenu;
import de.sessentials.admin.AdminPanel.Category;
import de.sessentials.admin.ChatPrompt;
import de.sessentials.admin.Menu;
import de.sessentials.chat.ChatListener;
import de.sessentials.chestshop.ChestShopManager;
import de.sessentials.chestshop.ShopCreatorMenu;
import de.sessentials.creativelog.CreativeLog;
import de.sessentials.creativelog.CreativeLogMenu;
import de.sessentials.detector.ClientDetector;
import de.sessentials.fairplay.FairplayListener;
import de.sessentials.fairplay.FairplayManager;
import de.sessentials.fairplay.FairplayMenu;
import de.sessentials.dimension.DimensionCommands;
import de.sessentials.durability.DurabilityAlert;
import de.sessentials.fakeplayer.FakePlayerManager;
import de.sessentials.gamble.LuckyWheel;
import de.sessentials.help.HelpCommand;
import de.sessentials.hook.LuckPermsHook;
import de.sessentials.itemguard.ItemGuard;
import de.sessentials.itemguard.ItemGuardMenu;
import de.sessentials.joinquit.JoinQuitListener;
import de.sessentials.msg.MailManager;
import de.sessentials.msg.MessageCommands;
import de.sessentials.news.NewsManager;
import de.sessentials.notify.Notifications;
import de.sessentials.player.EnderChestCommand;
import de.sessentials.player.KnownPlayers;
import de.sessentials.player.PersonalEnvironment;
import de.sessentials.player.PlayerCommands;
import de.sessentials.player.SudoCommand;
import de.sessentials.punish.PunishCommands;
import de.sessentials.punish.PunishmentManager;
import de.sessentials.punish.PunishmentMenu;
import de.sessentials.report.ReportManager;
import de.sessentials.hook.PermissionInfo;
import de.sessentials.softrestart.SoftRestartManager;
import de.sessentials.spawn.SpawnManager;
import de.sessentials.tab.TabNametagManager;
import de.sessentials.teleport.TeleportWarmup;
import de.sessentials.tpa.TpaManager;
import de.sessentials.tracker.ItemTracker;
import de.sessentials.update.AutoUpdater;
import de.sessentials.upgrader.ItemUpgrader;
import de.sessentials.tracker.TrackerMenu;
import de.sessentials.trust.SuspicionVote;
import de.sessentials.trust.TrustManager;
import de.sessentials.trust.TrustMenu;
import de.sessentials.text.TextService;
import de.sessentials.vanish.VanishManager;
import de.sessentials.web.WebPanel;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class SEssentials extends JavaPlugin {

    private ConfigFiles configFiles;
    private Modules modules;
    private KnownPlayers knownPlayers;
    private TextService text;
    private PermissionInfo permissions;
    private SoftRestartManager softRestart;
    private ChatListener chat;
    private JoinQuitListener joinQuit;
    private TabNametagManager tab;
    private VanishManager vanish;
    private PlayerCommands playerCommands;
    private SudoCommand sudo;
    private CommandAliases aliases;
    private AdminPanel adminPanel;
    private ReportManager reports;
    private Notifications notifications;
    private ClientDetector detector;
    private DimensionCommands dimensions;
    private SpawnManager spawn;
    private HelpCommand help;
    private TpaManager tpa;
    private TeleportWarmup teleportWarmup;
    private ChatPrompt chatPrompt;
    private BankManager bank;
    private BankExchange bankExchange;
    private BankMenu bankMenu;
    private BankCommands bankCommands;
    private ItemTracker tracker;
    private EnderChestCommand enderChest;
    private LuckyWheel luckyWheel;
    private AutoUpdater autoUpdater;
    private FakePlayerManager fakePlayers;
    private ItemUpgrader upgrader;
    private ChestShopManager chestShops;
    private BountyManager bounties;
    private BountyMenu bountyMenu;
    private ClanManager clans;
    private ClanCommands clanCommands;
    private ClanMenu clanMenu;
    private ShopCreatorMenu shopCreator;
    private ItemGuard itemGuard;
    private ItemGuardMenu itemGuardMenu;
    private CreativeLog creativeLog;
    private CreativeLogMenu creativeLogMenu;
    private PunishmentManager punishments;
    private PunishCommands punishCommands;
    private FairplayManager fairplay;
    private FairplayMenu fairplayMenu;
    private PunishmentMenu punishmentMenu;
    private WebPanel webPanel;
    private MessageCommands messages;
    private MailManager mail;
    private TrackerMenu trackerMenu;
    private NewsManager news;
    private TrustManager trust;
    private DurabilityAlert durability;
    private PersonalEnvironment personalEnvironment;
    private TrustMenu trustMenu;
    private SuspicionVote suspicion;

    @Override
    public void onEnable() {
        configFiles = new ConfigFiles(this);
        configFiles.load();
        modules = new Modules(getConfig(), getLogger());

        PermissionInfo perms = PermissionInfo.NONE;
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            perms = new LuckPermsHook(getLogger());
            getLogger().info("LuckPerms gefunden – Prefix/Suffix/Gruppen aktiv.");
        } else {
            getLogger().warning("LuckPerms nicht gefunden – <lp_*>-Platzhalter bleiben leer.");
        }
        boolean papi = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (papi) getLogger().info("PlaceholderAPI gefunden – %platzhalter% und <papi:...> aktiv.");

        vanish = new VanishManager(this);
        permissions = perms;
        text = new TextService(perms, papi, vanish::visibleCount);
        text.setPrefix(getConfig().getString("prefix", ""));

        teleportWarmup = new TeleportWarmup(this);
        knownPlayers = new KnownPlayers();

        // Bank vor allem, was Kosten hat (/spawn, /tpa)
        bank = new BankManager(this);
        bankExchange = BankExchange.fromConfig(getConfig(), getLogger());
        bankMenu = new BankMenu(this, bank);
        bankCommands = new BankCommands(this, bank, bankMenu);
        if (modules.on("bank") && getServer().getPluginManager().isPluginEnabled("Vault")) {
            VaultEconomy.register(this, bank);
            getLogger().info("Vault gefunden – Bank als Economy angemeldet.");
        }

        // Module, von denen sonst nichts abhängt, gibt es nur, wenn sie an sind (null = aus).
        // Gemeinsam genutzte Daten (Bank, Strafen, Vanish, Reports, News) gibt es immer – bei ihnen
        // schalten die Module nur Befehle, Listener und Kacheln.
        tracker = modules.on("item-tracker") ? new ItemTracker(this) : null;
        trackerMenu = tracker != null ? new TrackerMenu(this, tracker) : null;

        softRestart = new SoftRestartManager(this);
        autoUpdater = modules.on("auto-update") ? new AutoUpdater(this) : null;
        chat = new ChatListener(this);
        joinQuit = new JoinQuitListener(this);
        tab = modules.on("tab") ? new TabNametagManager(this) : null;
        playerCommands = new PlayerCommands(this);
        sudo = new SudoCommand(this);
        aliases = new CommandAliases(this);
        dimensions = new DimensionCommands(this);
        spawn = new SpawnManager(this);
        help = new HelpCommand(this);
        tpa = new TpaManager(this);
        enderChest = new EnderChestCommand(this);
        luckyWheel = modules.on("gambling") ? new LuckyWheel(this) : null;
        fakePlayers = modules.on("fake-players") ? new FakePlayerManager(this) : null;
        upgrader = modules.on("upgrader") ? new ItemUpgrader(this) : null;
        chestShops = modules.on("chestshops") ? new ChestShopManager(this) : null;
        shopCreator = chestShops != null ? new ShopCreatorMenu(this, chestShops) : null;
        bounties = modules.on("bounty") ? new BountyManager(this) : null;
        bountyMenu = bounties != null ? new BountyMenu(this, bounties) : null;
        clans = modules.on("clans") ? new ClanManager(this) : null;
        clanCommands = clans != null ? new ClanCommands(this, clans) : null;
        if (clanCommands != null) text.setClanTag(clanCommands::tag);
        clanMenu = clanCommands != null ? new ClanMenu(this, clans, clanCommands) : null;
        messages = new MessageCommands(this);
        mail = modules.on("mail") ? new MailManager(this) : null;
        chatPrompt = new ChatPrompt(this);
        news = new NewsManager(this);
        notifications = new Notifications(this);
        reports = new ReportManager(this);
        detector = modules.on("client-detector") ? new ClientDetector(this) : null;
        itemGuard = modules.on("itemguard") ? new ItemGuard(this) : null;
        itemGuardMenu = itemGuard != null ? new ItemGuardMenu(this, itemGuard) : null;
        creativeLog = modules.on("creativelog") ? new CreativeLog(this) : null;
        creativeLogMenu = creativeLog != null ? new CreativeLogMenu(this, creativeLog) : null;
        punishments = new PunishmentManager(this);
        punishCommands = new PunishCommands(this, punishments);
        punishmentMenu = new PunishmentMenu(this, punishments, punishCommands);
        fairplay = modules.on("fairplay") ? new FairplayManager(this) : null;
        fairplayMenu = fairplay != null ? new FairplayMenu(this, fairplay) : null;
        trust = modules.on("trust") ? new TrustManager(this) : null;
        durability = modules.on("durability") ? new DurabilityAlert(this) : null;
        personalEnvironment = modules.on("ptime") ? new PersonalEnvironment(this) : null;
        suspicion = trust != null ? new SuspicionVote(this, trust) : null;
        trustMenu = trust != null ? new TrustMenu(this, trust, suspicion) : null;
        adminPanel = new AdminPanel(this);
        registerAdminApps();

        getServer().getPluginManager().registerEvents(new Menu.ClickListener(), this);
        getServer().getPluginManager().registerEvents(chatPrompt, this);
        getServer().getPluginManager().registerEvents(teleportWarmup, this);
        getServer().getPluginManager().registerEvents(knownPlayers, this);
        listen("softrestart", softRestart);
        listen("chat", chat);
        listen("join-quit", joinQuit);
        listen("vanish", vanish);
        listen("player-commands", playerCommands);
        listen("reports", reports);
        listen("client-detector", detector);
        listen("itemguard", itemGuard);
        listen("creativelog", creativeLog);
        listen("trust", trust);
        listen("durability", durability);
        listen("ptime", personalEnvironment);
        listen("punishments", punishments);
        if (fairplay != null) listen("fairplay", new FairplayListener(this, fairplay));
        listen("spawn", spawn);
        listen("tpa", tpa);
        listen("news", news);
        listen("bank", bank);
        listen("item-tracker", tracker);
        listen("chestshops", chestShops);
        listen("bounty", bounties);
        listen("clans", clanCommands);
        if (clanCommands != null) listen("clans", clanCommands.chestListener());
        if (clanCommands != null) listen("clans", clanCommands.friendlyFireListener());
        listen("messages", messages);
        listen("mail", mail);
        listen("tab", tab);
        listen("fake-players", fakePlayers);
        listen("upgrader", upgrader);
        if (luckyWheel != null) listen("gambling", luckyWheel.odds());
        if (tab != null) tab.reload();

        registerCommands();

        if (modules.on("webpanel")) {
            webPanel = new WebPanel(this);
            webPanel.start();
        }
    }

    /** Meldet den Listener nur an, wenn sein Modul an ist (und es ihn gibt). */
    private void listen(String module, Listener listener) {
        if (listener != null && modules.on(module)) getServer().getPluginManager().registerEvents(listener, this);
    }

    @Override
    public void onDisable() {
        if (webPanel != null) webPanel.stop();
        if (autoUpdater != null) autoUpdater.shutdown();
        if (fakePlayers != null) fakePlayers.shutdown();
        if (upgrader != null) upgrader.shutdown();
        if (mail != null) mail.shutdown();
        if (softRestart != null) softRestart.shutdown();
        if (tab != null) tab.shutdown();
        if (vanish != null) vanish.shutdown();
        if (tracker != null) tracker.shutdown();
        if (creativeLog != null) creativeLog.shutdown();
        if (fairplay != null) fairplay.shutdown();
        if (punishments != null) punishments.shutdown();
        if (chestShops != null) chestShops.shutdown();
        if (bounties != null) bounties.shutdown();
        if (clans != null) clans.shutdown();
        if (luckyWheel != null) luckyWheel.shutdown();
        if (bank != null) bank.shutdown();
    }

    /** Die aus {@code config/*.yml} zusammengeführte Config, Standardwerte aus der config.yml im JAR. */
    @Override
    public FileConfiguration getConfig() {
        return configFiles.config();
    }

    @Override
    public void reloadConfig() {
        configFiles.load();
    }

    /** Schreibt Änderungen an {@link #getConfig()} zurück in die passenden Dateien unter {@code config/}. */
    @Override
    public void saveConfig() {
        configFiles.save();
    }

    @Override
    public void saveDefaultConfig() {
        configFiles.load();
    }

    public void reloadAll() {
        reloadConfig();
        text.setPrefix(getConfig().getString("prefix", ""));
        chat.reload();
        joinQuit.reload();
        vanish.reload();
        if (detector != null) detector.reload();
        if (itemGuard != null) itemGuard.reload();
        if (creativeLog != null) creativeLog.reload();
        if (trust != null) trust.reload();
        if (durability != null) durability.reload();
        if (suspicion != null) suspicion.reload();
        bankExchange = BankExchange.fromConfig(getConfig(), getLogger());
        if (tracker != null) tracker.reload();
        spawn.reload();
        help.reload();
        tpa.reload();
        enderChest.reload();
        if (luckyWheel != null) luckyWheel.reload();
        if (bounties != null) bounties.reload();
        if (clanCommands != null) clanCommands.reload();
        if (autoUpdater != null) autoUpdater.reload();
        if (fakePlayers != null) fakePlayers.reload();
        if (upgrader != null) upgrader.reload();
        if (tab != null) tab.reload();
        // Webpanel neu starten, damit Port/Adresse greifen; Sitzungen bleiben erhalten
        if (webPanel != null) {
            webPanel.stop();
            webPanel.start();
        }
    }

    public Modules modules() {
        return modules;
    }

    /** Rechte-Plugin (LuckPerms) oder {@link PermissionInfo#NONE}. */
    public PermissionInfo permissions() {
        return permissions;
    }

    public TextService text() {
        return text;
    }

    public JoinQuitListener joinQuit() {
        return joinQuit;
    }

    public AdminPanel adminPanel() {
        return adminPanel;
    }

    public ReportManager reports() {
        return reports;
    }

    public Notifications notifications() {
        return notifications;
    }

    public VanishManager vanish() {
        return vanish;
    }

    public SoftRestartManager softRestart() {
        return softRestart;
    }

    /** {@code null}, wenn das Modul aus ist. */
    public FairplayManager fairplay() {
        return fairplay;
    }

    /** Strafen vergeben, auch automatisch (Fairplay). */
    public PunishCommands punishCommands() {
        return punishCommands;
    }

    public PunishmentManager punishments() {
        return punishments;
    }

    /** {@code null}, wenn das Modul aus ist. */
    public ItemGuard itemGuard() {
        return itemGuard;
    }

    /** {@code null}, wenn das Modul aus ist. */
    public ChestShopManager chestShops() {
        return chestShops;
    }

    /** {@code null}, wenn das Modul aus ist. */
    public ItemTracker tracker() {
        return tracker;
    }

    public NewsManager news() {
        return news;
    }

    /** {@code null}, wenn das Modul aus ist. */
    public CreativeLog creativeLog() {
        return creativeLog;
    }

    public ChatPrompt chatPrompt() {
        return chatPrompt;
    }

    /** Alle Spieler, die schon einmal da waren – Tab-Vervollständigung für Befehle, die auch offline Spieler annehmen. */
    public KnownPlayers knownPlayers() {
        return knownPlayers;
    }

    /** Wartezeit vor Spieler-Teleports (/spawn, /tpa, /nether, /end, /clan home). */
    public TeleportWarmup teleportWarmup() {
        return teleportWarmup;
    }

    public BankManager bank() {
        return bank;
    }

    /**
     * Datei oder Ordner für gespeicherte Daten unter {@code data/}, damit im Plugin-Ordner nur die Config liegt.
     * Liegt sie noch am alten Ort direkt im Plugin-Ordner, wird sie beim ersten Zugriff verschoben;
     * schlägt das fehl, wird der alte Ort weiter genutzt, damit keine Daten verloren gehen.
     */
    public File dataFile(String path) {
        File target = new File(getDataFolder(), "data/" + path);
        File legacy = new File(getDataFolder(), path);
        if (target.exists() || !legacy.exists()) return target;
        try {
            Files.createDirectories(target.getParentFile().toPath());
            Files.move(legacy.toPath(), target.toPath());
            getLogger().info(path + " nach data/" + path + " verschoben.");
            return target;
        } catch (IOException e) {
            getLogger().log(Level.WARNING, path + " konnte nicht nach data/ verschoben werden – nutze den alten Ort.", e);
            return legacy;
        }
    }

    /** Das geladene Plugin-Jar (für den Auto-Updater). */
    public File jarFile() {
        return getFile();
    }

    /** {@code null}, wenn das Modul aus ist. */
    public FakePlayerManager fakePlayers() {
        return fakePlayers;
    }

    /** {@code null}, wenn das Modul aus ist. */
    public LuckyWheel luckyWheel() {
        return luckyWheel;
    }

    public BankExchange bankExchange() {
        return bankExchange;
    }

    /** Kacheln im Admin-Panel. Reihenfolge innerhalb einer Kategorie = Position in der Zeile. */
    private void registerAdminApps() {
        // Moderation
        if (modules.on("punishments")) {
            adminPanel.addApp(Category.MODERATION, PunishmentMenu.PERM,
                    p -> Menu.icon(Material.IRON_BARS, Component.text("Strafen", NamedTextColor.DARK_RED),
                            Component.text("Aktiv: " + punishments.active().size(), NamedTextColor.GRAY),
                            Component.text("Bans, Mutes, IP-Bans", NamedTextColor.GRAY)),
                    punishmentMenu::open);
        }
        if (fairplayMenu != null) {
            adminPanel.addApp(Category.MODERATION, FairplayMenu.PERM,
                    p -> Menu.icon(Material.GOLDEN_HELMET, Component.text("Fairplay", NamedTextColor.GOLD),
                            Component.text("Spieler mit Strikes: " + fairplay.standings().stream().filter(s -> s.strikes() > 0).count(), NamedTextColor.GRAY),
                            Component.text("Rangliste, Verlauf, Strikes", NamedTextColor.GRAY)),
                    fairplayMenu::openList);
        }
        if (modules.on("vanish")) {
            adminPanel.addApp(Category.MODERATION, "sess.vanish",
                    p -> Menu.icon(Material.ENDER_EYE, Component.text("Vanish", NamedTextColor.LIGHT_PURPLE), status(vanish.isVanished(p))),
                    p -> vanish.toggle(p, true));
        }
        if (modules.on("reports")) {
            adminPanel.addApp(Category.MODERATION, "sess.reports.admin",
                    p -> Menu.icon(Material.WRITABLE_BOOK, Component.text("Reports", NamedTextColor.RED),
                            Component.text("Offen: " + reports.openCount(), NamedTextColor.GRAY)),
                    p -> reports.menu().openList(p, 1));
        }
        if (modules.on("itemguard")) {
            adminPanel.addApp(Category.MODERATION, ItemGuardMenu.PERM,
                    p -> Menu.icon(Material.SHIELD, Component.text("ItemGuard", NamedTextColor.RED),
                            status(itemGuard.enabled()),
                            Component.text("Verbotene Items: " + itemGuard.banned().size(), NamedTextColor.GRAY),
                            Component.text("Entfernt seit Start: " + itemGuard.removedSinceStart(), NamedTextColor.GRAY)),
                    itemGuardMenu::open);
        }
        if (modules.on("creativelog")) {
            adminPanel.addApp(Category.MODERATION, CreativeLogMenu.PERM,
                    p -> Menu.icon(Material.GRASS_BLOCK, Component.text("CreativeLog", NamedTextColor.GREEN),
                            status(creativeLog.enabled()),
                            Component.text("Aktionen im Kreativmodus", NamedTextColor.GRAY)),
                    creativeLogMenu::open);
        }
        if (trustMenu != null) {
            adminPanel.addApp(Category.MODERATION, TrustManager.PERM_ADMIN,
                    p -> Menu.icon(Material.TOTEM_OF_UNDYING, Component.text("Trust", NamedTextColor.GREEN),
                            Component.text("Warten auf Vertrauen: " + trust.candidates().size(), NamedTextColor.GRAY),
                            Component.text("Verdachts-Abstimmungen: " + suspicion.open().size(), NamedTextColor.GRAY),
                            Component.text("Neue Spieler freischalten", NamedTextColor.GRAY)),
                    trustMenu::open);
        }

        // Spieler & Teleport
        adminPanel.addApp(Category.PLAYERS, p -> COMMAND_PERMS.stream().anyMatch(perm -> canUse(p, perm)),
                p -> Menu.icon(Material.COMMAND_BLOCK, Component.text("Befehle", NamedTextColor.AQUA),
                        Component.text("Fly, God, Heal, Feed, Enderchest", NamedTextColor.GRAY),
                        Component.text("Spawn, Nether, End", NamedTextColor.GRAY)),
                this::openCommandsMenu);

        if (clanMenu != null) {
            adminPanel.addApp(Category.PLAYERS, ClanMenu.PERM,
                    p -> Menu.icon(Material.WHITE_BANNER, Component.text("Clans", NamedTextColor.GOLD),
                            Component.text("Clans: " + clans.all().size(), NamedTextColor.GRAY),
                            Component.text("Home, Truhe, Konto, Mitglieder", NamedTextColor.GRAY)),
                    clanMenu::openList);
        }

        // Wirtschaft
        if (modules.on("bank")) {
            adminPanel.addApp(Category.ECONOMY, "sess.bank.admin",
                    p -> Menu.icon(Material.GOLD_BLOCK, Component.text("Bank", NamedTextColor.GOLD),
                            Component.text("Im Umlauf: " + bank.format(bank.total()), NamedTextColor.GRAY),
                            Component.text("Konten ansehen und ändern", NamedTextColor.GRAY)),
                    bankMenu::openAdmin);
        }
        if (bounties != null) {
            adminPanel.addApp(Category.ECONOMY, BountyManager.PERM_ADMIN,
                    p -> Menu.icon(Material.SKELETON_SKULL, Component.text("Kopfgelder", NamedTextColor.RED),
                            Component.text("Ziele: " + bounties.targets().size(), NamedTextColor.GRAY),
                            Component.text("Gebote ansehen und entfernen", NamedTextColor.GRAY)),
                    bountyMenu::openTargets);
        }
        if (modules.on("item-tracker")) {
            adminPanel.addApp(Category.ECONOMY, TrackerMenu.PERM,
                    p -> Menu.icon(Material.SPYGLASS, Component.text("Item-Tracker", NamedTextColor.AQUA),
                            Component.text("Wertvolle Items in Inventar und", NamedTextColor.GRAY),
                            Component.text("Enderchest, mit Verlauf", NamedTextColor.GRAY)),
                    p -> trackerMenu.openOverview(p, 1));
        }
        if (modules.on("upgrader")) {
            adminPanel.addApp(Category.ECONOMY, ItemUpgrader.PERM_ADMIN,
                    p -> Menu.icon(Material.SMITHING_TABLE, Component.text("Item-Upgrader", NamedTextColor.AQUA),
                            Component.text("Item-Werte und Sperrliste", NamedTextColor.GRAY)),
                    upgrader::openAdmin);
            adminPanel.addApp(Category.ECONOMY, ItemUpgrader.PERM_ADMIN,
                    p -> Menu.icon(Material.ANVIL, Component.text("Upgrader-Werte", NamedTextColor.AQUA),
                            Component.text("Einsatz- und Gewinn-Wert", NamedTextColor.GRAY),
                            Component.text("pro Item getrennt einstellen", NamedTextColor.GRAY)),
                    upgrader::openValues);
        }
        if (modules.on("gambling")) {
            adminPanel.addApp(Category.ECONOMY, LuckyWheel.PERM_ADMIN,
                    p -> Menu.icon(Material.CLOCK, Component.text("Glücksrad", NamedTextColor.LIGHT_PURPLE),
                            Component.text("Topf: " + bank.format(bank.pot()), NamedTextColor.GRAY),
                            Component.text("Topf setzen, Chancen ansehen", NamedTextColor.GRAY)),
                    luckyWheel::openAdmin);
        }

        // Server
        if (modules.on("news")) {
            adminPanel.addApp(Category.SERVER, "sess.news.admin",
                    p -> Menu.icon(Material.OAK_SIGN, Component.text("News", NamedTextColor.GOLD),
                            Component.text("News erstellen und verwalten", NamedTextColor.GRAY)),
                    p -> news.menu().openPanel(p));
        }
        if (modules.on("softrestart")) {
            adminPanel.addApp(Category.SERVER, "sess.softrestart",
                    p -> Menu.icon(Material.CLOCK, Component.text("Softrestart", NamedTextColor.RED),
                            softRestart.isRunning()
                                    ? text.render("<gray>Neustart in <white><time>", softRestart.time(softRestart.remainingSeconds()))
                                    : Component.text("Kein Neustart geplant", NamedTextColor.GRAY)),
                    this::openSoftRestartMenu);
        }
        if (modules.on("auto-update")) {
            adminPanel.addApp(Category.SERVER, AutoUpdater.PERM,
                    p -> Menu.icon(Material.HOPPER_MINECART, Component.text("Auto-Update", NamedTextColor.AQUA),
                            status(autoUpdater.enabled()),
                            Component.text(autoUpdater.repository() + " · täglich " + autoUpdater.time(), NamedTextColor.GRAY),
                            Component.text(autoUpdater.lastResult(), NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("Klick", NamedTextColor.YELLOW).append(Component.text(" » an/aus", NamedTextColor.GRAY)),
                            Component.text("/autoupdate check", NamedTextColor.YELLOW).append(Component.text(" » sofort prüfen", NamedTextColor.GRAY))),
                    p -> {
                        autoUpdater.setEnabled(p, !autoUpdater.enabled());
                        adminPanel.open(p);
                    });
        }
        adminPanel.addApp(Category.SERVER, "sess.reload",
                p -> Menu.icon(Material.COMPARATOR, Component.text("Config neu laden", NamedTextColor.GREEN)),
                p -> {
                    reloadAll();
                    p.sendMessage(text.render(getConfig().getString("messages.reloaded", "")));
                });

        // Einstellungen
        adminPanel.addApp(Category.SETTINGS, notifications::canConfigure,
                p -> Menu.icon(Material.BELL, Component.text("Benachrichtigungen", NamedTextColor.YELLOW),
                        Component.text("Reports & Client-Detector", NamedTextColor.GRAY)),
                notifications::openMenu);
    }

    private static final List<String> COMMAND_PERMS = List.of("sess.fly", "sess.god", "sess.heal", "sess.feed", "sess.enderchest", "sess.nether", "sess.end", "sess.spawn", "sess.setspawn");

    /** Modul zu einem Recht aus {@link #COMMAND_PERMS}. */
    private static String moduleOf(String perm) {
        return switch (perm) {
            case "sess.enderchest" -> "enderchest";
            case "sess.nether", "sess.end" -> "dimensions";
            case "sess.spawn", "sess.setspawn" -> "spawn";
            default -> "player-commands";
        };
    }

    /** Recht und Modul des Eintrags im Menü „Befehle“. */
    private boolean canUse(Player player, String perm) {
        return modules.on(moduleOf(perm)) && player.hasPermission(perm);
    }

    /** Untermenü „Befehle“: Spieler- und Teleport-Befehle, jede Kachel nur mit ihrem Recht. */
    private void openCommandsMenu(Player player) {
        List<Menu.Entry> self = new ArrayList<>();
        if (canUse(player, "sess.fly")) {
            self.add(new Menu.Entry(Menu.icon(Material.FEATHER, Component.text("Fly", NamedTextColor.AQUA), status(player.getAllowFlight())),
                    p -> playerCommands.execute("fly", p)));
        }
        if (canUse(player, "sess.god")) {
            self.add(new Menu.Entry(Menu.icon(Material.TOTEM_OF_UNDYING, Component.text("God", NamedTextColor.GOLD), status(playerCommands.isGod(player))),
                    p -> playerCommands.execute("god", p)));
        }
        if (canUse(player, "sess.heal")) {
            self.add(new Menu.Entry(Menu.icon(Material.GOLDEN_APPLE, Component.text("Heal", NamedTextColor.RED)),
                    p -> playerCommands.execute("heal", p)));
        }
        if (canUse(player, "sess.feed")) {
            self.add(new Menu.Entry(Menu.icon(Material.COOKED_BEEF, Component.text("Feed", NamedTextColor.YELLOW)),
                    p -> playerCommands.execute("feed", p)));
        }
        if (canUse(player, EnderChestCommand.PERM_USE)) {
            self.add(new Menu.Entry(Menu.icon(Material.ENDER_CHEST, Component.text("Enderchest", NamedTextColor.DARK_PURPLE)),
                    enderChest::open));
        }

        List<Menu.Entry> teleport = new ArrayList<>();
        if (canUse(player, "sess.spawn")) {
            teleport.add(new Menu.Entry(Menu.icon(Material.COMPASS, Component.text("Spawn", NamedTextColor.GREEN)), p -> {
                p.closeInventory();
                spawn.teleport(p);
            }));
        }
        if (canUse(player, "sess.setspawn")) {
            teleport.add(new Menu.Entry(Menu.icon(Material.RED_BED, Component.text("Spawn setzen", NamedTextColor.GOLD),
                    Component.text("Setzt den Spawn auf deine Position", NamedTextColor.GRAY)), spawn::setSpawn));
        }
        if (canUse(player, "sess.nether")) {
            teleport.add(new Menu.Entry(Menu.icon(Material.NETHERRACK, Component.text("Nether", NamedTextColor.RED),
                    Component.text("Im Nether: zurück in die Oberwelt", NamedTextColor.GRAY)), p -> {
                p.closeInventory();
                dimensions.toggle(p, World.Environment.NETHER);
            }));
        }
        if (canUse(player, "sess.end")) {
            teleport.add(new Menu.Entry(Menu.icon(Material.END_STONE, Component.text("End", NamedTextColor.LIGHT_PURPLE),
                    Component.text("Im End: zurück in die Oberwelt", NamedTextColor.GRAY)), p -> {
                p.closeInventory();
                dimensions.toggle(p, World.Environment.THE_END);
            }));
        }

        Menu menu = Menu.sectioned(Component.text("Befehle"), List.of(
                        new Menu.Section(Menu.icon(Material.LIME_STAINED_GLASS_PANE, Component.text("» Spieler", NamedTextColor.GREEN, TextDecoration.BOLD)), self),
                        new Menu.Section(Menu.icon(Material.LIGHT_BLUE_STAINED_GLASS_PANE, Component.text("» Teleport", NamedTextColor.AQUA, TextDecoration.BOLD)), teleport)),
                new Menu.Entry(Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), adminPanel::open));
        menu.refreshWith(this::openCommandsMenu);
        menu.open(player);
    }

    private static Component status(boolean on) {
        return on ? Component.text("Status: An", NamedTextColor.GREEN) : Component.text("Status: Aus", NamedTextColor.GRAY);
    }

    private void openSoftRestartMenu(Player player) {
        Menu menu = new Menu(1, Component.text("Softrestart"));
        int[] minutes = {1, 5, 10, 15, 30, 60};
        for (int i = 0; i < minutes.length; i++) {
            int m = minutes[i];
            menu.set(i, Menu.icon(Material.CLOCK, Component.text("In " + m + " Minute(n)", NamedTextColor.YELLOW)), p -> {
                p.closeInventory();
                softRestart.start(p, m);
            });
        }
        menu.set(7, Menu.icon(Material.BARRIER, Component.text("Abbrechen", NamedTextColor.RED)), p -> {
            p.closeInventory();
            if (!softRestart.cancel()) p.sendMessage(text.render(getConfig().getString("softrestart.messages.not-running", "")));
        });
        menu.set(8, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), adminPanel::open);
        menu.fillEmpty();
        menu.open(player);
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();

            if (modules.on("softrestart")) commands.register(
                    Commands.literal("softrestart")
                            .requires(src -> src.getSender().hasPermission("sess.softrestart"))
                            .then(Commands.argument("minuten", IntegerArgumentType.integer(1, 1440))
                                    .executes(ctx -> {
                                        int minutes = IntegerArgumentType.getInteger(ctx, "minuten");
                                        softRestart.start(ctx.getSource().getSender(), minutes);
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("cancel")
                                    .executes(ctx -> {
                                        if (!softRestart.cancel()) {
                                            ctx.getSource().getSender().sendMessage(text.render(
                                                    getConfig().getString("softrestart.messages.not-running", "")));
                                        }
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("status")
                                    .executes(ctx -> {
                                        String key = softRestart.isRunning() ? "status" : "not-running";
                                        ctx.getSource().getSender().sendMessage(text.render(
                                                getConfig().getString("softrestart.messages." + key, ""),
                                                softRestart.time(softRestart.remainingSeconds())));
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .build(),
                    "Startet den Server nach X Minuten neu",
                    List.of("srestart"));

            commands.register(
                    Commands.literal("sessentials")
                            .requires(src -> src.getSender().hasPermission("sess.reload"))
                            .then(Commands.literal("reload")
                                    .executes(ctx -> {
                                        reloadAll();
                                        ctx.getSource().getSender().sendMessage(text.render(
                                                getConfig().getString("messages.reloaded", "")));
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .build(),
                    "SEssentials-Verwaltung",
                    List.of("sess"));

            if (modules.on("vanish")) commands.register(
                    Commands.literal("vanish")
                            .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission("sess.vanish"))
                            .executes(ctx -> {
                                vanish.toggle((Player) ctx.getSource().getExecutor(), true);
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.literal("silent")
                                    .executes(ctx -> {
                                        vanish.toggle((Player) ctx.getSource().getExecutor(), false);
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .then(Commands.literal("bypass")
                                    .requires(src -> src.getSender().hasPermission("sess.vanish.bypass"))
                                    .executes(ctx -> {
                                        vanish.toggleBypass((Player) ctx.getSource().getExecutor());
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .build(),
                    "Macht dich unsichtbar / wieder sichtbar",
                    List.of("v"));

            if (modules.on("player-commands")) playerCommands.register(commands);
            if (modules.on("sudo")) sudo.register(commands);
            if (modules.on("command-aliases")) aliases.register(commands);
            if (modules.on("reports")) reports.register(commands);
            if (modules.on("dimensions")) dimensions.register(commands);
            if (modules.on("spawn")) spawn.register(commands);
            if (modules.on("help")) help.register(commands);
            if (modules.on("tpa")) tpa.register(commands);
            if (modules.on("enderchest")) enderChest.register(commands);
            if (modules.on("messages")) messages.register(commands);
            if (modules.on("spy")) notifications.register(commands);
            if (modules.on("punishments")) punishCommands.register(commands);
            if (fairplay != null) fairplay.register(commands);
            if (modules.on("news")) news.register(commands);
            if (modules.on("bank")) bankCommands.register(commands);
            // Module, die es nur gibt, wenn sie an sind
            if (luckyWheel != null) luckyWheel.register(commands);
            if (autoUpdater != null) autoUpdater.register(commands);
            if (fakePlayers != null) fakePlayers.register(commands);
            if (upgrader != null) upgrader.register(commands);
            if (mail != null) mail.register(commands);
            if (shopCreator != null) shopCreator.register(commands);
            if (bounties != null) bounties.register(commands);
            if (clanCommands != null) clanCommands.register(commands);
            if (itemGuard != null) itemGuard.register(commands);
            if (creativeLog != null) creativeLog.register(commands);
            if (trust != null) trust.register(commands);
            if (durability != null) durability.register(commands);
            if (personalEnvironment != null) personalEnvironment.register(commands);
            if (suspicion != null) suspicion.register(commands);
            if (webPanel != null) webPanel.register(commands);

            if (modules.on("admin-panel")) commands.register(
                    Commands.literal("admin")
                            .requires(src -> src.getExecutor() instanceof Player p && p.hasPermission("sess.admin"))
                            .executes(ctx -> {
                                adminPanel.open((Player) ctx.getSource().getExecutor());
                                return Command.SINGLE_SUCCESS;
                            })
                            .build(),
                    "Öffnet das Admin-Panel",
                    List.of());
        });
    }
}
