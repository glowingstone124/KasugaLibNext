package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.math.binding.SDEFData;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfLoader;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelConverter;
import lib.kasuga.structure.Pair;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelInstancingSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier MODEL = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "models/smoke/triangle.gltf");
    private static final Identifier MASK = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "models/smoke/triangle_mask.gltf");
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(KasugaLib.MODID, "smoke/instancing_white");
    private static final int COUNT = 64;

    private static List<ModelInstance> direct = List.of();
    private static List<ModelInstance> instanced = List.of();
    private static boolean started;
    private static boolean capturing;
    private static boolean finished;
    private static boolean cutout;
    private static boolean skinning;
    private static int frames;
    private static int opaquePixels;
    private static Sample reference;

    private ModelInstancingSmoke() {}

    static boolean needsFrames() {
        return !finished;
    }

    static void afterFrame(Minecraft client) throws IOException {
        if (!started) {
            NativeImage image = new NativeImage(cutout ? 2 : 1, 1, false);
            image.setPixel(0, 0, cutout ? 0xffffff : 0xffffffff);
            if (cutout) image.setPixel(1, 0, 0xffffffff);
            client.getTextureManager().register(WHITE, new DynamicTexture(WHITE::toString, image));
            Model first = load(client);
            Model second = load(client);
            direct = new ArrayList<>(COUNT);
            instanced = new ArrayList<>(COUNT);
            for (int i = 0; i < COUNT; i++) {
                direct.add(create(i % 2 == 0 ? first : second, i));
                instanced.add(create(first, i));
            }
            started = true;
        }
        if (capturing || ++frames < 8) return;
        capturing = true;
        boolean directFrame = reference == null;
        RenderPearlModelBatcher.FrameStats stats = RenderPearlModelBatcher.lastFrame();
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            Sample sample;
            try (image) {
                sample = sample(image);
                String material = cutout ? "mask" : "opaque";
                String path = directFrame ? "direct" : "instanced";
                String pose = skinning ? "skinned-" : "";
                image.writeToFile(client.gameDirectory.toPath().resolve("kasuga-model-batching-" + pose + material + "-" + path + ".png"));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save the model batching frame", failure);
            }
            client.execute(() -> {
                if (directFrame) {
                    if (sample.pixels() < 2000 || stats.instancedModels() != 0 || stats.geometryVertices() < COUNT * 4) {
                        throw new IllegalStateException("Missing direct model reference: " + sample + " " + stats);
                    }
                    reference = sample;
                    frames = 0;
                    capturing = false;
                    return;
                }
                if (stats.instancedModels() != COUNT || stats.draws() != 1 || stats.geometryVertices() > 8
                        || stats.skinnedModels() != (skinning ? COUNT : 0)
                        || Math.abs(sample.pixels() - reference.pixels()) > 40
                        || Math.abs(sample.averageGreen() - reference.averageGreen()) > 2) {
                    throw new IllegalStateException("Instanced frame differs from direct: " + sample + " vs "
                            + reference + " " + stats);
                }
                if (cutout && sample.pixels() >= opaquePixels * .8) {
                    throw new IllegalStateException("Alpha cutout did not discard masked pixels");
                }
                LOGGER.info("KASUGA_MODEL_INSTANCING_PASS material={} models={} draws={} geometryVertices={} gpuSkinnedModels={} pixels={}",
                        cutout ? "MASK" : "OPAQUE", stats.instancedModels(), stats.draws(), stats.geometryVertices(),
                        stats.skinnedModels(), sample.pixels());
                for (ModelInstance model : instanced) model.close();
                for (ModelInstance model : direct) model.close();
                client.getTextureManager().release(WHITE);
                if (cutout && skinning) {
                    finished = true;
                } else {
                    if (!cutout) opaquePixels = sample.pixels();
                    else skinning = true;
                    cutout = !cutout;
                    started = false;
                    capturing = false;
                    frames = 0;
                    reference = null;
                }
            });
        });
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        if (!started || finished) return;
        Camera camera = event.getCamera();
        List<ModelInstance> models = reference == null ? direct : instanced;
        for (int i = 0; i < models.size(); i++) {
            Vector3f location = new Vector3f((i % 8 - 3.5f) * .18f, (i / 8 - 3.5f) * .15f, -1.7f)
                    .rotate(camera.rotation());
            Matrix4f placement = new Matrix4f().translation(location).rotate(camera.rotation()).scale(.11f);
            Vec3 eye = camera.position();
            Matrix4d world = new Matrix4d().translation(eye.x, eye.y, eye.z).mul(new Matrix4d(placement));
            int light = i % 2 == 0 ? LightCoordsUtil.FULL_BRIGHT : LightCoordsUtil.pack(9, 0);
            RenderPearlWorldModels.addToFrame(event, models.get(i), texture -> WHITE, placement, world, light);
        }
    }

    private static Model load(Minecraft client) throws IOException {
        try (InputStream input = client.getResourceManager().open(cutout ? MASK : MODEL)) {
            Model model = GltfModelConverter.convert(GltfLoader.load(input));
            return skinning ? twoBoneModel(model) : model;
        }
    }

    @SuppressWarnings("unchecked")
    private static Model twoBoneModel(Model model) {
        Bone root = new Bone("root", new Transform(), null);
        Bone tip = new Bone("tip", new Transform().translate(.07f, .1f, 0), null);
        root.setChildren(new Bone[]{tip});
        tip.setParent(root);
        Bone[] bones = {root, tip};
        Skeleton skeleton = new Skeleton(bones, root, new Anchor[0], null, new Transform());
        Vertex[] vertices = model.getVertices();
        for (int i = 0; i < vertices.length; i++) {
            float rootWeight = .25f + (i % 3) * .2f;
            Pair<Bone, Float>[] weights = new Pair[]{Pair.of(root, rootWeight), Pair.of(tip, 1f - rootWeight)};
            BoneBindingFunc type = switch (i % 3) {
                case 0 -> BoneBindingFunc.BDEF;
                case 1 -> BoneBindingFunc.SDEF;
                default -> BoneBindingFunc.QDEF;
            };
            SphericalBinding data = i % 3 == 1 ? new SphericalBinding(new SDEFData(new Vector3f(0, .1f, 0),
                    new Vector3f(.04f, 0, 0), new Vector3f(-.03f, .01f, 0))) : null;
            vertices[i].setBinding(new BoneBinding(weights, type, data));
        }
        return new Model(vertices, model.getMeshes(), bones, skeleton, model.getMaterialSet(), model.getMeshMode(), null, null);
    }

    private static ModelInstance create(Model model, int index) {
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        instance.getSkeletonInstance().transformRoot(new Transform().translate((index % 3 - 1) * .1f, 0, 0));
        if (skinning) {
            instance.getSkeletonInstance().transform("tip", new Transform().translate(0, (index % 4 - 1) * .05f, 0)
                    .rotate(0, (index % 7 - 3) * 8, (index % 5 - 2) * 4, true));
        }
        return instance;
    }

    private static Sample sample(NativeImage image) {
        int pixels = 0;
        long totalGreen = 0;
        for (int y = image.getHeight() / 4; y < image.getHeight() * 3 / 4; y++) {
            for (int x = image.getWidth() / 4; x < image.getWidth() * 3 / 4; x++) {
                int color = image.getPixel(x, y);
                int red = color >>> 16 & 255;
                int green = color >>> 8 & 255;
                int blue = color & 255;
                if (green > 36 && blue > 36 && red * 4 < green && red * 4 < blue) {
                    pixels++;
                    totalGreen += green;
                }
            }
        }
        return new Sample(pixels, pixels == 0 ? 0 : (double) totalGreen / pixels);
    }

    private record Sample(int pixels, double averageGreen) {}

    private record SphericalBinding(SDEFData getSDEFData) implements BoneBindingData, SDEFBoneBindingData {}
}
