package lib.kasuga.core.rendering.mixin;

import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(GameRenderer.class)
abstract class CloudOptionsMixin {
    @Redirect(method = "extractOptions", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCloudStatus()Lnet/minecraft/client/CloudStatus;"))
    private CloudStatus kasuga$extractCloudStatus(Options options) {
        return CloudEffects.isEnabled() && CloudEffects.isSkyEnabled() ? CloudStatus.OFF : options.getCloudStatus();
    }
}
