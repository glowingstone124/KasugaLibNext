package lib.kasuga.rendering.models.mc.util;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public final class PlaneRayIntersection {
    private PlaneRayIntersection() {}

    /** Returns the ray distance and writes the plane hit, or returns -1 when there is no hit. */
    public static float intersect(Vector3f rayOrigin, Vector3f rayDirection, Matrix4f transform, Matrix3f normal,
                                  Vector3f dstHitPoint, boolean ignoreBackface) {
        Vector3f planeOrigin = new Vector3f(transform.m30(), transform.m31(), transform.m32());
        Vector3f planeNormal = new Vector3f(0, 0, 1);
        normal.transform(planeNormal);

        float denom = planeNormal.dot(rayDirection);
        if (Math.abs(denom) < 1e-6) {
            return -1f; // Ray is parallel to the plane
        }

        Vector3f diff = new Vector3f(planeOrigin).sub(rayOrigin);
        float t = diff.dot(planeNormal) / denom;

        if (t < 0 && ignoreBackface) {
            return -1f; // Intersection is behind the ray origin
        }

        dstHitPoint.set(rayOrigin).fma(t, rayDirection);
        return Math.abs(t);
    }
}
