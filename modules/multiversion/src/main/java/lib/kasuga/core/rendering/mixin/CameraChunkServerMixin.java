package lib.kasuga.core.rendering.mixin;

import lib.kasuga.rendering.output.mc.stream.CameraChunkServer;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
abstract class CameraChunkServerMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    private void kasuga$lightChanged(LightLayer layer, SectionPos pos, CallbackInfo ci) {
        CameraChunkServer.markDirty(level, pos.chunk());
    }
}
