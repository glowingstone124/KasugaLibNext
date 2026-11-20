package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

/** Diffuse texture decoding shared by the MMD resource loaders. */
public final class MmdTextureImages {
    private MmdTextureImages() {}

    public static BufferedImage decode(String name, byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image != null) return image;
        if (!name.toLowerCase(Locale.ROOT).endsWith(".tga")) throw new IOException("Unsupported MMD texture: " + name);
        return decodeTga(bytes);
    }

    private static BufferedImage decodeTga(byte[] bytes) throws IOException {
        ByteBuffer input = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            input.put(bytes).flip();
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(input, width, height, channels, 4);
            if (pixels == null) throw new IOException("Could not decode TGA: " + STBImage.stbi_failure_reason());
            try {
                BufferedImage image = new BufferedImage(width.get(0), height.get(0), BufferedImage.TYPE_4BYTE_ABGR);
                byte[] abgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                for (int i = 0; i < abgr.length; i += 4) {
                    abgr[i] = pixels.get(i + 3);
                    abgr[i + 1] = pixels.get(i + 2);
                    abgr[i + 2] = pixels.get(i + 1);
                    abgr[i + 3] = pixels.get(i);
                }
                return image;
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        } finally {
            MemoryUtil.memFree(input);
        }
    }
}
