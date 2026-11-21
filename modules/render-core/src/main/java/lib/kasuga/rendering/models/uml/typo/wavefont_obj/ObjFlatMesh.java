package lib.kasuga.rendering.models.uml.typo.wavefont_obj;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Interleaved triangles from the standard OBJ and MTL processors, for custom material pipelines. */
public record ObjFlatMesh(float[] vertices, int triangleCount, int vertexCount,
                          float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                          Map<String, ObjTextureData> materials) {
    /** Position3, normal3, UV2, diffuse RGBA, emission, roughness, material kind and padding. */
    public static final int FLOATS_PER_VERTEX = 16;

    public static ObjFlatMesh load(String objSource, String mtlSource, Function<String, Float> materialKind) {
        return new Loader(mtlSource, materialKind).read(objSource);
    }

    private static final class Loader extends ObjModelLoader<String, String, String> {
        private final String materialSource;
        private final Function<String, Float> materialKind;
        private final Map<String, ObjTextureData> materials = new LinkedHashMap<>();
        private final List<Triangle> triangles = new ArrayList<>();
        private ObjFlatMesh result;

        Loader(String materialSource, Function<String, Float> materialKind) {
            super("flat_obj", true, true, true);
            this.materialSource = materialSource;
            this.materialKind = materialKind;
        }

        ObjFlatMesh read(String objSource) {
            load("flat_mesh", objSource);
            return result;
        }

        @Override public String getTextureContent(String ignored) { return materialSource; }
        @Override public void consumeTexture(ObjTextureData material) { materials.put(material.getName(), material); }

        @Override public void beforeProcessors(SerialContext<ObjContextData> context) {
            getMtlLoader().readMtlFile(materialSource);
        }

        @Override public void endHighestGroup(SerialContext<ObjContextData> context) {
            while (!context.isEmpty()) {
                ObjContextData group = context.pop();
                for (int i = 0; i < group.getBindings().size(); i++) {
                    List<ObjVertexBinding> corners = group.getBindings().get(i);
                    if (corners.size() < 3) throw new IllegalArgumentException("OBJ surface requires at least three corners");
                    String material = group.getBindingMaterials().get(i);
                    for (int j = 1; j + 1 < corners.size(); j++) {
                        triangles.add(new Triangle(corners.getFirst(), corners.get(j), corners.get(j + 1), material));
                    }
                }
                if (group.isGroup()) break;
            }
        }

        @Override public void build(HashMap<String, Model> unused) {
            while (!getContext().isEmpty()) endHighestGroup(getContext());
            if (triangles.isEmpty()) throw new IllegalArgumentException("OBJ contains no surfaces");
            int vertexCount = Math.multiplyExact(triangles.size(), 3);
            float[] vertices = new float[Math.multiplyExact(vertexCount, FLOATS_PER_VERTEX)];
            Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
            Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
            int out = 0;
            for (Triangle triangle : triangles) {
                ObjTextureData material = materials.get(triangle.material());
                if (material == null) throw new IllegalArgumentException("Undefined OBJ material: " + triangle.material());
                Vector3f a = getObjVertexPositions().get(triangle.a().vertexIndex());
                Vector3f b = getObjVertexPositions().get(triangle.b().vertexIndex());
                Vector3f c = getObjVertexPositions().get(triangle.c().vertexIndex());
                Vector3f faceNormal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
                if (faceNormal.lengthSquared() < 1e-20f) throw new IllegalArgumentException("Degenerate OBJ surface");
                faceNormal.normalize();
                for (ObjVertexBinding corner : List.of(triangle.a(), triangle.b(), triangle.c())) {
                    Vector3f position = getObjVertexPositions().get(corner.vertexIndex());
                    Vector3f normal = normal(corner, faceNormal);
                    Vector2f uv = corner.textureIndex() < 0 ? new Vector2f() : getObjVertexUvs().get(corner.textureIndex());
                    if (!position.isFinite() || !normal.isFinite() || !uv.isFinite()) {
                        throw new IllegalArgumentException("Non-finite OBJ vertex attributes");
                    }
                    vertices[out++] = position.x;
                    vertices[out++] = position.y;
                    vertices[out++] = position.z;
                    vertices[out++] = normal.x;
                    vertices[out++] = normal.y;
                    vertices[out++] = normal.z;
                    vertices[out++] = uv.x;
                    vertices[out++] = uv.y;
                    vertices[out++] = material.getKd().x;
                    vertices[out++] = material.getKd().y;
                    vertices[out++] = material.getKd().z;
                    vertices[out++] = material.getD();
                    vertices[out++] = material.getKe().length();
                    vertices[out++] = material.getPr();
                    vertices[out++] = materialKind == null ? 0 : materialKind.apply(triangle.material());
                    vertices[out++] = 0;
                    min.min(position);
                    max.max(position);
                }
            }
            result = new ObjFlatMesh(vertices, triangles.size(), vertexCount,
                    min.x, min.y, min.z, max.x, max.y, max.z, Map.copyOf(materials));
            clear();
        }

        private Vector3f normal(ObjVertexBinding corner, Vector3f faceNormal) {
            if (corner.normalIndex() >= 0) {
                Vector3f normal = getObjVertexNormals().get(corner.normalIndex());
                if (normal != null && normal.isFinite() && normal.lengthSquared() > 1e-12f) {
                    return new Vector3f(normal).normalize();
                }
            }
            return faceNormal;
        }

        @Override public String getAsString(String input) { return input; }
        @Override public boolean isValidInput(Object input) { return input instanceof String; }
        @Override public String getIdentifier(ObjModelLoader loader, String input) { return "flat_mesh"; }
        @Override public VertexData getVertexData(ObjContextData data, ObjModelLoader loader, String bone) { return null; }
        @Override public MeshData getMeshData(ObjContextData data, ObjModelLoader loader) { return null; }
        @Override public BoneData getBoneData(ObjModelLoader loader, String bone) { return null; }
        @Override public BoneBindingData getBoneBindingData(ObjModelLoader loader, Bone bone, Vertex vertex) { return null; }
        @Override public SkeletonData getSkeletonData(ObjModelLoader loader, Bone[] bones) { return null; }
        @Override public ModelData getModelData(ObjModelLoader loader) { return null; }
        @Override public Anchor[] getAnchors(ObjModelLoader loader, Bone[] bones, Bone root) { return new Anchor[0]; }
        @Override public Texture loadTexture(Object identifier) { return null; }
        @Override public Texture getTexture(ObjContextData data, ObjModelLoader loader, String material) {
            throw new UnsupportedOperationException("Flat meshes export texture definitions");
        }
        @Override public Material getMaterial(ObjContextData data, ObjModelLoader loader, String material) {
            throw new UnsupportedOperationException("Flat meshes export material definitions");
        }
    }

    private record Triangle(ObjVertexBinding a, ObjVertexBinding b, ObjVertexBinding c, String material) {}
}
