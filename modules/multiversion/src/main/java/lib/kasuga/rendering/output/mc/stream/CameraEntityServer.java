package lib.kasuga.rendering.output.mc.stream;

import com.mojang.datafixers.util.Pair;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import lib.kasuga.core.rendering.mixin.CameraEntityDataAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.UpdateInterval;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** Snapshot visual state without consuming dirty tracking data from vanilla. */
final class CameraEntityServer {
    private static final int MAX_ENTITIES = 512;
    private static final ServerEntity.Synchronizer NO_TRACKING = new ServerEntity.Synchronizer() {
        @Override public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {}
        @Override public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {}
        @Override public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> predicate) {}
    };

    private final ServerPlayer player;
    private final ServerLevel level;
    private final CameraChunkRequest request;
    private final ProtocolInfo<ClientGamePacketListener> protocol;
    private Int2ObjectOpenHashMap<UUID> tracked = new Int2ObjectOpenHashMap<>();

    CameraEntityServer(ServerPlayer player, ServerLevel level, CameraChunkRequest request) {
        this.player = player;
        this.level = level;
        this.request = request;
        protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(buffer -> new RegistryFriendlyByteBuf(buffer, level.registryAccess()));
    }

    void sendSnapshot() {
        int radius = request.radius();
        AABB bounds = new AABB((request.x() - radius) * 16.0, level.getMinY(), (request.z() - radius) * 16.0,
                (request.x() + radius + 1) * 16.0, level.getMaxY(), (request.z() + radius + 1) * 16.0);
        List<Entity> entities = level.getEntities(player, bounds, entity -> !entity.isRemoved() && entity.broadcastToPlayer(player));
        if (entities.size() > MAX_ENTITIES) {
            double x = bounds.getCenter().x;
            double z = bounds.getCenter().z;
            entities.sort(Comparator.comparingDouble(entity -> Mth.lengthSquared(entity.getX() - x, entity.getZ() - z)));
            entities = entities.subList(0, MAX_ENTITIES);
        }
        Int2ObjectOpenHashMap<UUID> present = new Int2ObjectOpenHashMap<>();
        ByteBuf batch = Unpooled.buffer();
        ByteBuf encoded = Unpooled.buffer();
        try {
            for (Entity entity : entities) {
                if (!entity.getUUID().equals(tracked.get(entity.getId()))) {
                    ServerEntity tracker = new ServerEntity(level, entity, UpdateInterval.NEVER, false, NO_TRACKING);
                    if (!append(batch, encoded, entity.getAddEntityPacket(tracker))) continue;
                }
                present.put(entity.getId(), entity.getUUID());
                append(batch, encoded, ClientboundEntityPositionSyncPacket.of(entity));
                append(batch, encoded, new ClientboundRotateHeadPacket(entity, Mth.packDegrees(entity.getYHeadRot())));
                List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
                for (SynchedEntityData.DataItem<?> item : ((CameraEntityDataAccess) entity.getEntityData()).kasuga$itemsById()) {
                    values.add(item.value());
                }
                if (!values.isEmpty()) append(batch, encoded, new ClientboundSetEntityDataPacket(entity.getId(), values));
                if (entity instanceof LivingEntity living) {
                    List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>(EquipmentSlot.VALUES.size());
                    for (EquipmentSlot slot : EquipmentSlot.VALUES) equipment.add(Pair.of(slot, living.getItemBySlot(slot)));
                    append(batch, encoded, new ClientboundSetEquipmentPacket(entity.getId(), equipment));
                    append(batch, encoded, new ClientboundUpdateAttributesPacket(entity.getId(), living.getAttributes().getSyncableAttributes()));
                }
            }
            // Spawn all members before applying relationships so cross-entity lookups are local.
            for (Entity entity : entities) {
                int id = entity.getId();
                if (!present.containsKey(id)) continue;
                if (!entity.getPassengers().isEmpty() || tracked.containsKey(id)) append(batch, encoded, new ClientboundSetPassengersPacket(entity));
                if (entity instanceof Leashable leash && (leash.isLeashed() || tracked.containsKey(id))) {
                    append(batch, encoded, new ClientboundSetEntityLinkPacket(entity, leash.getLeashHolder()));
                }
            }
            IntArrayList removed = new IntArrayList();
            for (int id : tracked.keySet()) {
                if (!present.containsKey(id)) removed.add(id);
            }
            if (!removed.isEmpty()) append(batch, encoded, new ClientboundRemoveEntitiesPacket(removed));
            flush(batch);
            tracked = present;
        } finally {
            batch.release();
            encoded.release();
        }
    }

    private boolean append(ByteBuf batch, ByteBuf encoded, Packet<? super ClientGamePacketListener> packet) {
        if (!CameraEntityPayload.allowed(packet)) return false;
        encoded.clear();
        protocol.codec().encode(encoded, packet);
        if (encoded.readableBytes() > CameraEntityPayload.MAX_BYTES) return false;
        if (batch.readableBytes() + encoded.readableBytes() > CameraEntityPayload.MAX_BYTES) flush(batch);
        batch.writeBytes(encoded);
        return true;
    }

    private void flush(ByteBuf batch) {
        if (!batch.isReadable()) return;
        byte[] bytes = new byte[batch.readableBytes()];
        batch.readBytes(bytes);
        batch.clear();
        PacketDistributor.sendToPlayer(player, new CameraEntityPayload(request.camera(), request.epoch(), bytes));
    }
}
