package de.sessentials.fakeplayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Serverseitiger Spieler ohne Client. Echte Spieler werden über ihre Netzwerkverbindung getickt
 * (Bewegung, Physik, Chunk-Laden); das übernimmt hier {@link #tick()} selbst.
 */
final class FakeServerPlayer extends ServerPlayer {

    FakeServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    @Override
    public void tick() {
        // Position regelmäßig als "bestätigt" übernehmen und den Chunk-Lader nachziehen, wie es sonst die Verbindung tut
        if (level().getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            level().getChunkSource().move(this);
        }
        super.tick();
        doTick();
    }
}
