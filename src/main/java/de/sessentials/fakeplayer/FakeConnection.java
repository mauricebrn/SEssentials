package de.sessentials.fakeplayer;

import io.netty.channel.ChannelFutureListener;
import io.papermc.paper.adventure.PaperAdventure;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * Verbindung ohne Client: Pakete an den Fake-Spieler werden verworfen. Wird er vom Server getrennt
 * (Kick, Ban, Stopp), meldet {@code onKick} das, damit er sauber aus der Spielerliste kommt –
 * diese Verbindung wird nicht vom Netzwerk-Code getickt, der das sonst erledigt.
 */
final class FakeConnection extends Connection {

    private final Consumer<DisconnectionDetails> onKick;
    private boolean disconnected;
    /** Solange gesetzt, gehen Chat-Ausgaben an den Fake-Spieler hierhin (z.B. an den Admin, der ihn steuert). */
    private Consumer<net.kyori.adventure.text.Component> forward;

    FakeConnection(Consumer<DisconnectionDetails> onKick) {
        super(PacketFlow.SERVERBOUND);
        this.onKick = onKick;
        this.channel = new EmbeddedChannel();
        this.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    /** Leitet Chat-Ausgaben weiter, bis wieder {@code null} gesetzt wird. Nur im Main-Thread. */
    void forwardMessages(Consumer<net.kyori.adventure.text.Component> target) {
        this.forward = target;
    }

    @Override
    public void send(Packet<?> packet) {
        capture(packet);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
        capture(packet);
        complete(listener);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        capture(packet);
        complete(listener);
    }

    private void capture(Packet<?> packet) {
        Consumer<net.kyori.adventure.text.Component> target = forward;
        if (target != null && packet instanceof ClientboundSystemChatPacket chat && !chat.overlay()) {
            target.accept(PaperAdventure.asAdventure(chat.content()));
        }
    }

    /** Als "gesendet" melden – ein Kick trennt die Verbindung erst, wenn das Disconnect-Paket raus ist. */
    private void complete(ChannelFutureListener listener) {
        if (listener == null) return;
        try {
            listener.operationComplete(channel.newSucceededFuture());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public boolean isConnected() {
        return !disconnected;
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        if (disconnected) return;
        disconnected = true;
        onKick.accept(details);
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void handleDisconnection() {
    }
}
