package lib.kasuga.registration.minecraft.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jetbrains.annotations.Nullable;

final class PayloadPlatform {
    private PayloadPlatform() {}

    static <T extends CustomPacketPayload> void register(PayloadRegistrar registrar, PayloadStage stage,
            CustomPacketPayload.Type<T> type, StreamCodec<FriendlyByteBuf, T> codec,
            @Nullable IPayloadHandler<T> serverHandler, @Nullable IPayloadHandler<T> clientHandler) {
        if(serverHandler != null && clientHandler != null) {
            switch (stage) {
                case PLAY -> registrar.playBidirectional(type, codec, serverHandler, clientHandler);
                case CONFIGURATION -> registrar.configurationBidirectional(type, codec, serverHandler, clientHandler);
                case COMMON -> registrar.commonBidirectional(type, codec, serverHandler, clientHandler);
                default -> throw new IllegalStateException("Unexpected value: " + stage);
            }
        } else if(serverHandler != null) {
            switch (stage) {
                case PLAY -> registrar.playToServer(type, codec, serverHandler);
                case CONFIGURATION -> registrar.configurationToServer(type, codec, serverHandler);
                case COMMON -> registrar.commonToServer(type, codec, serverHandler);
                default -> throw new IllegalStateException("Unexpected value: " + stage);
            }
        } else if(clientHandler != null) {
            switch (stage) {
                case PLAY -> registrar.playToClient(type, codec, clientHandler);
                case CONFIGURATION -> registrar.configurationToClient(type, codec, clientHandler);
                case COMMON -> registrar.commonToClient(type, codec, clientHandler);
                default -> throw new IllegalStateException("Unexpected value: " + stage);
            }
        }
    }
}
