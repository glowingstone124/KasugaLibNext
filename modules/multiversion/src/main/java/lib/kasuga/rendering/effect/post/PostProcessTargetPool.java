package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Graph-owned texture storage. The first color attachment is also the native RenderTarget color. */
public final class PostProcessTargetPool implements AutoCloseable {
    private final Map<Identifier, ManagedTarget> targets = new HashMap<>();

    public RenderTarget acquire(PostProcessTargetDescriptor descriptor, RenderTarget reference) {
        RenderSystem.assertOnRenderThread();
        int width = descriptor.resolveWidth(reference.width);
        int height = descriptor.resolveHeight(reference.height);
        ManagedTarget previous = targets.get(descriptor.id());
        if (previous != null && previous.descriptor.equals(descriptor) && previous.width == width && previous.height == height) {
            return previous;
        }
        ManagedTarget replacement = new ManagedTarget(descriptor);
        try {
            replacement.createBuffers(width, height);
        } catch (RuntimeException failure) {
            replacement.destroyBuffers();
            throw failure;
        }
        targets.put(descriptor.id(), replacement);
        if (previous != null) previous.destroyBuffers();
        return replacement;
    }

    public static int colorCount(RenderTarget target) {
        return target instanceof ManagedTarget managed ? managed.descriptor.colorAttachments().size() : 1;
    }

    public static GpuTextureView color(RenderTarget target, int index) {
        Objects.checkIndex(index, colorCount(target));
        if (index == 0) return Objects.requireNonNull(target.getColorTextureView(), "Target is missing its primary color attachment");
        ManagedTarget managed = (ManagedTarget) target;
        return Objects.requireNonNull(managed.extraColors.get(index - 1).getColorTextureView());
    }

    static GpuFormat format(PostProcessTargetDescriptor.ColorFormat format) {
        return switch (format) {
            case RGBA8 -> GpuFormat.RGBA8_UNORM;
            case RGBA16F -> GpuFormat.RGBA16_FLOAT;
            case RGBA32F -> GpuFormat.RGBA32_FLOAT;
        };
    }

    static PostProcessTargetDescriptor descriptor(RenderTarget target) {
        return target instanceof ManagedTarget managed ? managed.descriptor : null;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();
        for (ManagedTarget target : targets.values()) target.destroyBuffers();
        targets.clear();
    }

    private static final class ManagedTarget extends RenderTarget {
        private final PostProcessTargetDescriptor descriptor;
        private final List<TextureTarget> extraColors = new ArrayList<>();

        private ManagedTarget(PostProcessTargetDescriptor descriptor) {
            super(descriptor.id().toString(), format(descriptor.colorAttachments().getFirst()),
                    descriptor.useDepth() ? GpuFormat.D32_FLOAT : null);
            this.descriptor = descriptor;
        }

        @Override
        public void createBuffers(int width, int height) {
            super.createBuffers(width, height);
            for (int index = 1; index < descriptor.colorAttachments().size(); index++) {
                extraColors.add(new TextureTarget(descriptor.id() + " color " + index, width, height,
                        format(descriptor.colorAttachments().get(index)), null));
            }
        }

        @Override
        public void destroyBuffers() {
            for (TextureTarget target : extraColors) target.destroyBuffers();
            extraColors.clear();
            super.destroyBuffers();
        }
    }
}
