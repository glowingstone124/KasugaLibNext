package test.kasuga.modelling.demo.cobalt;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

final class CobaltFrameCapture {
    private CobaltFrameCapture() {}

    static void read(Minecraft client, Consumer<NativeImage> callback) {
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), callback);
    }

    static int argb(NativeImage image, int x, int y) { return image.getPixel(x, y); }
}
