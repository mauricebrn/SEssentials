package de.sessentials.hook;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Wird nur instanziert, wenn LuckPerms installiert ist. Nutzt die (thread-sicheren) gecachten Daten. */
public final class LuckPermsHook implements PermissionInfo {

    private final LuckPerms api;
    private final Logger log;

    public LuckPermsHook(Logger log) {
        this.api = LuckPermsProvider.get();
        this.log = log;
    }

    /** Wartet höchstens 5 s – bei lokalem Speicher dauert das Millisekunden. Legt neue Spieler in LuckPerms an. */
    @Override
    public void loadUser(UUID id, String name) {
        try {
            // wie LuckPerms beim Login: Name ↔ UUID merken (sonst findet /lp user <name> ihn nicht), dann laden
            api.getUserManager().savePlayerData(id, name).get(5, TimeUnit.SECONDS);
            api.getUserManager().loadUser(id, name).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("LuckPerms-Daten für " + name + " nicht geladen", e);
        }
    }

    @Override
    public boolean supportsOwnPrefix() {
        return true;
    }

    @Override
    public void setOwnPrefix(UUID player, int priority, int oldPriority, String value) {
        api.getUserManager().modifyUser(player, user -> {
            user.data().clear(NodeType.PREFIX.predicate(n -> n.getPriority() == priority || n.getPriority() == oldPriority));
            if (value != null) user.data().add(PrefixNode.builder(value, priority).build());
        }).exceptionally(e -> {
            log.log(Level.WARNING, "LuckPerms-Prefix für " + player + " nicht gesetzt", e);
            return null;
        });
    }

    private CachedMetaData meta(Player player) {
        User user = api.getUserManager().getUser(player.getUniqueId());
        return user == null ? null : user.getCachedData().getMetaData();
    }

    @Override
    public String prefix(Player player) {
        CachedMetaData meta = meta(player);
        String value = meta == null ? null : meta.getPrefix();
        return value == null ? "" : value;
    }

    @Override
    public String suffix(Player player) {
        CachedMetaData meta = meta(player);
        String value = meta == null ? null : meta.getSuffix();
        return value == null ? "" : value;
    }

    @Override
    public String primaryGroup(Player player) {
        User user = api.getUserManager().getUser(player.getUniqueId());
        return user == null ? "default" : user.getPrimaryGroup();
    }

    @Override
    public String meta(Player player, String key) {
        CachedMetaData meta = meta(player);
        String value = meta == null ? null : meta.getMetaValue(key);
        return value == null ? "" : value;
    }
}
