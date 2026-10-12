package de.sessentials;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.sessentials.admin.AdminPanel;
import de.sessentials.admin.Menu;
import de.sessentials.chat.ChatListener;
import de.sessentials.detector.ClientDetector;
import de.sessentials.dimension.DimensionCommands;
import de.sessentials.help.HelpCommand;
import de.sessentials.hook.LuckPermsHook;
import de.sessentials.joinquit.JoinQuitListener;
import de.sessentials.notify.Notifications;
import de.sessentials.player.PlayerCommands;
import de.sessentials.report.ReportManager;
import de.sessentials.hook.PermissionInfo;
import de.sessentials.softrestart.SoftRestartManager;
import de.sessentials.spawn.SpawnManager;
import de.sessentials.tab.TabNametagManager;
import de.sessentials.text.TextService;
import de.sessentials.vanish.VanishManager;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();

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

        softRestart = new SoftRestartManager(this);
        chat = new ChatListener(this);
        joinQuit = new JoinQuitListener(this);
        tab = new TabNametagManager(this);
        playerCommands = new PlayerCommands(this);
        dimensions = new DimensionCommands(this);
        spawn = new SpawnManager(this);
        help = new HelpCommand(this);
        notifications = new Notifications(this);
        reports = new ReportManager(this);
        detector = new ClientDetector(this);
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
        getServer().getPluginManager().registerEvents(spawn, this);
        getServer().getPluginManager().registerEvents(tab, this);
        tab.reload();

        registerCommands();
    }

    @Override
    public void onDisable() {
        if (softRestart != null) softRestart.shutdown();
        if (tab != null) tab.shutdown();
        if (vanish != null) vanish.shutdown();
    }

    public void reloadAll() {
        reloadConfig();
        text.setPrefix(getConfig().getString("prefix", ""));
        chat.reload();
        joinQuit.reload();
        vanish.reload();
        detector.reload();
        spawn.reload();
        help.reload();
        tab.reload();
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

    /** Kacheln im Admin-Panel. Reihenfolge = Position im Panel. */
    private void registerAdminApps() {
        adminPanel.addApp("sess.vanish",
                p -> Menu.icon(Material.ENDER_EYE, Component.text("Vanish", NamedTextColor.LIGHT_PURPLE), status(vanish.isVanished(p))),
                p -> vanish.toggle(p, true));
        adminPanel.addApp(p -> COMMAND_PERMS.stream().anyMatch(p::hasPermission),
                p -> Menu.icon(Material.COMMAND_BLOCK, Component.text("Befehle", NamedTextColor.AQUA),
                        Component.text("Fly, God, Heal, Feed, Nether, End, Spawn", NamedTextColor.GRAY)),
                this::openCommandsMenu);
        adminPanel.addApp("sess.reports.admin",
                p -> Menu.icon(Material.WRITABLE_BOOK, Component.text("Reports", NamedTextColor.RED),
                        Component.text("Offen: " + reports.openCount(), NamedTextColor.GRAY)),
                p -> reports.menu().openList(p, 1));
        adminPanel.addApp(notifications::canConfigure,
                p -> Menu.icon(Material.BELL, Component.text("Benachrichtigungen", NamedTextColor.YELLOW),
                        Component.text("Reports & Client-Detector", NamedTextColor.GRAY)),
                notifications::openMenu);
        adminPanel.addApp("sess.softrestart",
                p -> Menu.icon(Material.CLOCK, Component.text("Softrestart", NamedTextColor.RED),
                        softRestart.isRunning()
                                ? text.render("<gray>Neustart in <white><time>", softRestart.time(softRestart.remainingSeconds()))
                                : Component.text("Kein Neustart geplant", NamedTextColor.GRAY)),
                this::openSoftRestartMenu);
        adminPanel.addApp("sess.reload",
                p -> Menu.icon(Material.COMPARATOR, Component.text("Config neu laden", NamedTextColor.GREEN)),
                p -> {
                    reloadAll();
                    p.sendMessage(text.render(getConfig().getString("messages.reloaded", "")));
                });
    }

    private static final List<String> COMMAND_PERMS = List.of("sess.fly", "sess.god", "sess.heal", "sess.feed", "sess.nether", "sess.end", "sess.spawn", "sess.setspawn");

    /** Untermenü mit /fly, /god, /heal, /feed, /nether, /end, /spawn, /setspawn – jede Kachel nur mit ihrem Recht. */
    private void openCommandsMenu(Player player) {
        Menu menu = new Menu(1, Component.text("Befehle"));
        int slot = 0;
        if (player.hasPermission("sess.fly")) {
            menu.set(slot++, Menu.icon(Material.FEATHER, Component.text("Fly", NamedTextColor.AQUA), status(player.getAllowFlight())),
                    p -> playerCommands.execute("fly", p));
        }
        if (player.hasPermission("sess.god")) {
            menu.set(slot++, Menu.icon(Material.TOTEM_OF_UNDYING, Component.text("God", NamedTextColor.GOLD), status(playerCommands.isGod(player))),
                    p -> playerCommands.execute("god", p));
        }
        if (player.hasPermission("sess.heal")) {
            menu.set(slot++, Menu.icon(Material.GOLDEN_APPLE, Component.text("Heal", NamedTextColor.RED)),
                    p -> playerCommands.execute("heal", p));
        }
        if (player.hasPermission("sess.feed")) {
            menu.set(slot++, Menu.icon(Material.COOKED_BEEF, Component.text("Feed", NamedTextColor.YELLOW)),
                    p -> playerCommands.execute("feed", p));
        }
        if (player.hasPermission("sess.nether")) {
            menu.set(slot++, Menu.icon(Material.NETHERRACK, Component.text("Nether", NamedTextColor.RED),
                            Component.text("Im Nether: zurück in die Oberwelt", NamedTextColor.GRAY)), p -> {
                p.closeInventory();
                dimensions.toggle(p, World.Environment.NETHER);
            });
        }
        if (player.hasPermission("sess.end")) {
            menu.set(slot++, Menu.icon(Material.END_STONE, Component.text("End", NamedTextColor.LIGHT_PURPLE),
                            Component.text("Im End: zurück in die Oberwelt", NamedTextColor.GRAY)), p -> {
                p.closeInventory();
                dimensions.toggle(p, World.Environment.THE_END);
            });
        }
        if (player.hasPermission("sess.spawn")) {
            menu.set(slot++, Menu.icon(Material.COMPASS, Component.text("Spawn", NamedTextColor.GREEN)), p -> {
                p.closeInventory();
                spawn.teleport(p);
            });
        }
        if (player.hasPermission("sess.setspawn") && slot < 8) {
            menu.set(slot, Menu.icon(Material.RED_BED, Component.text("Spawn setzen", NamedTextColor.GOLD),
                            Component.text("Setzt den Spawn auf deine Position", NamedTextColor.GRAY)), spawn::setSpawn);
        }
        menu.set(8, Menu.icon(Material.ARROW, Component.text("Zurück", NamedTextColor.GRAY)), adminPanel::open);
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
