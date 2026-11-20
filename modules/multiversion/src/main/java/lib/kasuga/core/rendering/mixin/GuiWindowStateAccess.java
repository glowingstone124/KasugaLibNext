package lib.kasuga.core.rendering.mixin;

import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderState.class)
interface GuiWindowStateAccess {
    @Mutable
    @Accessor("windowRenderState")
    void kasuga$setGuiWindowState(WindowRenderState window);
}
