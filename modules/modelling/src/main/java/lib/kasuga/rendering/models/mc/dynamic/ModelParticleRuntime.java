package lib.kasuga.rendering.models.mc.dynamic;

import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.models.mc.component.ModelComponents;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Instance-owned simulation with a weak world-render registry. Never retains a model globally. */
public final class ModelParticleRuntime {
    public static final String MODULE_ID = "kasuga:model_emitters";
    private static final double STEP = 1.0 / 120.0;
    private static final Map<EmitterModule, Boolean> MODULES = new WeakHashMap<>();
    static final ResourceLocation DEFAULT_TEXTURE = ResourceLocation.parse("kasuga_lib:textures/particle/model_dot.png");

    private ModelParticleRuntime() {}

    public static void install(ModelInstance instance, ModelComponents components) {
        install(instance, components, false);
    }

    /** Installs an emitter for a host that applies its world placement during frame extraction. */
    public static void installWithWorldPlacement(ModelInstance instance, ModelComponents components) {
        install(instance, components, true);
    }

    private static void install(ModelInstance instance, ModelComponents components, boolean externalPlacement) {
        if (components.emitters().isEmpty() || module(instance) != null) return;
        EmitterModule module = new EmitterModule(components.emitters(), externalPlacement);
        instance.getTickLoop().addPostPhysics(MODULE_ID, module);
        synchronized (MODULES) { MODULES.put(module, Boolean.TRUE); }
    }

    public static EmitterModule module(ModelInstance instance) {
        return instance.getTickLoop().module(MODULE_ID, EmitterModule.class);
    }

    public static void register(ModelInstance instance) {
        EmitterModule module = module(instance);
        if (module != null && !instance.isClosed()) module.enable();
    }

    public static void unregister(ModelInstance instance) {
        EmitterModule module = module(instance);
        if (module != null) module.disable();
    }

    /** Retires a scene placement so the next attachment starts with empty emitters. */
    public static void detachWorldPlacement(ModelInstance instance) {
        EmitterModule module = module(instance);
        if (module != null && module.externalPlacement) module.resetWorld();
    }

    /** Old-world instances must be explicitly registered again before emitting in another world. */
    public static void clearWorldState() {
        for (EmitterModule module : modules()) module.resetWorld();
    }

    public static void invalidateTextures() { ModelParticleRenderer.invalidateTextures(); }
    public static int lastVisibleCount() { return ModelParticleRenderer.lastVisibleCount(); }

    static List<EmitterModule> modules() {
        synchronized (MODULES) { return new ArrayList<>(MODULES.keySet()); }
    }

    public static void initialize(RenderPipelineRegistrar registrar) { ModelParticleRenderer.initialize(registrar); }

    public record ParticleView(Vector3d position, float size, Vector4f color, ResourceLocation texture) {
        public ParticleView { position = new Vector3d(position); color = new Vector4f(color); }
        @Override public Vector3d position() { return new Vector3d(position); }
        @Override public Vector4f color() { return new Vector4f(color); }
        double distanceSquared(double x, double y, double z) { return position.distanceSquared(x, y, z); }
    }

    public static final class EmitterModule implements ModelTickLoopModule {
        private final List<EmitterState> emitters;
        private final boolean externalPlacement;
        private double accumulator;
        private boolean enabled, closed, autoActivationAllowed = true;

        private EmitterModule(List<ModelComponents.EmitterDefinition> definitions, boolean externalPlacement) {
            this.externalPlacement = externalPlacement;
            emitters = new ArrayList<>(definitions.size());
            for (ModelComponents.EmitterDefinition definition : definitions) emitters.add(new EmitterState(definition));
        }
        public boolean enabled() { return enabled && !closed; }
        public boolean closed() { return closed; }
        public int particleCount() { return emitters.stream().mapToInt(e -> e.particles.size()).sum(); }
        public List<ParticleView> particles() {
            List<ParticleView> result = new ArrayList<>(particleCount());
            for (EmitterState emitter : emitters) for (Particle particle : emitter.particles) {
                Vector3d position = particle.position;
                float size = emitter.definition.sizePixels() / 16f;
                if (emitter.definition.localSpace()) {
                    Vector3f point = new Vector3f((float)position.x, (float)position.y, (float)position.z);
                    emitter.frame.apply(point);
                    position = new Vector3d(emitter.origin).add(point.x, point.y, point.z);
                    Vector3f scale = emitter.frame.transform().getScale(new Vector3f());
                    size *= Math.max(Math.abs(scale.x), Math.max(Math.abs(scale.y), Math.abs(scale.z)));
                }
                result.add(new ParticleView(position, size, emitter.definition.color(), emitter.texture));
            }
            return List.copyOf(result);
        }
        public void enable() { if (!closed) { enabled = true; autoActivationAllowed = true; } }
        public void disable() { enabled = false; autoActivationAllowed = false; clear(); }
        private void resetWorld() { enabled = false; autoActivationAllowed = true; clear(); }
        private void clear() {
            accumulator = 0;
            for (EmitterState emitter : emitters) {
                emitter.particles.clear();
                emitter.carry = 0;
            }
        }

