package lib.kasuga.content.document;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

final class DocumentLayout {
    private DocumentLayout() {}

    static void oneHandPage(PoseStack pose, float side, float equip, float swing) {
        pose.translate(side * .51f, -.08f - equip * 1.2f, -.75f);
        float swingRoot = Mth.sqrt(swing);
        float swingSin = Mth.sin(swingRoot * Mth.PI);
        pose.translate(side * -.5f * swingSin, .4f * Mth.sin(swingRoot * Mth.TWO_PI) - .3f * swingSin,
                -.3f * Mth.sin(swing * Mth.PI));
        rotate(pose, Axis.XP, swingSin * -45f);
        rotate(pose, Axis.YP, side * swingSin * -30f);
    }

    static void twoHandPose(PoseStack pose, float pitch, float equip, float swing) {
        float swingSin = Mth.sin(Mth.sqrt(swing) * Mth.PI);
        float tilt = -.5f * Mth.cos(Mth.clamp(1.1f - pitch / 45f, 0f, 1f) * Mth.PI) + .5f;
        pose.translate(0f, .1f * Mth.sin(swing * Mth.PI), -.4f * swingSin);
        pose.translate(0f, .04f - equip * 1.2f - .5f * tilt, -.72f);
        rotate(pose, Axis.XP, tilt * -85f);
    }

    static void twoHandPage(PoseStack pose, float swing) {
        rotate(pose, Axis.XP, Mth.sin(Mth.sqrt(swing) * Mth.PI) * 20f);
        pose.scale(2f, 2f, 2f);
    }

    static void pageCoordinates(PoseStack pose) {
        rotate(pose, Axis.YP, 180f);
        rotate(pose, Axis.ZP, 180f);
        pose.scale(.38f, .38f, .38f);
        pose.translate(-.5f, -.5f, 0f);
        pose.scale(1f / 128f, 1f / 128f, 1f / 128f);
    }

    private static void rotate(PoseStack pose, Axis axis, float degrees) {
        pose.mulPose(new Matrix4f().rotation(axis.rotationDegrees(degrees)));
    }
}
