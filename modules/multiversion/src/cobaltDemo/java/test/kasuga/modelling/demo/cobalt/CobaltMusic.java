package test.kasuga.modelling.demo.cobalt;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.registration.minecraft.sound.SoundReg;

/** Registers the demo's resource-pack replaceable positional track. */
@Context
public final class CobaltMusic {
    @PostConstruct
    public void register() {
        SoundReg.of("cobalt_blue_music").setParent(KasugaLibApplication.REGISTRY);
    }
}
