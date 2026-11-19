package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

final class RenderPearlModelGeometry {
    private RenderPearlModelGeometry() {}

    static List<Batch> extract(ModelInstance model, Function<Texture, Identifier> textures, int light) {
        Map<RenderType, List<Corner>> groups = new LinkedHashMap<>();
        Map<Material, Surface> surfaces = new IdentityHashMap<>();
        List<BoneContext> boneContexts = new ArrayList<>();
        for (Mesh mesh : model.getModel().getMeshes()) {
            Vertex[] vertices = mesh.getVertices();
            if (!mesh.isVisible() || vertices.length < 3) continue;
            for (Material material : mesh.getMaterials()) {
                Surface surface = surfaces.computeIfAbsent(material, key -> surface(model, key, textures, light));
                List<Corner> group = groups.computeIfAbsent(surface.layer(), key -> new ArrayList<>());
                List<Corner> face = new ArrayList<>(vertices.length);
                for (Vertex vertex : vertices) face.add(corner(model, mesh, vertex, surface, boneContexts));
                appendFace(group, face);
                if (!surface.sprite().culled && surface.layer().pipeline().isCull()) {
                    appendFace(group, face.reversed());
                }
            }
        }
        List<Batch> batches = new ArrayList<>(groups.size());
        groups.forEach((layer, corners) -> batches.add(new Batch(layer, List.copyOf(corners))));
        return List.copyOf(batches);
    }

    private static void appendFace(List<Corner> group, List<Corner> face) {
        if (face.size() == 4) {
            group.addAll(face);
            return;
        }
        // Entity render types consume quads. The second triangle has zero area.
        for (int i = 2; i < face.size(); i++) {
            group.add(face.getFirst());
            group.add(face.get(i - 1));
            group.add(face.get(i));
            group.add(face.get(i));
        }
    }

    private static Surface surface(ModelInstance model, Material material, Function<Texture, Identifier> textures, int light) {
        Sprite sprite = model.getMaterialSprite(material);
        Identifier texture = Objects.requireNonNull(textures.apply(sprite.getTexture()), "texture identifier");
        MaterialData materialData = material.getData();
        MaterialAlphaMode alpha = materialData == null ? MaterialAlphaMode.OPAQUE : materialData.alphaMode();
        RenderType layer = switch (alpha) {
            case OPAQUE -> RenderTypes.entitySolid(texture);
            case MASK -> sprite.culled ? RenderTypes.entityCutoutCull(texture) : RenderTypes.entityCutout(texture);
            case BLEND -> sprite.culled ? RenderTypes.entityTranslucentCull(texture) : RenderTypes.entityTranslucent(texture);
        };
        Vector4f color = new Vector4f(1f);
        model.getMaterialColor(material, sprite, color);
        return new Surface(material, sprite, layer, color, sprite.emissive ? LightCoordsUtil.FULL_BRIGHT : light);
    }

    private static Corner corner(ModelInstance model, Mesh mesh, Vertex vertex, Surface surface,
                                 List<BoneContext> boneContexts) {
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        Vector2f uv = new Vector2f();
        model.getVertexPosition(vertex, position);
        model.getVertexNormal(vertex, mesh, normal);
        model.getVertexUv(vertex, mesh, surface.material(), uv);
        BoneBindingFunc skin = vertex.getBinding().getFunc();
        if (skin != BoneBindingFunc.IDENTITY && vertex.getBinding().getWeights().length != 0) {
            model.getSkeletonInstance().collectBoneContexts(boneContexts, vertex);
            HashMap<Mesh, Vector3f> normals = new HashMap<>();
            normals.put(mesh, normal);
            Vertex posed = skin.apply(new Vertex(vertex, position, normals), boneContexts);
            position = posed.getPosition();
            normal = posed.getNormal(mesh);
        }
        if (!normal.isFinite() || normal.lengthSquared() == 0f) normal.set(0f, 1f, 0f);
        Sprite sprite = surface.sprite();
        float u = sprite.flipU ? 1f - uv.x : uv.x;
        float v = sprite.flipV ? 1f - uv.y : uv.y;
        Vector2f mapped = new Vector2f(sprite.getUv0()).lerp(sprite.getUv1(), u)
                .lerp(new Vector2f(sprite.getUv3()).lerp(sprite.getUv2(), u), v);
        return new Corner(position, normal, mapped, surface.color(), surface.light());
    }

    record Batch(RenderType layer, List<Corner> corners) {
        void submit(PoseStack.Pose pose, VertexConsumer consumer) {
            for (Corner corner : corners) {
                Vector4f color = corner.color();
                consumer.addVertex(pose, corner.position())
                        .setColor(color.x, color.y, color.z, color.w)
                        .setUv(corner.uv().x, corner.uv().y)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(corner.light())
                        .setNormal(pose, corner.normal());
            }
        }
    }

    private record Surface(Material material, Sprite sprite, RenderType layer, Vector4f color, int light) {}
    private record Corner(Vector3f position, Vector3f normal, Vector2f uv, Vector4f color, int light) {}
}
