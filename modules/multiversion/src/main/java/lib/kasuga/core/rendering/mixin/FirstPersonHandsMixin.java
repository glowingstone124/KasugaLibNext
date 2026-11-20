package lib.kasuga.core.rendering.mixin;

import lib.kasuga.core.rendering.ISpecialRenderingItem;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState.HandRenderSelection;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FirstPersonHandsAndItems.class)
abstract class FirstPersonHandsMixin {
    @Inject(method = "evaluateWhichHandsToRender", at = @At("HEAD"), cancellable = true)
    private static void kasuga$selectHands(LocalPlayer player, CallbackInfoReturnable<HandRenderSelection> callback) {
        int main = priority(player, InteractionHand.MAIN_HAND);
        int off = priority(player, InteractionHand.OFF_HAND);
        if (main == off) return;
        callback.setReturnValue(main > off
                ? HandRenderSelection.RENDER_MAIN_HAND_ONLY : HandRenderSelection.RENDER_OFF_HAND_ONLY);
    }

    private static int priority(LocalPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.getItem() instanceof ISpecialRenderingItem item) return item.getRenderPriority(stack, player, hand);
        return 0;
    }
}
