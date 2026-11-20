package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.resources.Identifier;

import java.util.Objects;

/** Scheduling for a world callback. Draw state is declared in its native RenderPearl pipelines. */
public record RenderPipelineDescriptor(Identifier id, RenderPhase phase, int priority) {
    public RenderPipelineDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(phase, "phase");
    }

    public static Builder builder(Identifier id, RenderPhase phase) {
        return new Builder(id, phase);
    }

    public static final class Builder {
        private final Identifier id;
        private final RenderPhase phase;
        private int priority;

        private Builder(Identifier id, RenderPhase phase) {
            this.id = id;
            this.phase = phase;
        }

        public Builder priority(int value) {
            priority = value;
            return this;
        }

        public RenderPipelineDescriptor build() {
            return new RenderPipelineDescriptor(id, phase, priority);
        }
    }
}
