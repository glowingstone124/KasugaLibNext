package lib.kasuga.rendering.output.mc;

import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lib.kasuga.core.rendering.mixin.CameraBiomeSeedAccess;
import lib.kasuga.rendering.output.mc.stream.CameraChunkClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

/** Private chunk and light caches. Extraction consumes only this view's chunk-change sets. */
final class CameraChunkSnapshot extends ClientLevel implements AutoCloseable {
    private static final int CHUNKS_PER_FRAME = 8;
    private final ClientLevel source;
    private final int radius;
    private final LongOpenHashSet loaded = new LongOpenHashSet();
    private final LongOpenHashSet dirty = new LongOpenHashSet();
    private final LongOpenHashSet remote = new LongOpenHashSet();
    private final Long2ObjectLinkedOpenHashMap<ClientboundLevelChunkWithLightPacket> incoming = new Long2ObjectLinkedOpenHashMap<>();
    private final CameraChunkClient stream;
    private int streamTick;
    private int centerX = Integer.MIN_VALUE;
    private int centerZ = Integer.MIN_VALUE;

    CameraChunkSnapshot(Minecraft client, ClientLevel source, LevelExtractor extractor, int radius) {
        super(client.getConnection(), new ClientLevelData(source.getDifficulty(), source.getLevelData().isHardcore(), false),
                source.dimension(), source.dimensionTypeRegistration(), radius, 0, extractor, source.isDebug(),
                ((CameraBiomeSeedAccess) (Object) source.getBiomeManager()).kasuga$biomeZoomSeed(), source.getSeaLevel());
        this.source = source;
        this.radius = radius;
        stream = CameraChunkClient.open(source, this, client.getConnection(), radius, this::enqueueRemote);
    }

    @Override public Iterable<Entity> entitiesForRendering() {
        Iterable<Entity> replicas = super.entitiesForRendering();
        if (source == null) return replicas;
        return Iterables.concat(source.entitiesForRendering(), Iterables.filter(replicas, entity -> source.getEntity(entity.getId()) == null));
    }

    boolean isFor(ClientLevel level) { return source == level; }

    void markDirty(ChunkPos pos) {
        if (inRange(pos.x(), pos.z())) dirty.add(pos.pack());
    }

