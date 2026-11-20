package lib.kasuga.rendering.output.mc.stream;

import lib.kasuga.KasugaLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.Identifier;

import java.util.UUID;

record CameraEntityPayload(UUID camera, long epoch, byte[] packets) implements CustomPacketPayload {
    static final int MAX_BYTES = 700_000;
    static final Type<CameraEntityPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "camera/entity"));
    static final StreamCodec<FriendlyByteBuf, CameraEntityPayload> CODEC = StreamCodec.of((buffer, payload) -> {
        buffer.writeUUID(payload.camera);
        buffer.writeLong(payload.epoch);
        buffer.writeByteArray(payload.packets);
    }, buffer -> new CameraEntityPayload(buffer.readUUID(), buffer.readLong(), buffer.readByteArray(MAX_BYTES)));

    static boolean allowed(Packet<?> packet) {
        return packet instanceof ClientboundAddEntityPacket || packet instanceof ClientboundEntityPositionSyncPacket
                || packet instanceof ClientboundRemoveEntitiesPacket || packet instanceof ClientboundRotateHeadPacket
                || packet instanceof ClientboundSetEntityDataPacket || packet instanceof ClientboundSetEntityLinkPacket
                || packet instanceof ClientboundSetEquipmentPacket || packet instanceof ClientboundSetPassengersPacket
                || packet instanceof ClientboundUpdateAttributesPacket;
    }

    @Override public Type<CameraEntityPayload> type() { return TYPE; }
}
