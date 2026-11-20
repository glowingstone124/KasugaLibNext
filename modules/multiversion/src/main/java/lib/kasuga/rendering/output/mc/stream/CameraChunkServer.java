package lib.kasuga.rendering.output.mc.stream;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.profile.CameraChunkSnapshotEvent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Services remote views without granting them player tracking or simulation distance. */
@EventBusSubscriber(modid = KasugaLib.MODID)
public final class CameraChunkServer {
    private static final int MAX_CAMERAS = 4;
    private static final int UPDATE_INTERVAL = 10;
    private static final int TIMEOUT = 400;
    private static final int LOADING_LIMIT = 16;
    private static final int TICKETS_PER_TICK = 8;
    private static final int CHUNKS_PER_TICK = 4;
    private static final TicketType TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
    private static final Map<ServerPlayer, Map<UUID, Subscription>> PLAYERS = new HashMap<>();
    private static final Map<WatchedChunk, Integer> WATCHERS = new ConcurrentHashMap<>();
    private static final Set<WatchedChunk> DIRTY = ConcurrentHashMap.newKeySet();

    private CameraChunkServer() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("2").optional()
                .playToServer(CameraChunkRequest.TYPE, CameraChunkRequest.CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) request(player, payload);
                })
                .playToClient(CameraChunkFragment.TYPE, CameraChunkFragment.CODEC)
                .playToClient(CameraEntityPayload.TYPE, CameraEntityPayload.CODEC);
    }

    public static void markDirty(ServerLevel level, ChunkPos pos) {
        WatchedChunk chunk = new WatchedChunk(level, pos.pack());
        if (WATCHERS.containsKey(chunk)) DIRTY.add(chunk);
    }

    private static void request(ServerPlayer player, CameraChunkRequest request) {
        if (!request.isValid()) return;
        Map<UUID, Subscription> cameras = PLAYERS.get(player);
        Subscription previous = cameras == null ? null : cameras.get(request.camera());
        if (previous != null && request.epoch() < previous.request.epoch()) return;
        ServerLevel level = player.level();
        if (!request.open() || !level.dimension().identifier().equals(request.dimension())) {
            if (previous != null) {
                cameras.remove(request.camera());
                previous.close();
                if (cameras.isEmpty()) PLAYERS.remove(player);
            }
            return;
        }
        if (!NetworkRegistry.hasChannel(player.connection, CameraChunkFragment.TYPE.id())) return;
        int tick = level.getServer().getTickCount();
        if (previous != null) {
            if (previous.request.equals(request)) {
                previous.lastHeartbeat = tick;
                return;
            }
            if (request.epoch() == previous.request.epoch() || tick - previous.started < UPDATE_INTERVAL - 1) return;
        } else if (cameras != null && cameras.size() >= MAX_CAMERAS) {
            return;
        }
        if (cameras == null) {
            cameras = new HashMap<>();
            PLAYERS.put(player, cameras);
        }
        if (previous != null) previous.close();
        cameras.put(request.camera(), new Subscription(player, level, request, tick));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        for (WatchedChunk changed : DIRTY) {
            if (changed.level.getServer() != server || !DIRTY.remove(changed)) continue;
            for (Map<UUID, Subscription> cameras : PLAYERS.values()) {
                for (Subscription camera : cameras.values()) {
                    if (camera.level == changed.level && camera.tickets.contains(changed.position)) camera.pending.add(changed.position);
                }
            }
        }
        int tick = server.getTickCount();
        Iterator<Map.Entry<ServerPlayer, Map<UUID, Subscription>>> players = PLAYERS.entrySet().iterator();
        while (players.hasNext()) {
            Map.Entry<ServerPlayer, Map<UUID, Subscription>> entry = players.next();
            ServerPlayer player = entry.getKey();
            if (player.level().getServer() != server) continue;
            Iterator<Subscription> cameras = entry.getValue().values().iterator();
            while (cameras.hasNext()) {
                Subscription camera = cameras.next();
                if (!player.hasDisconnected() && player.level() == camera.level
                        && tick - camera.lastHeartbeat <= TIMEOUT) {
                    try {
                        camera.tick();
                        continue;
                    } catch (RuntimeException failure) {
                        LogUtils.getLogger().error("Remote camera {} could not send chunks", camera.request.camera(), failure);
                    }
                }
                cameras.remove();
                camera.close();
            }
            if (entry.getValue().isEmpty()) players.remove();
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) remove(player);
    }

    @SubscribeEvent
    public static void replacedPlayer(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer player) remove(player);
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) remove(player);
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        PLAYERS.entrySet().removeIf(entry -> {
            if (entry.getKey().level().getServer() != event.getServer()) return false;
            for (Subscription camera : entry.getValue().values()) camera.close();
            return true;
        });
        DIRTY.removeIf(chunk -> chunk.level.getServer() == event.getServer());
    }

    private static void remove(ServerPlayer player) {
        Map<UUID, Subscription> cameras = PLAYERS.remove(player);
        if (cameras == null) return;
        for (Subscription camera : cameras.values()) camera.close();
    }

    // Package-visible for the integrated-server lifecycle smoke.
    static int watchedChunks() { return WATCHERS.size(); }

    private record WatchedChunk(ServerLevel level, long position) {}

    private static final class Subscription {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final CameraChunkRequest request;
        private final int started;
        private int lastHeartbeat;
        private final CameraEntityServer entities;
        private ChunkTrackingView playerView;
        private final LongArrayFIFOQueue waiting = new LongArrayFIFOQueue();
        private final LongOpenHashSet tickets = new LongOpenHashSet();
        private final LongOpenHashSet loading = new LongOpenHashSet();
        private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();

        Subscription(ServerPlayer player, ServerLevel level, CameraChunkRequest request, int tick) {
            this.player = player;
            this.level = level;
            this.request = request;
            started = tick;
            lastHeartbeat = tick;
            playerView = player.getChunkTrackingView();
            entities = NetworkRegistry.hasChannel(player.connection, CameraEntityPayload.TYPE.id())
                    ? new CameraEntityServer(player, level, request) : null;
            waiting.enqueue(ChunkPos.pack(request.x(), request.z()));
            for (int ring = 1; ring <= request.radius() + 1; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    waiting.enqueue(ChunkPos.pack(request.x() + dx, request.z() - ring));
                    waiting.enqueue(ChunkPos.pack(request.x() + dx, request.z() + ring));
                }
                for (int dz = 1 - ring; dz < ring; dz++) {
                    waiting.enqueue(ChunkPos.pack(request.x() - ring, request.z() + dz));
                    waiting.enqueue(ChunkPos.pack(request.x() + ring, request.z() + dz));
                }
            }
        }

        void tick() {
            ServerChunkCache source = level.getChunkSource();
            ChunkTrackingView tracking = player.getChunkTrackingView();
            if (tracking != playerView) {
                for (LongIterator chunks = tickets.iterator(); chunks.hasNext();) {
                    long key = chunks.nextLong();
                    ChunkPos pos = ChunkPos.unpack(key);
                    if (playerView.contains(pos) && !tracking.contains(pos)) pending.add(key);
                }
                playerView = tracking;
            }
            for (int i = 0; i < TICKETS_PER_TICK && loading.size() < LOADING_LIMIT && !waiting.isEmpty(); i++) {
                long key = waiting.dequeueLong();
                WatchedChunk watched = new WatchedChunk(level, key);
                if (WATCHERS.merge(watched, 1, Integer::sum) == 1) {
                    source.addTicketWithRadius(TICKET, ChunkPos.unpack(key), 0);
                }
                tickets.add(key);
                loading.add(key);
                pending.add(key);
            }
            if (entities != null && (level.getServer().getTickCount() - started) % 5 == 0) entities.sendSnapshot();
            int budget = CHUNKS_PER_TICK;
            for (LongIterator chunks = pending.iterator(); chunks.hasNext() && budget > 0;) {
                long key = chunks.nextLong();
                ChunkPos pos = ChunkPos.unpack(key);
                LevelChunk chunk = source.getChunkNow(pos.x(), pos.z());
                if (chunk == null || !chunk.isLightCorrect()) continue;
                // The vanilla client cache already supplies these snapshots and receives their changes.
                if (!playerView.contains(pos)) {
                    send(chunk, source);
                    budget--;
                }
                chunks.remove();
                loading.remove(key);
            }
        }

        private void send(LevelChunk chunk, ServerChunkCache source) {
            CameraChunkSnapshotEvent timing = CameraChunkSnapshotEvent.start();
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, source.getLightEngine(), null, null);
                ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
                int count = Math.ceilDiv(buffer.readableBytes(), CameraChunkFragment.PART_SIZE);
                if (count > CameraChunkFragment.MAX_PARTS) {
                    throw new IllegalStateException("Camera chunk exceeds the snapshot size limit");
                }
                for (int index = 0; index < count; index++) {
                    byte[] bytes = new byte[Math.min(buffer.readableBytes(), CameraChunkFragment.PART_SIZE)];
                    buffer.readBytes(bytes);
                    PacketDistributor.sendToPlayer(player, new CameraChunkFragment(request.camera(), request.epoch(),
                            chunk.getPos().x(), chunk.getPos().z(), index, count, bytes));
                }
            } finally {
                if (timing != null) timing.finish(buffer.writerIndex());
                buffer.release();
            }
        }

        void close() {
            for (LongIterator chunks = tickets.iterator(); chunks.hasNext();) {
                long key = chunks.nextLong();
                WatchedChunk watched = new WatchedChunk(level, key);
                if (WATCHERS.computeIfPresent(watched, (ignored, count) -> count > 1 ? count - 1 : null) == null) {
                    level.getChunkSource().removeTicketWithRadius(TICKET, ChunkPos.unpack(key), 0);
                    DIRTY.remove(watched);
                }
            }
            tickets.clear();
        }
    }
}