    void update(Vec3 eye) {
        setTimeFromServer(source.getGameTime());
        setRainLevel(source.getRainLevel(1));
        setThunderLevel(source.getThunderLevel(1));
        int x = SectionPos.blockToSectionCoord(eye.x);
        int z = SectionPos.blockToSectionCoord(eye.z);
        boolean moved = x != centerX || z != centerZ;
        centerX = x;
        centerZ = z;
        if (stream != null) stream.update(x, z, streamTick);
        LongIterator existing = loaded.iterator();
        while (existing.hasNext()) {
            long key = existing.nextLong();
            ChunkPos pos = ChunkPos.unpack(key);
            boolean localChunk = hasLocalChunk(pos.x(), pos.z());
            if (!inRange(pos.x(), pos.z()) || (!remote.contains(key) && !localChunk)) {
                drop(pos);
                existing.remove();
                dirty.remove(key);
                remote.remove(key);
            } else if (localChunk && remote.remove(key)) {
                dirty.add(key);
            }
        }
        // ClientChunkCache.drop checks the previous center, so evict before moving it.
        if (moved) {
            getChunkSource().updateViewCenter(x, z);
            incoming.values().removeIf(packet -> !inRange(packet.x(), packet.z()));
            dirty.removeIf(key -> !inRange(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
        int remaining = CHUNKS_PER_FRAME;
        // Fill near chunks first without scanning the interior again for each ring.
        if (copyIfNeeded(x, z)) remaining--;
        for (int ring = 1; ring <= radius + 1 && remaining > 0; ring++) {
            for (int dx = -ring; dx <= ring && remaining > 0; dx++) {
                if (copyIfNeeded(x + dx, z - ring)) remaining--;
                if (remaining > 0 && copyIfNeeded(x + dx, z + ring)) remaining--;
            }
            for (int dz = -ring + 1; dz < ring && remaining > 0; dz++) {
                if (copyIfNeeded(x - ring, z + dz)) remaining--;
                if (remaining > 0 && copyIfNeeded(x + ring, z + dz)) remaining--;
            }
        }
        while (remaining-- > 0 && !incoming.isEmpty()) {
            ClientboundLevelChunkWithLightPacket packet = incoming.removeFirst();
            if (hasLocalChunk(packet.x(), packet.z()) || !apply(packet)) continue;
            long key = ChunkPos.pack(packet.x(), packet.z());
            loaded.add(key);
            remote.add(key);
            dirty.remove(key);
        }
        getLightEngine().runLightUpdates();
    }

    void tickStreaming() {
        streamTick++;
        if (stream != null) stream.tickEntities();
    }

    private boolean hasLocalChunk(int x, int z) {
        return source.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
    }

    private void enqueueRemote(ClientboundLevelChunkWithLightPacket packet) {
        if (!inRange(packet.x(), packet.z())) return;
        incoming.put(ChunkPos.pack(packet.x(), packet.z()), packet);
    }

    private boolean copyIfNeeded(int x, int z) {
        long key = ChunkPos.pack(x, z);
        if (loaded.contains(key) && !dirty.contains(key)) return false;
        LevelChunk chunk = source.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) return false;
        if (!apply(new ClientboundLevelChunkWithLightPacket(chunk, source.getLightEngine(), null, null))) return false;
        loaded.add(key);
        dirty.remove(key);
        return true;
    }

    private boolean inRange(int x, int z) {
        return centerX != Integer.MIN_VALUE && Math.abs((long) x - centerX) <= radius + 1 && Math.abs((long) z - centerZ) <= radius + 1;
    }

    private boolean apply(ClientboundLevelChunkWithLightPacket packet) {
        LevelChunk chunk = getChunkSource().replaceWithPacketData(packet.x(), packet.z(), packet.chunkData());
        if (chunk == null) return false;
        LevelLightEngine light = getLightEngine();
        ClientboundLightUpdatePacketData data = packet.lightData();
        queueLight(light, chunk.getPos(), LightLayer.SKY, data.skyYMask(), data.emptySkyYMask(), data.skyUpdates());
        queueLight(light, chunk.getPos(), LightLayer.BLOCK, data.blockYMask(), data.emptyBlockYMask(), data.blockUpdates());
        light.setLightEnabled(chunk.getPos(), true);
        for (int section = 0; section < chunk.getSections().length; section++) {
            int y = getSectionYFromSectionIndex(section);
            light.updateSectionStatus(SectionPos.of(chunk.getPos(), y), chunk.getSections()[section].hasOnlyAir());
        }
        setSectionRangeDirty(packet.x() - 1, getMinSectionY(), packet.z() - 1,
                packet.x() + 1, getMaxSectionY(), packet.z() + 1);
        return true;
    }

    private static void queueLight(LevelLightEngine light, ChunkPos pos, LightLayer layer, BitSet present, BitSet empty, List<byte[]> data) {
        Iterator<byte[]> bytes = data.iterator();
        for (int section = 0; section < light.getLightSectionCount(); section++) {
            if (!present.get(section) && !empty.get(section)) continue;
            int y = light.getMinLightSection() + section;
            light.queueSectionData(layer, SectionPos.of(pos, y), present.get(section) ? new DataLayer(bytes.next().clone()) : new DataLayer());
        }
    }

    private void drop(ChunkPos pos) {
        getChunkSource().drop(pos);
        LevelLightEngine light = getLightEngine();
        light.setLightEnabled(pos, false);
        for (int y = light.getMinLightSection(); y < light.getMaxLightSection(); y++) {
            light.queueSectionData(LightLayer.BLOCK, SectionPos.of(pos, y), null);
            light.queueSectionData(LightLayer.SKY, SectionPos.of(pos, y), null);
        }
    }

    @Override public void close() {
        if (stream != null) stream.close();
        incoming.clear();
        remote.clear();
        for (LongIterator chunks = loaded.iterator(); chunks.hasNext();) drop(ChunkPos.unpack(chunks.nextLong()));
        loaded.clear();
        dirty.clear();
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(this));
    }
}
