package test.kasuga.modelling.demo.cobalt;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

final class CobaltFrameCapture {
    private CobaltFrameCapture() {}

    static void read(Minecraft client, Consumer<NativeImage> callback) {
        callback.accept(Screenshot.takeScreenshot(client.getMainRenderTarget()));
    }

    static int argb(NativeImage image, int x, int y) {
        int abgr = image.getPixelRGBA(x, y);
        return (abgr & 0xff00ff00) | ((abgr & 255) << 16) | ((abgr >>> 16) & 255);
    }
}
