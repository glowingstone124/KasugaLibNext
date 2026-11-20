package lib.kasuga.scripting;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.ScopedResourceManagerConsumer;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Context()
public class ScriptingSystem implements ScopedResourceManagerConsumer {

    private final ConcurrentHashMap<MinecraftServer, ScriptRuntime> serverRuntimes = new ConcurrentHashMap<>();
    private volatile ScriptRuntime clientRuntime;

    @Inject() @Named("forgeEventBus")
    IEventBus eventBus;

    @PostConstruct()
    void init() {
        eventBus.addListener(this::onServerTick);
    }

    @Override
    public void onResourceManagerAdded(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        ScriptRuntime rt = new ScriptRuntime(server, resourceManager);
        KasugaLib.getContext().inject(rt);
        ScriptRuntime previous;
        if (server == null) {
            previous = clientRuntime;
            clientRuntime = rt;
        } else {
            previous = serverRuntimes.put(server, rt);
        }
        if (previous != null) previous.close();
        resourceManager.addListener(rt);
    }

    @Override
    public void onResourceManagerRemoved(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        ScriptRuntime rt;
        if (server == null) {
            rt = clientRuntime;
            clientRuntime = null;
        } else {
            rt = serverRuntimes.remove(server);
        }
        if (rt != null) rt.close();
    }

    private void onServerTick(ServerTickEvent.Pre event) {
        ScriptRuntime rt = serverRuntimes.get(event.getServer());
        if (rt != null) rt.tick();
    }

    void tickClient() {
        ScriptRuntime rt = clientRuntime;
        if (rt != null) rt.tick();
    }

    void reportEngineDiagnostics(@Nullable MinecraftServer server, Consumer<ScriptRuntime.EngineDiagnostics> report) {
        ScriptRuntime runtime = server == null ? clientRuntime : serverRuntimes.get(server);
        if (runtime == null) return;
        ScriptRuntime.EngineDiagnostics diagnostics = runtime.takeEngineDiagnostics();
        if (diagnostics != null) report.accept(diagnostics);
    }
}
