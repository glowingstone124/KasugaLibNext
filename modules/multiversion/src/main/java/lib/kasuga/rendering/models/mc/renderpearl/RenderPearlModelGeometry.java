package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import lib.kasuga.rendering.models.mc.java_and_bedrock.data.MCMeshData;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSkin.Palette;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.ColorizedMeshData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
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

    static List<Batch> capture(ModelInstance model, Function<Texture, Identifier> textures, int light,
                               float partialTick, Object frameToken) {
        return capture(model, textures, light, partialTick, frameToken, false);
    }

    static List<Batch> capture(ModelInstance model, Function<Texture, Identifier> textures, int light,
                               float partialTick, Object frameToken, boolean world) {
        model.sample(partialTick, frameToken);
        model.prepareRenderFrame(frameToken);
        Map<RenderType, List<Corner>> groups = new LinkedHashMap<>();
        Map<Material, Surface> surfaces = new IdentityHashMap<>();
        for (Mesh mesh : model.getModel().getMeshes()) {
            Vertex[] vertices = mesh.getVertices();
            if (!mesh.isVisible() || vertices.length < 3) continue;
            MCMeshData nativeFace = mesh.getData() instanceof MCMeshData data ? data : null;
            if (nativeFace != null && !nativeFace.isVisible()) continue;
            for (Material material : mesh.getMaterials()) {
                Surface surface = surfaces.computeIfAbsent(material, key -> surface(model, key, textures));
                Vector4f color = surface.color();
                if (mesh.getData() instanceof ColorizedMeshData tinted && tinted.getColor() != null) {
                    color = new Vector4f(color).mul(tinted.getColor());
                }
                boolean emissive = surface.sprite().emissive || nativeFace != null && nativeFace.isEmissive();
                List<Corner> group = groups.computeIfAbsent(surface.layer(), key -> new ArrayList<>());
                List<Corner> face = new ArrayList<>(vertices.length);
                for (Vertex vertex : vertices) {
                    face.add(corner(model, mesh, vertex, surface, color, emissive));
                }
                appendFace(group, face);
                if (!surface.sprite().culled && surface.layer().pipeline().isCull()) {
                    appendFace(group, face.reversed());
                }
            }
        }
        SkeletonInstance skeleton = model.getSkeletonInstance();
        List<BoneContext> boneContexts = new ArrayList<>();
        Palette palette = null;
        List<Batch> batches = new ArrayList<>(groups.size());
        for (Map.Entry<RenderType, List<Corner>> entry : groups.entrySet()) {
            RenderType layer = entry.getKey();
            List<Corner> corners = entry.getValue();
            if (corners.isEmpty()) continue;
            Bone rigidBone = commonRigidBone(corners);
            boolean hasBindings = false;
            boolean canSkin = world && RenderPearlModelBatcher.supports(layer)
                    && skeleton.getSkeleton().getBones().length <= RenderPearlModelSkin.MAX_BONES;
            for (Corner corner : corners) {
                if (corner.bound() == null) continue;
                hasBindings = true;
                if (!RenderPearlModelSkin.canRepresent(corner.bound().binding())) canSkin = false;
            }
            if (!hasBindings) {
                batches.add(new Batch(layer, List.copyOf(corners), light, null, null));
            } else if (rigidBone != null) {
                Transform rigidPose = skeleton.getAbsoluteTransforms().get(rigidBone).copy()
                        .mul(skeleton.getSkeleton().getBoneTransforms().get(rigidBone).getSecond());
                List<Corner> geometry = new ArrayList<>(corners.size());
                for (Corner corner : corners) geometry.add(corner.unbound(corner.position(), corner.normal()));
                batches.add(new Batch(layer, List.copyOf(geometry), light, rigidPose, null));
            } else if (canSkin) {
                if (palette == null) palette = new Palette(skeleton);
                batches.add(new Batch(layer, List.copyOf(corners), light, null, palette));
            } else {
                List<Corner> baked = new ArrayList<>(corners.size());
                for (Corner corner : corners) {
                    if (corner.bound() == null) baked.add(corner);
                    else {
                        skeleton.collectBoneContexts(boneContexts, corner.bound().vertex());
                        baked.add(corner.bake(boneContexts));
                    }
                }
                batches.add(new Batch(layer, List.copyOf(baked), light, null, null));
            }
        }
        return List.copyOf(batches);
    }

    private static @Nullable Bone commonRigidBone(List<Corner> corners) {
        Bone bone = corners.getFirst().rigidBone();
        if (bone == null) return null;
        for (Corner corner : corners) {
            if (corner.rigidBone() != bone) return null;
        }
        return bone;
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

    private static Surface surface(ModelInstance model, Material material, Function<Texture, Identifier> textures) {
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
        return new Surface(material, sprite, layer, color);
    }

    private static Corner corner(ModelInstance model, Mesh mesh, Vertex vertex, Surface surface,
                                 Vector4f color, boolean emissive) {
        Vector3f position = new Vector3f();
        Vector3f normal = new Vector3f();
        Vector2f uv = new Vector2f();
        model.getVertexPosition(vertex, position);
        model.getVertexNormal(vertex, mesh, normal);
        model.getVertexUv(vertex, mesh, surface.material(), uv);
        BoneBinding binding = vertex.getBinding();
        BoundVertex bound = binding.getFunc() == BoneBindingFunc.IDENTITY || binding.getWeights().length == 0 ? null
                : new BoundVertex(mesh, vertex, binding);
        if (!normal.isFinite() || normal.lengthSquared() == 0f) normal.set(0f, 1f, 0f);
        Sprite sprite = surface.sprite();
        float u = sprite.flipU ? 1f - uv.x : uv.x;
        float v = sprite.flipV ? 1f - uv.y : uv.y;
        Vector2f mapped = new Vector2f(sprite.getUv0()).lerp(sprite.getUv1(), u)
                .lerp(new Vector2f(sprite.getUv3()).lerp(sprite.getUv2(), u), v);
        return new Corner(position, normal, mapped, color, emissive, bound);
    }

    record Batch(RenderType layer, List<Corner> corners, int packedLight, @Nullable Transform rigidPose,
                 @Nullable Palette palette) {
        void submit(PoseStack.Pose pose, VertexConsumer consumer) {
            submit(pose, consumer, packedLight, rigidPose, palette);
        }

        void submitShared(VertexConsumer consumer) {
            submit(new PoseStack().last(), consumer, 0, null, null);
        }

        void writeSkinBindings(ByteBuffer destination) {
            int offset = 0;
            for (Corner corner : corners) {
                RenderPearlModelSkin.writeBinding(corner.bound() == null ? null : corner.bound().binding(), destination, offset);
                offset += RenderPearlModelSkin.BINDINGS.getVertexSize();
            }
        }

        private void submit(PoseStack.Pose pose, VertexConsumer consumer, int light, @Nullable Transform skin,
                            @Nullable Palette cpuSkin) {
            Vector3f skinnedPosition = new Vector3f();
            Vector3f skinnedNormal = new Vector3f();
            List<BoneContext> contexts = cpuSkin == null ? null : new ArrayList<>(4);
            for (Corner corner : corners) {
                if (cpuSkin != null && corner.bound() != null) {
                    cpuSkin.contexts(corner.bound().binding(), contexts);
                    corner = corner.bake(contexts);
                }
                Vector4f color = corner.color();
                Vector3f position = corner.position();
                Vector3f normal = corner.normal();
                if (skin != null) {
                    position = skin.apply(skinnedPosition.set(position));
                    normal = normalize(skin.normal().transform(normal, skinnedNormal));
                }
                consumer.addVertex(pose, position)
                        .setColor(color.x, color.y, color.z, color.w)
                        .setUv(corner.uv().x, corner.uv().y)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(corner.emissive() ? LightCoordsUtil.FULL_BRIGHT : light)
                        .setNormal(pose, normal);
            }
        }
    }

    private record Surface(Material material, Sprite sprite, RenderType layer, Vector4f color) {}
    private static Vector3f normalize(Vector3f normal) {
        return !normal.isFinite() || normal.lengthSquared() == 0f ? normal.set(0f, 1f, 0f) : normal.normalize();
    }

    private record BoundVertex(Mesh mesh, Vertex vertex, BoneBinding binding) {}

    private record Corner(Vector3f position, Vector3f normal, Vector2f uv, Vector4f color, boolean emissive,
                          @Nullable BoundVertex bound) {
        @Nullable Bone rigidBone() {
            if (bound == null || bound.binding().getFunc() != BoneBindingFunc.BDEF
                    || bound.binding().getWeights().length != 1 || bound.binding().getWeights()[0].getSecond() != 1f) return null;
            return bound.binding().getWeights()[0].getFirst();
        }

        Corner unbound(Vector3f newPosition, Vector3f newNormal) {
            return new Corner(newPosition, newNormal, uv, color, emissive, null);
        }

        Corner bake(List<BoneContext> contexts) {
            HashMap<Mesh, Vector3f> normals = new HashMap<>();
            normals.put(bound.mesh(), normal);
            Vertex source = new Vertex(bound.vertex(), position, normals);
            source.setBinding(bound.binding());
            Vertex posed = bound.binding().getFunc().apply(source, contexts);
            Vector3f posedNormal = posed.getNormal(bound.mesh());
            if (!posedNormal.isFinite() || posedNormal.lengthSquared() == 0f) posedNormal.set(0f, 1f, 0f);
            return unbound(posed.getPosition(), posedNormal);
        }
    }
}
