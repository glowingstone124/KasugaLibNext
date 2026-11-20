package lib.kasuga.core.rendering.mixin;

import lib.kasuga.rendering.output.mc.MinecraftCameras;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class CameraChunkUpdatesMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void kasuga$blockChanged(BlockPos position, BlockState oldState, BlockState newState, CallbackInfo callback) {
        if ((Object) this == Minecraft.getInstance().level) MinecraftCameras.markChunkDirty(ChunkPos.containing(position));
    }

    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
    private void kasuga$lightChanged(int x, int y, int z, CallbackInfo callback) {
        if ((Object) this == Minecraft.getInstance().level) MinecraftCameras.markChunkDirty(new ChunkPos(x, z));
    }
}
