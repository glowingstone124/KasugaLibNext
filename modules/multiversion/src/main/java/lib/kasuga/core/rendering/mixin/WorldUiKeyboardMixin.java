package lib.kasuga.core.rendering.mixin;

import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class WorldUiKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void kasuga$worldUiKey(long window, int action, KeyEvent event, CallbackInfo callback) {
        if (UIBackend.interceptKey(window, action, event)) callback.cancel();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void kasuga$worldUiCharacter(long window, CharacterEvent event, CallbackInfo callback) {
        if (UIBackend.interceptCharacter(window, event)) callback.cancel();
    }

    @Inject(method = "textEditing", at = @At("TAIL"))
    private void kasuga$worldUiPreedit(long window, @Nullable PreeditEvent event, CallbackInfo callback) {
        UIBackend.forwardPreedit(window, event);
    }
}
