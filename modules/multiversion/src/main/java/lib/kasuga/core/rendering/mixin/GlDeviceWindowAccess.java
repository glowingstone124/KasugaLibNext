package lib.kasuga.core.rendering.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice", remap = false)
public interface GlDeviceWindowAccess {
    @Invoker("makeCurrent") void kasuga$makeCurrent(long window);
}
