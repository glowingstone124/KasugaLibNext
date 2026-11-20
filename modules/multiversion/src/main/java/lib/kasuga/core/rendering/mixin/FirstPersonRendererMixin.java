package lib.kasuga.core.rendering.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.core.rendering.SpecialRenderingRegistry;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
abstract class FirstPersonRendererMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void kasuga$renderSpecialItem(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state,
                                          float partialTick, float pitch, InteractionHand hand, float swingProgress,
                                          ItemStack stack, float equipProgress, PoseStack pose, SubmitNodeCollector nodes,
                                          int light, CallbackInfo callback) {
        if (state.isScoping || player.avatarRenderState == null || stack.isEmpty()) return;
        if (SpecialRenderingRegistry.render((FirstPersonHandsAndItemsRenderer) (Object) this, player,
                partialTick, pitch, hand, swingProgress, stack, equipProgress, pose, nodes, light)) {
            callback.cancel();
        }
    }
}
