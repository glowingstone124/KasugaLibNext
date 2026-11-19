package lib.kasuga.core.resource.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.resource.ServerResourceReloadFinishEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

@Mixin(MinecraftServer.class)
final class ServerResourceReloadMixin {
    @WrapOperation(method = "reloadResources", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;thenAcceptAsync(Ljava/util/function/Consumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private <T> CompletableFuture<Void> kasuga$publishResourceReload(
            CompletableFuture<T> future, Consumer<? super T> apply, Executor executor,
            Operation<CompletableFuture<Void>> original) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        Consumer<T> publish = replacement -> {
            apply.accept(replacement);
            KasugaLib.getBean(ResourceSystem.class).onServerReloading(server);
            NeoForge.EVENT_BUS.post(new ServerResourceReloadFinishEvent());
        };
        return original.call(future, publish, executor);
    }
}
