package lib.kasuga.core.rendering.mixin;

import lib.kasuga.rendering.output.mc.stream.CameraChunkServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
abstract class CameraLevelChunkMixin {
    @Shadow public abstract Level getLevel();

    // The normal block broadcast skips FULL chunks outside the simulation distance.
    @Inject(method = "markUnsaved", at = @At("TAIL"))
    private void kasuga$chunkChanged(CallbackInfo ci) {
        if (getLevel() instanceof ServerLevel level) {
            CameraChunkServer.markDirty(level, ((LevelChunk) (Object) this).getPos());
        }
    }
}
