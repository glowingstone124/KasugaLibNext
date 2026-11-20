package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.ModelBounds;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import lib.kasuga.rendering.models.mc.typo.KsgMinecraftJsonLoader;
import lib.kasuga.rendering.models.mc.typo.KsgObjLoader;
import lib.kasuga.rendering.models.mc.typo.KsgPmxLoader;
import lib.kasuga.rendering.models.mc.typo.bbmodel.KsgBbModelLoader;
import lib.kasuga.rendering.models.mc.typo.gltf.KsgGltfLoader;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** A portable model and its authored lights as one world display node. */
public final class RenderPearlModelSource extends WorldDisplayTarget {
    private static final AtomicLong TEXTURE_IDS = new AtomicLong();
    private final ModelInstance model;
    private final Function<Texture, Identifier> textures;
    private final Runnable release;
    private final boolean owned;
    private final ModelBounds bounds;

    private RenderPearlModelSource(ModelInstance model, Function<Texture, Identifier> textures,
                                  Runnable release, boolean owned) {
        this.model = Objects.requireNonNull(model, "model");
        this.model.requireOpen();
        this.textures = Objects.requireNonNull(textures, "textures");
        this.release = Objects.requireNonNull(release, "release");
        this.owned = owned;
        if (!model.getModel().isRenderable() || model.getMeshMode() == MeshMode.LINES) {
            throw new IllegalArgumentException("RenderPearl requires a renderable polygon model");
        }
        this.bounds = bounds(model);
    }

    /** Borrows a caller-owned model and texture identifiers. */
    public static RenderPearlModelSource borrow(ModelInstance model, Function<Texture, Identifier> textures) {
        return new RenderPearlModelSource(model, textures, () -> {}, false);
    }

    /** Takes ownership of the model and releases its texture bindings on close. */
    public static RenderPearlModelSource own(ModelInstance model, Function<Texture, Identifier> textures, Runnable release) {
        return new RenderPearlModelSource(model, textures, release, true);
    }

    /** Opens model geometry and textures from the current resource pack stack. */
    public static RenderPearlModelSource open(Identifier identifier) throws IOException {
        String path = identifier.getPath();
        if (path.endsWith(".bbmodel")) return KsgBbModelLoader.open(identifier);
        if (path.endsWith(".gltf") || path.endsWith(".glb")) return KsgGltfLoader.open(identifier);
        if (path.endsWith(".pmx") || path.endsWith(".pmd") || path.endsWith(".zip")) return KsgPmxLoader.open(identifier);
        if (path.endsWith(".obj")) return KsgObjLoader.open(identifier);
        if (path.endsWith(".geo.json")) return KsgMinecraftJsonLoader.openBedrock(identifier);
        if (path.endsWith(".json")) return KsgMinecraftJsonLoader.openJava(identifier);
        throw new IOException("Unsupported RenderPearl model format: " + identifier);
    }

    /** Creates a model instance and uploads any embedded textures it owns. */
    public static RenderPearlModelSource open(Model model, Function<Texture, Identifier> externalTexture,
                                             Function<Texture, BufferedImage> embeddedTexture) {
        RenderSystem.assertOnRenderThread();
        TextureManager manager = Minecraft.getInstance().getTextureManager();
        List<Identifier> ownedTextures = new ArrayList<>();
        Map<Texture, Identifier> bindings = new IdentityHashMap<>();
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        try {
            for (Texture texture : model.getMaterialSet().getTextures()) {
                BufferedImage image = embeddedTexture.apply(texture);
                if (image != null) {
                    Identifier id = registerEmbedded(manager, image);
                    ownedTextures.add(id);
                    bindings.put(texture, id);
                } else {
                    bindings.put(texture, externalTexture.apply(texture));
                }
            }
            return own(instance, bindings::get, () -> release(manager, ownedTextures));
        } catch (RuntimeException | Error failure) {
            try {
                instance.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            } finally {
                release(manager, ownedTextures);
            }
            throw failure;
        }
    }

    private static Identifier registerEmbedded(TextureManager manager, BufferedImage image) {
        Identifier id = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "model/embedded/" + TEXTURE_IDS.incrementAndGet());
        DynamicTexture texture = new DynamicTexture(id::toString, image.getWidth(), image.getHeight(), false);
        try {
            NativeImage pixels = texture.getPixels();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) pixels.setPixel(x, y, image.getRGB(x, y));
            }
            texture.upload();
            manager.register(id, texture);
            return id;
        } catch (RuntimeException | Error failure) {
            texture.close();
            throw failure;
        }
    }

    private static void release(TextureManager manager, List<Identifier> textures) {
        for (Identifier texture : textures) manager.release(texture);
    }

    public ModelInstance instance() { return model; }

    public ModelBounds bounds() { return bounds; }

    /** Captures this model's posed geometry for a non-world render target. */
    public Geometry captureGeometry(float partialTick, Object frameToken, int packedLight) {
        RenderSystem.assertOnRenderThread();
        model.requireOpen();
        return new Geometry(RenderPearlModelGeometry.capture(model, textures, packedLight, partialTick, frameToken));
    }

    public static final class Geometry {
        private final List<RenderPearlModelGeometry.Batch> batches;

        private Geometry(List<RenderPearlModelGeometry.Batch> batches) {
            this.batches = batches;
        }

        public void submit(PoseStack pose, SubmitNodeCollector collector) {
            for (RenderPearlModelGeometry.Batch batch : batches) {
                collector.submitCustomGeometry(pose, batch.layer(), batch::submit);
            }
        }
    }

    /** Mounts this model directly in a level. The caller owns the mount and the source. */
    public RenderPearlWorldModels.Mount mount(ClientLevel level, Matrix4fc placement) {
        Vec3 origin = new Vec3(placement.m30(), placement.m31(), placement.m32());
        RenderPearlWorldModels.Mount mount = RenderPearlWorldModels.mount(level, model, origin, textures);
        mount.setPlacement(origin, new Matrix4f(placement).setTranslation(0, 0, 0));
        return mount;
    }

    @Override protected Snapshot contentSnapshot(LayerLayout layout, float partialTick) {
        if (model.isClosed() || !layout.includes(0)) return null;
        return new Snapshot(bounds, List.of(new Part("model", 0, bounds, context -> {
            if (context.extraction() == null) {
                for (RenderPearlModelGeometry.Batch batch : RenderPearlModelGeometry.capture(
                        model, textures, context.packedLight(), context.partialTick(), context.frameToken())) {
                    context.submit(batch.layer(), batch::submit);
                }
                return;
            }
            Matrix4f matrix = context.pose().last().pose();
            Vec3 camera = context.extraction().getCamera().position();
            Matrix4d world = new Matrix4d().translation(camera.x, camera.y, camera.z).mul(new Matrix4d(matrix));
            RenderPearlWorldModels.addToFrame(context.extraction(), model, textures, matrix, world, context.packedLight());
        })));
    }

    @Override protected void tickChildren(long tick, double deltaSeconds) {
        if (owned && !model.isClosed()) model.tick((float) deltaSeconds);
    }

    @Override protected void closeContent() {
        try {
            if (owned) model.close();
        } finally {
            release.run();
        }
    }

    private static ModelBounds bounds(ModelInstance model) {
        ModelBounds bounds = null;
        for (Vertex vertex : model.getModel().getVertices()) {
            Vector3f position = vertex.getPosition();
            ModelBounds point = new ModelBounds(position.x, position.y, position.z, position.x, position.y, position.z);
            bounds = bounds == null ? point : bounds.union(point);
        }
        return bounds == null ? new ModelBounds(0, 0, 0, 0, 0, 0) : bounds;
    }
}
