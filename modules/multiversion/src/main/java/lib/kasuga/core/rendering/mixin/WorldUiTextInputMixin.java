package lib.kasuga.core.rendering.mixin;

import com.mojang.blaze3d.platform.TextInputManager;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextInputManager.class)
abstract class WorldUiTextInputMixin {
    @Unique private boolean kasuga$projecting;

    @Shadow public abstract void setTextInputArea(int x0, int y0, int x1, int y1);

    @Inject(method = "setTextInputArea", at = @At("HEAD"), cancellable = true)
    private void kasuga$projectCandidateWindow(int x0, int y0, int x1, int y1, CallbackInfo callback) {
        if (kasuga$projecting) return;
        ScreenRectangle screen = UIBackend.projectTextInputArea(x0, y0, x1, y1);
        if (screen == null) return;
        kasuga$projecting = true;
        try {
            setTextInputArea(screen.left(), screen.top(), screen.right(), screen.bottom());
        } finally {
            kasuga$projecting = false;
        }
        callback.cancel();
    }
}
