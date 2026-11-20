package lib.kasuga.host;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.core.networking.rpc.Rpc;
import lib.kasuga.core.networking.rpc.RpcApi;
import lib.kasuga.core.networking.rpc.RpcTimeoutException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Context
@Requires(property = "kasuga.graphicsSmoke")
public final class RpcSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Rpc.RpcR2<Integer, Integer, Boolean> bidirectional;
    private final Rpc.RpcV1<Integer> oneWay;
    private final SilentRpc silent = new SilentRpc();
    private final CompletableFuture<Integer> clientReply = new CompletableFuture<>();
    private CompletableFuture<Integer> serverReply;
    private CompletableFuture<Integer> serverError;
    private CompletableFuture<Integer> timeout;
    private volatile int received;
    private volatile boolean silentReceived;
    private boolean verified;

    public RpcSmoke() {
        bidirectional = Rpc.wrap("smoke_echo", RpcSmoke::echo, ByteBufCodecs.VAR_INT,
                        ByteBufCodecs.VAR_INT, ByteBufCodecs.BOOL)
                .executeOnServer().executeOnClient().runOnMainThread().setTimeoutSeconds(5)
                .register(KasugaLibApplication.REGISTRY);
        oneWay = Rpc.wrap("smoke_notify", this::notifyServer, ByteBufCodecs.VAR_INT)
                .executeOnServer().runOnMainThread().register(KasugaLibApplication.REGISTRY);
        silent.executeOnServer().setTimeoutMillis(150).register(KasugaLibApplication.REGISTRY);
    }

    private static int echo(IPayloadContext context, int value, boolean negate) {
        boolean server = context.player() instanceof ServerPlayer;
        boolean mainThread = server ? context.player().level().getServer().isSameThread()
                : Minecraft.getInstance().isSameThread();
        if (!mainThread) throw new IllegalStateException("RPC handler ran outside the game thread");
        if (value < 0) throw new IllegalArgumentException();
        return (negate ? -value : value) + (server ? 2 : 8);
    }

    private void notifyServer(IPayloadContext context, int value) {
        if (!(context.player() instanceof ServerPlayer)) throw new IllegalStateException("One-way RPC reached the wrong side");
        received = value;
    }

    static boolean ready(Minecraft client) {
        return KasugaLib.getBean(RpcSmoke.class).check(client);
    }

    private boolean check(Minecraft client) {
        if (verified) return true;
        if (serverReply == null) {
            serverReply = bidirectional.call(null, 37, false);
            serverError = bidirectional.call(null, -1, false);
            timeout = silent.run(91);
            oneWay.call(null, 67);
            IntegratedServer server = client.getSingleplayerServer();
            UUID id = client.player.getUUID();
            server.execute(() -> bidirectional.call(server.getPlayerList().getPlayer(id), 20, true)
                    .whenComplete((value, failure) -> {
                        if (failure == null) clientReply.complete(value);
                        else clientReply.completeExceptionally(failure);
                    }));
        }
        silent.tick();
        bidirectional.tick();
        if (!serverReply.isDone() || !clientReply.isDone() || !serverError.isDone() || !timeout.isDone()
                || received != 67 || !silentReceived) return false;
        if (serverReply.join() != 39 || clientReply.join() != -12
                || !"IllegalArgumentException".equals(failureOf(serverError).getMessage())
                || !(failureOf(timeout) instanceof RpcTimeoutException)) {
            throw new IllegalStateException("RPC response, error or timeout was not delivered as expected");
        }
        verified = true;
        LOGGER.info("KASUGA_RPC_PASS generated,client-to-server,server-to-client,one-way,error,timeout");
        return true;
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (CompletionException expected) {
            return expected.getCause();
        }
        throw new IllegalStateException("RPC unexpectedly completed successfully");
    }

    private final class SilentRpc extends RpcApi.WithResponse<Integer, Integer, SilentRpc> {
        private SilentRpc() { super("smoke_silent"); }
        @Override protected StreamCodec<? super FriendlyByteBuf, Integer> getRequestPayloadCodec() { return ByteBufCodecs.VAR_INT; }
        @Override protected StreamCodec<? super FriendlyByteBuf, Integer> getResponsePayloadCodec() { return ByteBufCodecs.VAR_INT; }
        @Override public CompletableFuture<Integer> handle(Integer request, IPayloadContext context) {
            silentReceived = request == 91;
            return new CompletableFuture<>();
        }
    }
}