        @Override public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float deltaTime) {
            if (closed || externalPlacement) return;
            ModelInstance instance = loop.getInstance();
            if (!enabled && autoActivationAllowed && MinecraftModelRuntime.isRegistered(instance)) enable();
            if (!enabled) return;
            advance(instance.getSkeletonInstance(), null, deltaTime);
        }

        void advanceInWorld(SkeletonInstance skeleton, Matrix4dc placement, float deltaTime) {
            if (closed || !externalPlacement) return;
            if (!enabled && autoActivationAllowed) enable();
            if (enabled) advance(skeleton, placement, deltaTime);
        }

        private void advance(SkeletonInstance skeleton, Matrix4dc placement, float deltaTime) {
            Vector3d origin = skeleton.getWorldOrigin();
            for (EmitterState emitter : emitters) {
                Transform frame = skeleton.anchorTransformRelative(emitter.definition.anchorName(), origin);
                emitter.valid = frame != null && frame.transform().isFinite();
                if (!emitter.valid) continue;
                if (placement == null) {
                    emitter.frame.set(frame);
                    emitter.origin.set(origin);
                } else {
                    // The external placement already includes the world's floating origin.
                    Matrix4d anchor = new Matrix4d(placement).mul(new Matrix4d(frame.transform()));
                    emitter.origin.set(anchor.m30(), anchor.m31(), anchor.m32());
                    emitter.frame.set(new Matrix4f(anchor.setTranslation(0, 0, 0)));
                }
            }
            if (!(deltaTime > 0) || !Float.isFinite(deltaTime)) return;
            accumulator = Math.min(accumulator + deltaTime, 0.1);
            int steps = Math.min(12, (int)Math.floor((accumulator + 1e-8) / STEP));
            accumulator = Math.max(0, accumulator - steps * STEP);
            for (int i = 0; i < steps; i++) for (EmitterState emitter : emitters) emitter.step();
        }
        @Override public void destroy(Model model) { closed = true; disable(); synchronized (MODULES) { MODULES.remove(this); } }
    }

    private static final class Particle {
        final Vector3d position;
        final Vector3d velocity;
        double age;
        Particle(Vector3d position, Vector3d velocity) { this.position = position; this.velocity = velocity; }
    }

    private static final class EmitterState {
        final ModelComponents.EmitterDefinition definition;
        final List<Particle> particles = new ArrayList<>();
        final Transform frame = new Transform();
        final Vector3d origin = new Vector3d();
        final Vector3d gravity;
        final ResourceLocation texture;
        double carry;
        boolean valid;
        EmitterState(ModelComponents.EmitterDefinition definition) {
            this.definition = definition;
            Vector3f acceleration = definition.gravityPixelsPerSecondSquared().div(16f);
            gravity = new Vector3d(acceleration.x, acceleration.y, acceleration.z);
            texture = textureLocation(definition.texture());
        }
        void step() {
            for (Iterator<Particle> it = particles.iterator(); it.hasNext();) {
                Particle p = it.next(); p.age += STEP;
                if (p.age + 1e-8 >= definition.lifetimeSeconds()) { it.remove(); continue; }
                p.velocity.fma(STEP, gravity); p.position.fma(STEP, p.velocity);
            }
            if (!valid || !definition.enabled()) return;
            carry += definition.rate() * STEP;
            int due = (int)Math.floor(carry + 1e-8);
            carry -= due; // discard emissions above budget, never defer a burst
            int count = Math.min(due, definition.maxParticles() - particles.size());
            for (int i = 0; i < count; i++) {
                double phi = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
                double cosine = 1 - ThreadLocalRandom.current().nextDouble() * (1 - Math.cos(Math.toRadians(definition.spreadDegrees())));
                double sine = Math.sqrt(Math.max(0, 1 - cosine * cosine));
                Vector3f direction = new Vector3f((float)(sine*Math.cos(phi)), (float)cosine, (float)(sine*Math.sin(phi)));
                Vector3d position;
                if (definition.localSpace()) position = new Vector3d();
                else {
                    frame.applyDirection(direction);
                    if (!direction.isFinite() || direction.lengthSquared() < 1e-12f) continue;
                    direction.normalize();
                    Vector3f point = frame.getPosition();
                    position = new Vector3d(origin).add(point.x, point.y, point.z);
                }
                particles.add(new Particle(position, new Vector3d(direction.x,direction.y,direction.z).mul(definition.speedPixelsPerSecond()/16.0)));
            }
        }
    }

    public static ResourceLocation textureLocation(String value) {
        if (value == null || value.isBlank()) return DEFAULT_TEXTURE;
        ResourceLocation id = ResourceLocation.parse(value);
        String path = id.getPath();
        if (!path.startsWith("textures/")) path = "textures/" + path;
        if (!path.endsWith(".png")) path += ".png";
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }
}
