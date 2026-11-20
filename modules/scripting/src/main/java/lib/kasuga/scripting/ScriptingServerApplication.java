package lib.kasuga.scripting;

import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.inject.auto_configure.Configurable;
import lib.kasuga.inject.class_loader.BeanOnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Context()
@BeanOnlyIn.DedicatedServer()
public class ScriptingServerApplication implements Configurable {

    Logger logger = LogUtils.getLogger();

    @Inject()
    ScriptingSystem scriptingSystem;


    @Inject() @Named("forgeEventBus")
    IEventBus eventBus;

    private ModConfigSpec.BooleanValue IGNORE_ERRORS;

    @Override
    public void configureServer(ModConfigSpec.Builder builder) {
        IGNORE_ERRORS = builder
                .comment("Disable the scripting error")
                .define("ignore_scripting_errors" , false);
    }

    @PostConstruct()
    public void init() {
        eventBus.addListener(this::onServerTick);
    }

    private void onServerTick(ServerTickEvent.Pre event) {
        scriptingSystem.reportEngineDiagnostics(event.getServer(), this::reportEngineDiagnostics);
    }

    private void reportEngineDiagnostics(ScriptRuntime.EngineDiagnostics diagnostics) {
        for (Map.Entry<String, List<Throwable>> entry : diagnostics.failures().entrySet()) {
            for (Throwable error : entry.getValue()) logger.error("Script engine {} failed", entry.getKey(), error);
        }
        if (!diagnostics.failures().isEmpty()) {
            throw new IllegalStateException("Script engine startup failed: " + String.join(", ", diagnostics.failures().keySet()));
        }

        Set<String> missingEngines = diagnostics.missing();
        if (missingEngines.isEmpty()) return;
        String message = "Missing scripting engine: " + String.join(", ", missingEngines) + ", please install them now!";
        if (IGNORE_ERRORS.getAsBoolean()) {
            logger.warn(message);
            return;
        }
        throw new IllegalStateException(message);
    }
}
