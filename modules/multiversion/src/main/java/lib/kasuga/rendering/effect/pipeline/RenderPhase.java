package lib.kasuga.rendering.effect.pipeline;

/** World rendering phases exposed by the Minecraft 26.3 host. */
public enum RenderPhase {
    AFTER_SKY,
    AFTER_OPAQUE_BLOCKS,
    AFTER_OPAQUE_FEATURES,
    /** Classic transparency only. */
    AFTER_TRANSLUCENT_FEATURES,
    /** Classic transparency only. */
    AFTER_TRANSLUCENT_BLOCKS,
    /** Classic transparency only. */
    AFTER_PARTICLES,
    /** Classic transparency only. */
    AFTER_WEATHER,
    /** One callback per native OIT stage when improved transparency is enabled. */
    OIT_TRANSLUCENT,
    POST_PROCESS,
    AFTER_LEVEL
}
