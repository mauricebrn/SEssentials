package de.sessentials;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import de.sessentials.chat.ChatListener;
import de.sessentials.hook.LuckPermsHook;
import de.sessentials.joinquit.JoinQuitListener;
import de.sessentials.hook.PermissionInfo;
import de.sessentials.softrestart.SoftRestartManager;
import de.sessentials.tab.TabNametagManager;
import de.sessentials.text.TextService;
import de.sessentials.vanish.VanishManager;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
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

        getServer().getPluginManager().registerEvents(softRestart, this);
        getServer().getPluginManager().registerEvents(chat, this);
        getServer().getPluginManager().registerEvents(joinQuit, this);
        getServer().getPluginManager().registerEvents(vanish, this);
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
        tab.reload();
    }

    public TextService text() {
        return text;
    }

    public JoinQuitListener joinQuit() {
        return joinQuit;
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
        });
    }
}
