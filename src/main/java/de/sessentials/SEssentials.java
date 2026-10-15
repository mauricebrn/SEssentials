package de.sessentials;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.sessentials.admin.AdminPanel;
import de.sessentials.bank.BankCommands;
import de.sessentials.bank.BankExchange;
import de.sessentials.bank.BankManager;
import de.sessentials.bank.BankMenu;
import de.sessentials.bank.VaultEconomy;
import de.sessentials.admin.AdminPanel.Category;
import de.sessentials.admin.ChatPrompt;
import de.sessentials.admin.Menu;
import de.sessentials.chat.ChatListener;
import de.sessentials.chestshop.ChestShopManager;
import de.sessentials.chestshop.ShopCreatorMenu;
import de.sessentials.creativelog.CreativeLog;
import de.sessentials.creativelog.CreativeLogMenu;
import de.sessentials.detector.ClientDetector;
import de.sessentials.dimension.DimensionCommands;
import de.sessentials.fakeplayer.FakePlayerManager;
import de.sessentials.gamble.LuckyWheel;
import de.sessentials.help.HelpCommand;
import de.sessentials.hook.LuckPermsHook;
import de.sessentials.itemguard.ItemGuard;
import de.sessentials.itemguard.ItemGuardMenu;
import de.sessentials.joinquit.JoinQuitListener;
import de.sessentials.msg.MessageCommands;
import de.sessentials.news.NewsManager;
import de.sessentials.notify.Notifications;
import de.sessentials.player.EnderChestCommand;
import de.sessentials.player.PlayerCommands;
import de.sessentials.punish.PunishCommands;
import de.sessentials.punish.PunishmentManager;
import de.sessentials.punish.PunishmentMenu;
import de.sessentials.report.ReportManager;
import de.sessentials.hook.PermissionInfo;
import de.sessentials.softrestart.SoftRestartManager;
import de.sessentials.spawn.SpawnManager;
import de.sessentials.tab.TabNametagManager;
import de.sessentials.tpa.TpaManager;
import de.sessentials.tracker.ItemTracker;
import de.sessentials.update.AutoUpdater;
import de.sessentials.tracker.TrackerMenu;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class SEssentials extends JavaPlugin {

    private TextService text;
    private SoftRestartManager softRestart;
    private ChatListener chat;
    private JoinQuitListener joinQuit;
    private TabNametagManager tab;
    private VanishManager vanish;
    private PlayerCommands playerCommands;
    private AdminPanel adminPanel;
    private ReportManager reports;
    private Notifications notifications;
    private ClientDetector detector;
    private DimensionCommands dimensions;
    private SpawnManager spawn;
    private HelpCommand help;
    private TpaManager tpa;
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
    private ChestShopManager chestShops;
    private ShopCreatorMenu shopCreator;
    private ItemGuard itemGuard;
    private ItemGuardMenu itemGuardMenu;
    private CreativeLog creativeLog;
    private CreativeLogMenu creativeLogMenu;
    private PunishmentManager punishments;
    private PunishCommands punishCommands;
    private PunishmentMenu punishmentMenu;
    private WebPanel webPanel;
    private MessageCommands messages;
    private TrackerMenu trackerMenu;
    private NewsManager news;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addNewConfigOptions();

        PermissionInfo perms = PermissionInfo.NONE;
        if (getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            perms = new LuckPermsHook();
            getLogger().info("LuckPerms gefunden – Prefix/Suffix/Gruppen aktiv.");
        } else {
            getLogger().warning("LuckPerms nicht gefunden – <lp_*>-Platzhalter bleiben leer.");
        }
        boolean papi = getServer().getPluginManager().isPluginEnabled("PlaceholderAPI");
        if (papi) getLogger().info("PlaceholderAPI gefunden – %platzhalter% und <papi:...> aktiv.");

        vanish = new VanishManager(this);
        text = new TextService(perms, papi, vanish::visibleCount);
        text.setPrefix(getConfig().getString("prefix", ""));

        // Bank vor allem, was Kosten hat (/spawn, /tpa)
        bank = new BankManager(this);
        bankExchange = BankExchange.fromConfig(getConfig(), getLogger());
        bankMenu = new BankMenu(this, bank);
        bankCommands = new BankCommands(this, bank, bankMenu);
        if (getServer().getPluginManager().isPluginEnabled("Vault")) {
            VaultEconomy.register(this, bank);
            getLogger().info("Vault gefunden – Bank als Economy angemeldet.");
        }

        tracker = new ItemTracker(this);
        trackerMenu = new TrackerMenu(this, tracker);

        softRestart = new SoftRestartManager(this);
        autoUpdater = new AutoUpdater(this);
        chat = new ChatListener(this);
        joinQuit = new JoinQuitListener(this);
        tab = new TabNametagManager(this);
        playerCommands = new PlayerCommands(this);
        dimensions = new DimensionCommands(this);
        spawn = new SpawnManager(this);
        help = new HelpCommand(this);
        tpa = new TpaManager(this);
        enderChest = new EnderChestCommand(this);
        luckyWheel = new LuckyWheel(this);
        fakePlayers = new FakePlayerManager(this);
        chestShops = new ChestShopManager(this);
        shopCreator = new ShopCreatorMenu(this, chestShops);
        messages = new MessageCommands(this);
        chatPrompt = new ChatPrompt(this);
        news = new NewsManager(this);
        notifications = new Notifications(this);
        reports = new ReportManager(this);
        detector = new ClientDetector(this);
        itemGuard = new ItemGuard(this);
        itemGuardMenu = new ItemGuardMenu(this, itemGuard);
        creativeLog = new CreativeLog(this);
        creativeLogMenu = new CreativeLogMenu(this, creativeLog);
        punishments = new PunishmentManager(this);
        punishCommands = new PunishCommands(this, punishments);
        punishmentMenu = new PunishmentMenu(this, punishments, punishCommands);
        adminPanel = new AdminPanel(this);
        registerAdminApps();

        getServer().getPluginManager().registerEvents(softRestart, this);
        getServer().getPluginManager().registerEvents(chat, this);
        getServer().getPluginManager().registerEvents(joinQuit, this);
        getServer().getPluginManager().registerEvents(vanish, this);
        getServer().getPluginManager().registerEvents(playerCommands, this);
        getServer().getPluginManager().registerEvents(new Menu.ClickListener(), this);
        getServer().getPluginManager().registerEvents(reports, this);
        getServer().getPluginManager().registerEvents(detector, this);
        getServer().getPluginManager().registerEvents(itemGuard, this);
        getServer().getPluginManager().registerEvents(creativeLog, this);
        getServer().getPluginManager().registerEvents(punishments, this);
        getServer().getPluginManager().registerEvents(spawn, this);
        getServer().getPluginManager().registerEvents(tpa, this);
        getServer().getPluginManager().registerEvents(chatPrompt, this);
        getServer().getPluginManager().registerEvents(news, this);
        getServer().getPluginManager().registerEvents(bank, this);
        getServer().getPluginManager().registerEvents(tracker, this);
        getServer().getPluginManager().registerEvents(chestShops, this);
        getServer().getPluginManager().registerEvents(messages, this);
        getServer().getPluginManager().registerEvents(tab, this);
        getServer().getPluginManager().registerEvents(fakePlayers, this);
        tab.reload();

        registerCommands();

        webPanel = new WebPanel(this);
        webPanel.start();
    }

    @Override
    public void onDisable() {
        if (webPanel != null) webPanel.stop();
        if (autoUpdater != null) autoUpdater.shutdown();
        if (fakePlayers != null) fakePlayers.shutdown();
        if (softRestart != null) softRestart.shutdown();
        if (tab != null) tab.shutdown();
        if (vanish != null) vanish.shutdown();
        if (tracker != null) tracker.shutdown();
        if (creativeLog != null) creativeLog.shutdown();
        if (punishments != null) punishments.shutdown();
        if (chestShops != null) chestShops.shutdown();
        if (luckyWheel != null) luckyWheel.shutdown();
        if (bank != null) bank.shutdown();
    }

    /**
     * Ergänzt nach einem Update fehlende Optionen aus der mitgelieferten config.yml samt Kommentaren.
     * Vorhandene Werte bleiben unverändert; neue Optionen landen am Ende ihres Abschnitts.
     */
    private void addNewConfigOptions() {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults;
        try (InputStream in = getResource("config.yml")) {
            if (in == null) return;
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Mitgelieferte config.yml nicht lesbar", e);
            return;
        }
        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (current.contains(key, true)) continue;
            if (defaults.isConfigurationSection(key)) current.createSection(key);
            else current.set(key, defaults.get(key));
            current.setComments(key, defaults.getComments(key));
            current.setInlineComments(key, defaults.getInlineComments(key));
            added.add(key);
        }
        if (added.isEmpty()) return;
        try {
            current.save(file);
            reloadConfig();
            getLogger().info("config.yml um " + added.size() + " neue Optionen ergänzt: " + String.join(", ", added));
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "config.yml konnte nicht ergänzt werden", e);
        }
    }

    public void reloadAll() {
        reloadConfig();
        text.setPrefix(getConfig().getString("prefix", ""));
        chat.reload();
        joinQuit.reload();
        vanish.reload();
        detector.reload();
        itemGuard.reload();
        creativeLog.reload();
        bankExchange = BankExchange.fromConfig(getConfig(), getLogger());
        tracker.reload();
        spawn.reload();
        help.reload();
        tpa.reload();
        enderChest.reload();
        luckyWheel.reload();
        autoUpdater.reload();
        fakePlayers.reload();
        tab.reload();
        // Webpanel neu starten, damit Port/Adresse greifen; Sitzungen bleiben erhalten
        webPanel.stop();
        webPanel.start();
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

    public PunishmentManager punishments() {
        return punishments;
    }

    public ItemGuard itemGuard() {
        return itemGuard;
    }

    public ChestShopManager chestShops() {
        return chestShops;
    }

    public ItemTracker tracker() {
        return tracker;
    }

    public NewsManager news() {
        return news;
    }

    public CreativeLog creativeLog() {
        return creativeLog;
    }

    public ChatPrompt chatPrompt() {
        return chatPrompt;
    }

    public BankManager bank() {
        return bank;
    }

    /** Das geladene Plugin-Jar (für den Auto-Updater). */
    public File jarFile() {
        return getFile();
    }

    public LuckyWheel luckyWheel() {
        return luckyWheel;
    }

    public BankExchange bankExchange() {
        return bankExchange;
    }

    /** Kacheln im Admin-Panel. Reihenfolge innerhalb einer Kategorie = Position in der Zeile. */
    private void registerAdminApps() {
        // Moderation
        adminPanel.addApp(Category.MODERATION, PunishmentMenu.PERM,
                p -> Menu.icon(Material.IRON_BARS, Component.text("Strafen", NamedTextColor.DARK_RED),
                        Component.text("Aktiv: " + punishments.active().size(), NamedTextColor.GRAY),
                        Component.text("Bans, Mutes, IP-Bans", NamedTextColor.GRAY)),
                punishmentMenu::open);
        adminPanel.addApp(Category.MODERATION, "sess.vanish",
                p -> Menu.icon(Material.ENDER_EYE, Component.text("Vanish", NamedTextColor.LIGHT_PURPLE), status(vanish.isVanished(p))),
                p -> vanish.toggle(p, true));
        adminPanel.addApp(Category.MODERATION, "sess.reports.admin",
                p -> Menu.icon(Material.WRITABLE_BOOK, Component.text("Reports", NamedTextColor.RED),
                        Component.text("Offen: " + reports.openCount(), NamedTextColor.GRAY)),
                p -> reports.menu().openList(p, 1));
        adminPanel.addApp(Category.MODERATION, ItemGuardMenu.PERM,
                p -> Menu.icon(Material.SHIELD, Component.text("ItemGuard", NamedTextColor.RED),
                        status(itemGuard.enabled()),
                        Component.text("Verbotene Items: " + itemGuard.banned().size(), NamedTextColor.GRAY),
                        Component.text("Entfernt seit Start: " + itemGuard.removedSinceStart(), NamedTextColor.GRAY)),
                itemGuardMenu::open);
        adminPanel.addApp(Category.MODERATION, CreativeLogMenu.PERM,
                p -> Menu.icon(Material.GRASS_BLOCK, Component.text("CreativeLog", NamedTextColor.GREEN),
                        status(creativeLog.enabled()),
                        Component.text("Aktionen im Kreativmodus", NamedTextColor.GRAY)),
                creativeLogMenu::open);

        // Spieler & Teleport
        adminPanel.addApp(Category.PLAYERS, p -> COMMAND_PERMS.stream().anyMatch(p::hasPermission),
                p -> Menu.icon(Material.COMMAND_BLOCK, Component.text("Befehle", NamedTextColor.AQUA),
                        Component.text("Fly, God, Heal, Feed, Enderchest", NamedTextColor.GRAY),
                        Component.text("Spawn, Nether, End", NamedTextColor.GRAY)),
                this::openCommandsMenu);

        // Wirtschaft
        adminPanel.addApp(Category.ECONOMY, "sess.bank.admin",
                p -> Menu.icon(Material.GOLD_BLOCK, Component.text("Bank", NamedTextColor.GOLD),
                        Component.text("Im Umlauf: " + bank.format(bank.total()), NamedTextColor.GRAY),
                        Component.text("Konten ansehen und ändern", NamedTextColor.GRAY)),
                bankMenu::openAdmin);
        adminPanel.addApp(Category.ECONOMY, TrackerMenu.PERM,
                p -> Menu.icon(Material.SPYGLASS, Component.text("Item-Tracker", NamedTextColor.AQUA),
                        Component.text("Wertvolle Items in Inventar und", NamedTextColor.GRAY),
                        Component.text("Enderchest, mit Verlauf", NamedTextColor.GRAY)),
                p -> trackerMenu.openOverview(p, 1));
        adminPanel.addApp(Category.ECONOMY, LuckyWheel.PERM_ADMIN,
                p -> Menu.icon(Material.CLOCK, Component.text("Glücksrad", NamedTextColor.LIGHT_PURPLE),
                        Component.text("Topf: " + bank.format(bank.pot()), NamedTextColor.GRAY),
                        Component.text("Topf setzen, Chancen ansehen", NamedTextColor.GRAY)),
                luckyWheel::openAdmin);

        // Server
        adminPanel.addApp(Category.SERVER, "sess.news.admin",
                p -> Menu.icon(Material.OAK_SIGN, Component.text("News", NamedTextColor.GOLD),
                        Component.text("News erstellen und verwalten", NamedTextColor.GRAY)),
                p -> news.menu().openPanel(p));
        adminPanel.addApp(Category.SERVER, "sess.softrestart",
                p -> Menu.icon(Material.CLOCK, Component.text("Softrestart", NamedTextColor.RED),
                        softRestart.isRunning()
                                ? text.render("<gray>Neustart in <white><time>", softRestart.time(softRestart.remainingSeconds()))
                                : Component.text("Kein Neustart geplant", NamedTextColor.GRAY)),
                this::openSoftRestartMenu);
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

    /** Untermenü „Befehle“: Spieler- und Teleport-Befehle, jede Kachel nur mit ihrem Recht. */
    private void openCommandsMenu(Player player) {
        List<Menu.Entry> self = new ArrayList<>();
        if (player.hasPermission("sess.fly")) {
            self.add(new Menu.Entry(Menu.icon(Material.FEATHER, Component.text("Fly", NamedTextColor.AQUA), status(player.getAllowFlight())),
                    p -> playerCommands.execute("fly", p)));
        }
        if (player.hasPermission("sess.god")) {
            self.add(new Menu.Entry(Menu.icon(Material.TOTEM_OF_UNDYING, Component.text("God", NamedTextColor.GOLD), status(playerCommands.isGod(player))),
                    p -> playerCommands.execute("god", p)));
        }
        if (player.hasPermission("sess.heal")) {
            self.add(new Menu.Entry(Menu.icon(Material.GOLDEN_APPLE, Component.text("Heal", NamedTextColor.RED)),
                    p -> playerCommands.execute("heal", p)));
        }
        if (player.hasPermission("sess.feed")) {
            self.add(new Menu.Entry(Menu.icon(Material.COOKED_BEEF, Component.text("Feed", NamedTextColor.YELLOW)),
                    p -> playerCommands.execute("feed", p)));
        }
        if (player.hasPermission(EnderChestCommand.PERM_USE)) {
            self.add(new Menu.Entry(Menu.icon(Material.ENDER_CHEST, Component.text("Enderchest", NamedTextColor.DARK_PURPLE)),
                    enderChest::open));
        }

        List<Menu.Entry> teleport = new ArrayList<>();
        if (player.hasPermission("sess.spawn")) {
            teleport.add(new Menu.Entry(Menu.icon(Material.COMPASS, Component.text("Spawn", NamedTextColor.GREEN)), p -> {
                p.closeInventory();
                spawn.teleport(p);
            }));
        }
        if (player.hasPermission("sess.setspawn")) {
            teleport.add(new Menu.Entry(Menu.icon(Material.RED_BED, Component.text("Spawn setzen", NamedTextColor.GOLD),
                    Component.text("Setzt den Spawn auf deine Position", NamedTextColor.GRAY)), spawn::setSpawn));
        }
        if (player.hasPermission("sess.nether")) {
            teleport.add(new Menu.Entry(Menu.icon(Material.NETHERRACK, Component.text("Nether", NamedTextColor.RED),
                    Component.text("Im Nether: zurück in die Oberwelt", NamedTextColor.GRAY)), p -> {
                p.closeInventory();
                dimensions.toggle(p, World.Environment.NETHER);
            }));
        }
        if (player.hasPermission("sess.end")) {
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

            commands.register(
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

            commands.register(
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

            playerCommands.register(commands);
            reports.register(commands);
            dimensions.register(commands);
            spawn.register(commands);
            help.register(commands);
            tpa.register(commands);
            enderChest.register(commands);
            luckyWheel.register(commands);
            autoUpdater.register(commands);
            fakePlayers.register(commands);
            messages.register(commands);
            shopCreator.register(commands);
            itemGuard.register(commands);
            creativeLog.register(commands);
            punishCommands.register(commands);
            webPanel.register(commands);
            news.register(commands);
            bankCommands.register(commands);

            commands.register(
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
