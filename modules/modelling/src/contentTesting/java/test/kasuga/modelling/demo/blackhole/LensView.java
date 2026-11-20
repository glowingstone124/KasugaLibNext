package test.kasuga.modelling.demo.blackhole;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

record LensView(Matrix4f projection, Matrix4f inverseProjection, Vector3f normal, Vector3f tangent,
                Vector3f bitangent, float radius, float time, List<Lens> lenses) {
    static LensView prepare(BlackHoleDemo.Scene scene, float time, Vec3 eye, Matrix4fc rotation,
                            Matrix4fc projection, int width, int height) {
        float radius = scene.radius();
        List<Lens> visible = new ArrayList<>(scene.count());
        for (int index = 0; index < scene.count(); index++) {
            Vec3 offset = scene.position(index, time).subtract(eye);
            Vector3f center = rotation.transformDirection(vector(offset));
            Bounds bounds = bounds(center, radius * 9.0f, projection, width, height);
            if (bounds != null) visible.add(new Lens(center, bounds));
        }
        if (visible.isEmpty()) return null;
        Vector3f normal = rotation.transformDirection(vector(scene.normal())).normalize();
        Vector3f tangent = rotation.transformDirection(vector(scene.right())).normalize();
        return new LensView(new Matrix4f(projection), projection.invert(new Matrix4f()), normal, tangent,
                normal.cross(tangent, new Vector3f()).normalize(), radius, time, visible);
    }

    private static Vector3f vector(Vec3 vector) {
        return new Vector3f((float) vector.x, (float) vector.y, (float) vector.z);
    }

    private static Bounds bounds(Vector3f center, float radius, Matrix4fc projection, int width, int height) {
        if (center.z - radius >= 0.0f) return null;
        Vector4f clip = projection.transform(new Vector4f(center, 1f));
        Vector3f denominator = new Vector3f(projection.m03(), projection.m13(), projection.m23());
        if (clip.w <= radius * denominator.length()) return new Bounds(0, 0, width, height);
        float radiusSquared = radius * radius;
        float quadratic = clip.w * clip.w - radiusSquared * denominator.lengthSquared();
        Range x = projectedRange(clip.x, clip.w, new Vector3f(projection.m00(), projection.m10(), projection.m20()),
                denominator, radiusSquared, quadratic);
        Range y = projectedRange(clip.y, clip.w, new Vector3f(projection.m01(), projection.m11(), projection.m21()),
                denominator, radiusSquared, quadratic);
        int left = Math.max(0, (int) Math.floor((x.min() * .5f + .5f) * width));
        int bottom = Math.max(0, (int) Math.floor((y.min() * .5f + .5f) * height));
        int right = Math.min(width, (int) Math.ceil((x.max() * .5f + .5f) * width));
        int top = Math.min(height, (int) Math.ceil((y.max() * .5f + .5f) * height));
        if (left >= right || bottom >= top) return null;
        return new Bounds(left, bottom, right - left, top - bottom);
    }

    private static Range projectedRange(float clipCoordinate, float clipW, Vector3f numerator,
                                        Vector3f denominator, float radiusSquared, float quadratic) {
        // Extremal projected coordinates are the two planes tangent to the sphere.
        float halfLinear = clipCoordinate * clipW - radiusSquared * numerator.dot(denominator);
        float constant = clipCoordinate * clipCoordinate - radiusSquared * numerator.lengthSquared();
        float root = (float) Math.sqrt(Math.max(0, halfLinear * halfLinear - quadratic * constant));
        return new Range((halfLinear - root) / quadratic, (halfLinear + root) / quadratic);
    }

    private record Range(float min, float max) {}

    record Lens(Vector3f center, Bounds bounds) {}
    record Bounds(int x, int y, int width, int height) {}
}
