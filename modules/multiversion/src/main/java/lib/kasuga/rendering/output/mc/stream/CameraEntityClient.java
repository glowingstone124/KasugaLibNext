package lib.kasuga.rendering.output.mc.stream;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Applies only visual packets to a camera cache, with no access to the host packet listener's handlers. */
final class CameraEntityClient {
    private static final EntitySpawnRequest SPAWN = new EntitySpawnRequest(EntitySpawnReason.LOAD, true);
    private final ClientPacketListener connection;
    private final ClientLevel level;
    private final ProtocolInfo<ClientGamePacketListener> protocol;
    private final IntOpenHashSet present = new IntOpenHashSet();

    CameraEntityClient(ClientPacketListener connection, ClientLevel level) {
        this.connection = connection;
        this.level = level;
        protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(buffer -> new RegistryFriendlyByteBuf(buffer, level.registryAccess()));
    }

    void receive(byte[] bytes) {
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            while (buffer.isReadable()) {
                Packet<?> packet = protocol.codec().decode(buffer);
                if (!CameraEntityPayload.allowed(packet)) return;
                apply(packet);
            }
        } catch (RuntimeException failure) {
            LogUtils.getLogger().warn("Could not apply detached camera entity snapshot", failure);
        } finally {
            buffer.release();
        }
    }

    private void apply(Packet<?> packet) {
        switch (packet) {
            case ClientboundAddEntityPacket spawn -> spawn(spawn);
            case ClientboundEntityPositionSyncPacket movement -> move(movement);
            case ClientboundRemoveEntitiesPacket removed -> removed.entityIds().forEach((int id) -> remove(id));
            case ClientboundRotateHeadPacket head -> {
                Entity entity = head.getEntity(level);
                if (entity != null) entity.setYHeadRot(head.getYHeadRot());
            }
            case ClientboundSetEntityDataPacket data -> {
                Entity entity = level.getEntity(data.id());
                if (entity != null) entity.getEntityData().assignValues(data.packedItems());
            }
            case ClientboundSetEquipmentPacket equipment -> {
                if (level.getEntity(equipment.getEntity()) instanceof LivingEntity living) {
                    for (Pair<EquipmentSlot, ItemStack> slot : equipment.getSlots()) living.setItemSlot(slot.getFirst(), slot.getSecond());
                }
            }
            case ClientboundSetPassengersPacket riders -> passengers(riders);
            case ClientboundSetEntityLinkPacket link -> {
                if (level.getEntity(link.getSourceId()) instanceof Leashable leash) {
                    if (link.getDestId() == 0) leash.setLeashData(null);
                    else leash.setDelayedLeashHolderId(link.getDestId());
                }
            }
            case ClientboundUpdateAttributesPacket attributes -> attributes(attributes);
            default -> { }
        }
    }

    private void spawn(ClientboundAddEntityPacket packet) {
        Entity entity;
        if (packet.getType() == EntityTypes.PLAYER) {
            PlayerInfo player = connection.getPlayerInfo(packet.getUUID());
            if (player == null) return;
            entity = new RemotePlayer(level, player.getProfile());
        } else {
            entity = packet.getType().create(level, SPAWN);
            if (entity == null) return;
        }
        entity.recreateFromPacket(packet);
        entity.setYHeadRot(packet.getYHeadRot());
        entity.setOldPosAndRot();
        level.addEntity(entity);
        present.add(entity.getId());
    }

    private void move(ClientboundEntityPositionSyncPacket packet) {
        Entity entity = level.getEntity(packet.id());
        if (entity == null) return;
        Vec3 next = packet.position().endPosition();
        entity.getPositionCodec().setBase(next);
        if (entity.position().distanceToSqr(next) > 4096) entity.snapTo(next, packet.yRot(), packet.xRot());
        else entity.moveOrInterpolateTo(packet.position(), packet.yRot(), packet.xRot());
        entity.setOnGround(packet.onGround());
    }

    private void passengers(ClientboundSetPassengersPacket packet) {
        Entity vehicle = level.getEntity(packet.getVehicle());
        if (vehicle == null) return;
        int[] ids = packet.getPassengers();
        List<Entity> current = vehicle.getPassengers();
        if (current.size() == ids.length) {
            int index = 0;
            while (index < ids.length && current.get(index).getId() == ids[index]) index++;
            if (index == ids.length) return;
        }
        vehicle.ejectPassengers();
        for (int id : ids) {
            Entity rider = level.getEntity(id);
            if (rider != null && rider != vehicle) rider.startRiding(vehicle, true, false);
        }
    }

    private void attributes(ClientboundUpdateAttributesPacket packet) {
        if (!(level.getEntity(packet.getEntityId()) instanceof LivingEntity entity)) return;
        for (ClientboundUpdateAttributesPacket.AttributeSnapshot attribute : packet.getValues()) {
            AttributeInstance instance = entity.getAttributes().getInstance(attribute.attribute());
            if (instance == null) continue;
            instance.setBaseValue(attribute.base());
            instance.removeModifiers();
            for (AttributeModifier modifier : attribute.modifiers()) instance.addTransientModifier(modifier);
        }
    }

    void tick() {
        for (int id : present) {
            Entity entity = level.getEntity(id);
            if (entity == null) continue;
            entity.tickCount++;
            entity.setOldPosAndRot();
            if (entity instanceof LivingEntity living) {
                living.yHeadRotO = living.yHeadRot;
                living.yBodyRotO = living.yBodyRot;
                entity.getInterpolation().interpolate();
                living.yBodyRot = living.getYRot();
                living.calculateEntityAnimation(false);
            } else {
                entity.getInterpolation().interpolate();
            }
        }
    }

    private void remove(int id) {
        level.removeEntity(id, Entity.RemovalReason.DISCARDED);
        present.remove(id);
    }

    void clear() {
        for (int id : present) level.removeEntity(id, Entity.RemovalReason.DISCARDED);
        present.clear();
    }
}
