package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;

/**
 * Alpha contract inferred from a Blockbench texture.
 *
 * <p>Native BBModel texture entries commonly leave {@code render_mode} at {@code default}, so that
 * field cannot distinguish opaque and cutout content. Inspect the decoded image instead: transparent
 * texels must discard or blend or they render as black holes.
 *
 * @param alphaMode renderer-facing alpha mode
 * @param alphaCutoff cutout threshold
 */
record BbModelMaterialData(MaterialAlphaMode alphaMode, float alphaCutoff) implements MaterialData {

    static final float DEFAULT_ALPHA_CUTOFF = 0.5f;

    static BbModelMaterialData from(@Nullable BufferedImage image) {
        return new BbModelMaterialData(MaterialAlphaMode.fromImage(image), DEFAULT_ALPHA_CUTOFF);
    }
}
