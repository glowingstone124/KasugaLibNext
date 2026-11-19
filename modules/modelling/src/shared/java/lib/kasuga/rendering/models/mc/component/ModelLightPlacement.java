package lib.kasuga.rendering.models.mc.component;

import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Resolves the current skinned anchor before applying an external model placement. */
public final class ModelLightPlacement {
    private ModelLightPlacement() {}

    public static @Nullable LightFrame sample(SkeletonInstance skeleton, ModelComponents.LightDefinition light,
                                               Matrix4dc modelPlacement) {
        Vector3d origin = skeleton.getWorldOrigin();
        Transform anchor = skeleton.anchorTransformRelative(light.anchorName(), origin);
        if (anchor == null) return null;
        Matrix4f relative = anchor.transform();
        if (!relative.isFinite() || relative.determinant() == 0) return null;
        Matrix4d transform = new Matrix4d(modelPlacement).translate(origin).mul(new Matrix4d(relative));
        return new LightFrame(light.source(), transform);
    }
}
