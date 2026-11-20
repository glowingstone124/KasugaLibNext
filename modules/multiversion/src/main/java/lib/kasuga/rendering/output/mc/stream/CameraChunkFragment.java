package lib.kasuga.rendering.output.mc.stream;

import lib.kasuga.KasugaLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Each fragment stays below the play payload limit. One chunk is assembled at a time per camera. */
record CameraChunkFragment(UUID camera, long epoch, int x, int z, int index, int count, byte[] bytes)
        implements CustomPacketPayload {
    static final int PART_SIZE = 700_000;
    static final int MAX_PARTS = 4;
    static final Type<CameraChunkFragment> TYPE = new Type<>(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "camera/chunk"));
    static final StreamCodec<FriendlyByteBuf, CameraChunkFragment> CODEC = StreamCodec.of((buffer, fragment) -> {
        buffer.writeUUID(fragment.camera);
        buffer.writeLong(fragment.epoch);
        buffer.writeInt(fragment.x);
        buffer.writeInt(fragment.z);
        buffer.writeVarInt(fragment.index);
        buffer.writeVarInt(fragment.count);
        buffer.writeByteArray(fragment.bytes);
    }, buffer -> new CameraChunkFragment(buffer.readUUID(), buffer.readLong(), buffer.readInt(), buffer.readInt(),
            buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(PART_SIZE)));

    boolean isValid() {
        return count >= 1 && count <= MAX_PARTS && index >= 0 && index < count && bytes.length > 0
                && (index == count - 1 || bytes.length == PART_SIZE);
    }

    @Override public Type<CameraChunkFragment> type() { return TYPE; }
}
