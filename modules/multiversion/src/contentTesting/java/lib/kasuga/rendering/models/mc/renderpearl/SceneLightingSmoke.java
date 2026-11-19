package lib.kasuga.rendering.models.mc.renderpearl;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.lighting.LightColor;
import lib.kasuga.rendering.lighting.LightSource;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayScene;
import lib.kasuga.rendering.models.mc.scene.display.WorldLightTarget;
import lib.kasuga.rendering.models.mc.typo.bbmodel.KsgBbModelLoader;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.scene.SceneModels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.io.IOException;

final class SceneLightingSmoke implements AutoCloseable {
    private static final Identifier LOCATOR_MODEL = Identifier.fromNamespaceAndPath(KasugaLib.MODID,
            "models/components/light_locator.bbmodel");
    private static final Identifier TEXTURED_MODEL = Identifier.fromNamespaceAndPath(KasugaLib.MODID,
            "models/components/emitter_smoke.bbmodel");
    private static final String LOCATOR_BONE = "lamp_mount";
    private static final Vec3 LOCAL_LIGHT = new Vec3(6, -18, 0);
    private final SceneModels models;
    private final SceneModels.Entry triangle;
    private final WorldDisplayScene root = new WorldDisplayScene();
    private final WorldLightTarget lamp;
    private final SceneModels.Entry scene;
    private RenderPearlModelSource locator;
    private SceneModels.Entry locatorEntry;
    private SceneModels.Entry texturedEntry;
    private RenderPearlClientSmoke.Lighting previous;

    SceneLightingSmoke(ClientLevel level, ModelInstance model, Identifier white) {
        models = SceneModels.open(level);
        triangle = models.add(RenderPearlModelSource.borrow(model, texture -> white), Vec3.ZERO);
        root.transform(new Matrix4f().translation(0, 10, 0));
        WorldDisplayScene group = root.add(new WorldDisplayScene());
        group.transform(new Matrix4f().translation(0, 8, 6).rotateY((float) (Math.PI / 2)));
        lamp = group.add(new WorldLightTarget(LightSource.point(LOCAL_LIGHT.x, LOCAL_LIGHT.y, LOCAL_LIGHT.z)
                .color(LightColor.srgb(1, 0, 0)).range(2).intensity(160).build()));
        scene = models.add(root, Vec3.ZERO);
    }

    void update(RenderPearlClientSmoke.Lighting phase, Vec3 modelPosition, Matrix4fc orientation, Vec3 emitter)
            throws IOException {
        triangle.position(modelPosition).transform(orientation);
        if (phase != previous) {
            switch (phase) {
                case SCENE_MOVED -> lamp.moveBy(0, 32, 0);
                case SCENE_HIDDEN -> {
                    lamp.position(LOCAL_LIGHT);
                    root.visible(false);
                }
                case SCENE_SHOWN -> root.visible(true);
                case SCENE_CLOSED -> {
                    scene.close();
                    if (!root.isClosed() || !lamp.isClosed()) {
                        throw new IllegalStateException("Closing the parent did not close its light node");
                    }
                }
                case LOCATOR_LIT -> {
                    locator = KsgBbModelLoader.open(LOCATOR_MODEL);
                    locatorEntry = models.add(locator, Vec3.ZERO);
                }
                case LOCATOR_BONE_MOVED -> locator.instance().getSkeletonInstance()
                        .transform(LOCATOR_BONE, new Transform().translate(0, 32, 0));
                case LOCATOR_RESTORED -> locator.instance().getSkeletonInstance().transform(LOCATOR_BONE, new Transform());
                case LOCATOR_CLOSED -> {
                    locatorEntry.close();
                    if (!locator.instance().isClosed()) {
                        throw new IllegalStateException("Closing the model node did not close the owned instance");
                    }
                }
                case EMBEDDED_TEXTURE -> texturedEntry = models.add(KsgBbModelLoader.open(TEXTURED_MODEL), Vec3.ZERO);
                default -> {}
            }
            previous = phase;
        }
        if (!scene.isClosed()) scene.position(emitter);
        if (locatorEntry != null && !locatorEntry.isClosed()) {
            // The native export stores (2, 0.5, 0) relative to a bone pivot at (0, 1, 0).
            locatorEntry.position(emitter.subtract(2, 1.5, 0));
        }
        if (texturedEntry != null) {
            Vector3f shift = orientation.transformDirection(new Vector3f(0, -.4f, 0));
            texturedEntry.position(emitter.add(shift.x, shift.y, shift.z))
                    .transform(new Matrix4f(orientation).scale(2));
        }
    }

    @Override public void close() {
        models.close();
    }
}
