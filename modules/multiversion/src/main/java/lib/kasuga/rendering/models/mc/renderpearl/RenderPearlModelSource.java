package lib.kasuga.rendering.models.mc.renderpearl;

import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.ModelBounds;
import lib.kasuga.rendering.models.mc.scene.display.WorldDisplayTarget;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** A portable model and its authored lights as one world display node. */
public final class RenderPearlModelSource extends WorldDisplayTarget {
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

    public ModelInstance instance() { return model; }

    @Override protected Snapshot contentSnapshot(LayerLayout layout, float partialTick) {
        if (model.isClosed() || !layout.includes(0)) return null;
        return new Snapshot(bounds, List.of(new Part("model", 0, bounds, context -> {
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
