package lib.kasuga.rendering.output.mc.stream;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lib.kasuga.KasugaLib;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** A camera subscription tied to one client connection and one world incarnation. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class CameraChunkClient implements AutoCloseable {
    private static final int MOVE_INTERVAL = 10;
    private static final int HEARTBEAT_INTERVAL = 100;
    private static final Map<UUID, CameraChunkClient> ACTIVE = new HashMap<>();
    private final UUID id = UUID.randomUUID();
    private final ClientLevel level;
    private final ClientPacketListener connection;
    private final int radius;
    private final CameraEntityClient entities;
    private final Consumer<ClientboundLevelChunkWithLightPacket> receiveChunk;
    private CameraChunkRequest request;
    private Assembly assembly;
    private int requestTick;

    private CameraChunkClient(ClientLevel level, ClientLevel snapshot, ClientPacketListener connection, int radius,
                              Consumer<ClientboundLevelChunkWithLightPacket> receiveChunk) {
        this.level = level;
        this.connection = connection;
        this.radius = radius;
        entities = NetworkRegistry.hasChannel(connection, CameraEntityPayload.TYPE.id())
                ? new CameraEntityClient(connection, snapshot) : null;
        this.receiveChunk = receiveChunk;
        ACTIVE.put(id, this);
    }

    public static CameraChunkClient open(ClientLevel level, ClientLevel snapshot, ClientPacketListener connection, int radius,
                                         Consumer<ClientboundLevelChunkWithLightPacket> receiveChunk) {
        if (connection == null || !NetworkRegistry.hasChannel(connection, CameraChunkRequest.TYPE.id())
                || !NetworkRegistry.hasChannel(connection, CameraChunkFragment.TYPE.id())) return null;
        return new CameraChunkClient(level, snapshot, connection, radius, receiveChunk);
    }

    @SubscribeEvent
    public static void register(RegisterClientPayloadHandlersEvent event) {
        event.register(CameraChunkFragment.TYPE, (fragment, context) -> {
            CameraChunkClient camera = ACTIVE.get(fragment.camera());
            if (camera != null && camera.connection.getConnection() == context.connection()) camera.receive(fragment);
        });
        event.register(CameraEntityPayload.TYPE, (payload, context) -> {
            CameraChunkClient camera = ACTIVE.get(payload.camera());
            if (camera != null && camera.entities != null && camera.request != null
                    && camera.connection.getConnection() == context.connection() && camera.isConnected()
                    && payload.epoch() == camera.request.epoch()) camera.entities.receive(payload.packets());
        });
    }

    public void update(int x, int z, int tick) {
        if (!isConnected()) return;
        if (request == null || ((request.x() != x || request.z() != z) && tick - requestTick >= MOVE_INTERVAL)) {
            long epoch = request == null ? 0 : request.epoch() + 1;
            CameraChunkRequest next = new CameraChunkRequest(id, epoch, level.dimension().identifier(), x, z, radius, true);
            if (!next.isValid()) return;
            request = next;
            assembly = null;
            if (entities != null) entities.clear();
            connection.send(request);
            requestTick = tick;
        } else if (tick - requestTick >= HEARTBEAT_INTERVAL) {
            connection.send(request);
            requestTick = tick;
        }
    }

    public void tickEntities() {
        if (entities != null) entities.tick();
    }

    private boolean isConnected() {
        return connection.getLevel() == level && connection.getConnection().isConnected();
    }

    private void receive(CameraChunkFragment fragment) {
        if (request == null || !isConnected() || fragment.epoch() != request.epoch()
                || !request.contains(fragment.x(), fragment.z()) || !fragment.isValid()) return;
        if (fragment.index() == 0) assembly = new Assembly(fragment.x(), fragment.z(), new byte[fragment.count()][]);
        if (assembly == null || assembly.x != fragment.x() || assembly.z != fragment.z()
                || assembly.parts.length != fragment.count() || assembly.next != fragment.index()) {
            assembly = null;
            return;
        }
        assembly.parts[assembly.next++] = fragment.bytes();
        if (assembly.next != assembly.parts.length) return;
        ByteBuf[] parts = new ByteBuf[assembly.parts.length];
        for (int i = 0; i < parts.length; i++) parts[i] = Unpooled.wrappedBuffer(assembly.parts[i]);
        assembly = null;
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(parts), connection.registryAccess());
        try {
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
            if (packet.x() == fragment.x() && packet.z() == fragment.z() && !buffer.isReadable()) receiveChunk.accept(packet);
        } catch (RuntimeException failure) {
            LogUtils.getLogger().warn("Remote camera {} could not apply chunk ({}, {})", id, fragment.x(), fragment.z(), failure);
        } finally {
            buffer.release();
        }
    }

    @Override public void close() {
        if (ACTIVE.remove(id) == null) return;
        if (request != null && isConnected()) {
            connection.send(new CameraChunkRequest(id, request.epoch(), request.dimension(), request.x(), request.z(), radius, false));
        }
        request = null;
        assembly = null;
        if (entities != null) entities.clear();
    }

    private static final class Assembly {
        private final int x;
        private final int z;
        private final byte[][] parts;
        private int next;

        Assembly(int x, int z, byte[][] parts) {
            this.x = x;
            this.z = z;
            this.parts = parts;
        }
    }
}
