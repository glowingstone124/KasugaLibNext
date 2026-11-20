package lib.kasuga.rendering.output.mc.stream;

import lib.kasuga.KasugaLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

record CameraChunkRequest(UUID camera, long epoch, Identifier dimension, int x, int z, int radius, boolean open)
        implements CustomPacketPayload {
    static final Type<CameraChunkRequest> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "camera/chunk_request"));
    static final StreamCodec<FriendlyByteBuf, CameraChunkRequest> CODEC = StreamCodec.of((buffer, request) -> {
        buffer.writeUUID(request.camera);
        buffer.writeLong(request.epoch);
        buffer.writeIdentifier(request.dimension);
        buffer.writeInt(request.x);
        buffer.writeInt(request.z);
        buffer.writeVarInt(request.radius);
        buffer.writeBoolean(request.open);
    }, buffer -> new CameraChunkRequest(buffer.readUUID(), buffer.readLong(), buffer.readIdentifier(), buffer.readInt(),
            buffer.readInt(), buffer.readVarInt(), buffer.readBoolean()));

    boolean isValid() {
        return epoch >= 0 && radius >= 2 && radius <= 32 && Math.abs((long) x) <= 1_875_000 && Math.abs((long) z) <= 1_875_000;
    }

    boolean contains(int chunkX, int chunkZ) {
        return Math.abs((long) chunkX - x) <= radius + 1 && Math.abs((long) chunkZ - z) <= radius + 1;
    }

    @Override public Type<CameraChunkRequest> type() { return TYPE; }
}
