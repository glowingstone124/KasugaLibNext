package lib.kasuga.rendering.effect;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WorldPipelineSmoke {
    private static final RenderPipeline DRAW = RenderPipeline.builder()
            .withLocation(id("pipeline/world/stage_smoke"))
            .withVertexShader(id("core/post/fullscreen"))
            .withFragmentShader(id("core/world/stage_smoke"))
            .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, 0))
            .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build();
    private static final List<PipelineRegistration> REGISTRATIONS = new ArrayList<>();
    private static final Matrix4f POSE = new Matrix4f();
    private static int graphFramesBefore;
    private static int stage;
    private static int frames;

    private WorldPipelineSmoke() {}

    @SubscribeEvent
    public static void registerPipeline(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(DRAW);
    }

    public static void install(IntSupplier graphFrames) {
        // Register out of order. The dispatcher must sort by priority then ID.
        register("zeta", RenderPhase.AFTER_OPAQUE_BLOCKS, 0, context -> {
            require(stage == 2, "Same-priority callbacks did not use ID order");
            stage = 3;
        });
        register("alpha", RenderPhase.AFTER_OPAQUE_BLOCKS, 0, context -> {
            require(stage == 1 && context.poseStack().last().pose().equals(POSE), "Previous callback leaked its pose");
            RenderPass pass = context.renderPass();
            require(pass != null && context.oitStage() == null && context.camera().initialized, "Missing opaque stage state");
            pass.setPipeline(RenderSystem.getCompiledPipeline(DRAW));
            pass.draw(3, 1, 0, 0);
            stage = 2;
        });
        register("start", RenderPhase.AFTER_OPAQUE_BLOCKS, -1, context -> {
            require(stage == 0, "Duplicate or unordered opaque stage");
            POSE.set(context.poseStack().last().pose());
            context.poseStack().translate(4, 8, 12);
            stage = 1;
        });
        register("post", RenderPhase.POST_PROCESS, 0, context -> {
            require(stage == 3 && context.renderPass() == null, "Post-process stage ran before opaque blocks");
            graphFramesBefore = graphFrames.getAsInt();
            stage = 4;
        });
        register("end", RenderPhase.AFTER_LEVEL, 0, context -> {
            require(stage == 4 && context.renderPass() == null, "After-level stage ran before post-processing");
            if (frames > 0) require(graphFrames.getAsInt() == graphFramesBefore + 1, "Graph ran outside post-processing");
            stage = 0;
            frames++;
        });
    }

    public static void finish() {
        require(frames >= 3, "World stages did not run in order across the tested frames");
        for (PipelineRegistration registration : REGISTRATIONS) {
            registration.close();
            require(!registration.isActive(), "World callback did not unregister");
        }
        REGISTRATIONS.clear();
        LogUtils.getLogger().info("KASUGA_WORLD_PIPELINE_PASS native-pass,priority,id-order,pose-isolation,post-process,unregister");
    }

    private static void register(String name, RenderPhase phase, int priority, WorldRenderPipeline callback) {
        REGISTRATIONS.add(WorldRenderPipelineRegistry.register(id("smoke"),
                RenderPipelineDescriptor.builder(id("stage_smoke/" + name), phase).priority(priority).build(),
                DuplicatePolicy.FAIL, callback));
    }

    private static Identifier id(String name) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, name); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
