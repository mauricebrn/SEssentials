package de.sessentials.fakeplayer;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.Action;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate.PlayerInfo;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * TAB-Einträge für Fake-Spieler als Pakete über PacketEvents. Die einzige Klasse, die PacketEvents
 * berührt – sie wird nur erzeugt, wenn das Plugin "packetevents" läuft.
 */
final class FakeTab {

    record Entry(UUID id, String name, String textureValue, String textureSignature, int ping, Component displayName) {}

    private static final EnumSet<Action> ADD = EnumSet.of(Action.ADD_PLAYER, Action.UPDATE_LISTED,
            Action.UPDATE_LATENCY, Action.UPDATE_GAME_MODE, Action.UPDATE_DISPLAY_NAME);

    void show(Player viewer, Collection<Entry> entries) {
        if (entries.isEmpty()) return;
        List<PlayerInfo> infos = entries.stream().map(FakeTab::info).toList();
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerPlayerInfoUpdate(ADD, infos));
    }

    void hide(Player viewer, Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerPlayerInfoRemove(List.copyOf(ids)));
    }

    private static PlayerInfo info(Entry e) {
        List<TextureProperty> textures = e.textureValue() == null ? List.of()
                : List.of(new TextureProperty("textures", e.textureValue(), e.textureSignature()));
        return new PlayerInfo(new UserProfile(e.id(), e.name(), textures), true, e.ping(), GameMode.SURVIVAL, e.displayName(), null);
    }
}
