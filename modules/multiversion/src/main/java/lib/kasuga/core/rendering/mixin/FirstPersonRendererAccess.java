package lib.kasuga.core.rendering.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public interface FirstPersonRendererAccess {
    @Invoker("renderPlayerArm")
    void kasuga$renderPlayerArm(PoseStack pose, SubmitNodeCollector nodes, int light, float equipProgress,
                               float swingProgress, HumanoidArm side, PlayerRenderState player);

    @Invoker("renderMapHand")
    void kasuga$renderMapHand(PoseStack pose, SubmitNodeCollector nodes, int light,
                             HumanoidArm side, PlayerRenderState player);
}
