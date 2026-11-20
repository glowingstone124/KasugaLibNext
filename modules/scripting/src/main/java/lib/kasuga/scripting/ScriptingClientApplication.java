package lib.kasuga.scripting;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.inject.class_loader.BeanOnlyIn;
import lib.kasuga.scripting.client.ScriptEngineErrorScreen;
import lib.kasuga.scripting.client.ScriptEngineMissingScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.Set;

@Context()
@BeanOnlyIn.Client
public class ScriptingClientApplication {

    @Inject() @Named("forgeEventBus")
    IEventBus eventBus;

    @Inject()
    ScriptingSystem scriptingSystem;

    @PostConstruct()
    public void init() {
        eventBus.addListener(this::onClientTick);
    }

    private void onClientTick(ClientTickEvent.Pre event) {
        scriptingSystem.tickClient();
        scriptingSystem.reportEngineDiagnostics(null, this::showEngineDiagnostics);
    }

    private void showEngineDiagnostics(ScriptRuntime.EngineDiagnostics diagnostics) {
        Set<String> missingEngines = diagnostics.missing();
        if (!missingEngines.isEmpty()) {
            Minecraft.getInstance().setScreen(new ScriptEngineMissingScreen(missingEngines, Minecraft.getInstance().screen));
        }
        if (!diagnostics.failures().isEmpty()) {
            Minecraft.getInstance().setScreen(new ScriptEngineErrorScreen(diagnostics.failures(), Minecraft.getInstance().screen));
        }
    }
}
