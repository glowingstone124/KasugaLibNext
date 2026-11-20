package lib.kasuga.rendering.models.uml.structure.material.data;

import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
/**
 * Format-neutral alpha classification consumed by the Minecraft renderer.
 *
 * <p>Keeping this contract in the model layer lets loaders retain their
 * format-specific metadata while the backend applies one consistent pass
 * policy.</p>
 */
public enum MaterialAlphaMode {
    /** The material is treated as fully opaque regardless of sampled alpha. */
    OPAQUE,
    /** Alpha is tested against {@link MaterialData#alphaCutoff()} and retained fragments write depth. */
    MASK,
    /** Conventional source-over blending; geometry is rendered back-to-front. */
    BLEND;

    /** Infers the render pass from decoded texture alpha. */
    public static MaterialAlphaMode fromImage(@Nullable BufferedImage image) {
        if (image == null || !image.getColorModel().hasAlpha()) return OPAQUE;
        boolean transparent = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha == 255) continue;
                if (alpha != 0) return BLEND;
                transparent = true;
            }
        }
        return transparent ? MASK : OPAQUE;
    }
}
