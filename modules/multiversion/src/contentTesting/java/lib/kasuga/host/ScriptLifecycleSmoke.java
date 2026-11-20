package lib.kasuga.host;

import lib.kasuga.scripting.ScriptConsole;
import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.ScriptEngineRegistry;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.feature.EngineFeature;
import lib.kasuga.scripting.feature.EngineFeatureType;
import lib.kasuga.scripting.module.ModuleId;
import lib.kasuga.scripting.module.ModuleResolver;
import lib.kasuga.scripting.module.ResolvedPackage;
import lib.kasuga.scripting.module.ResolvedScript;
import lib.kasuga.scripting.module.ScriptModuleHandle;
import lib.kasuga.scripting.value.ScriptValue;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

final class ScriptLifecycleSmoke {
    private final ScriptEngineType<Engine> type;
    private volatile boolean initialTicked;
    private volatile boolean initialClosed;
    private volatile boolean replacementTicked;

    private int created;

    ScriptLifecycleSmoke(ScriptEngineRegistry registry) {
        type = ScriptEngineType.builder(() -> new Engine(++created)).scriptType("smoke").resolver(new Resolver()).build();
        registry.register(Identifier.parse("kasuga_lib:smoke"), List.of("smoke"), type, 0);
    }

    boolean initialReady() { return initialTicked; }

    boolean replacementReady() { return initialClosed && replacementTicked; }

    private final class Engine implements ScriptEngine {
        private final int generation;
        private boolean entryRan;
        private int ticksAfterEntry;

        Engine(int generation) { this.generation = generation; }

        @Override public void executeEntry(String name, InputStream source) throws ScriptException {
            try {
                entryRan = "kasuga-script-smoke".equals(new String(source.readAllBytes(), StandardCharsets.UTF_8).strip());
            } catch (IOException failure) {
                throw new ScriptException(failure);
            }
        }

        @Override public void tick() {
            if (!entryRan || ++ticksAfterEntry < 2) return;
            if (generation == 1) initialTicked = true;
            if (generation == 2) replacementTicked = true;
        }

        @Override public void close() {
            if (generation == 1) initialClosed = true;
        }

        @Override public ScriptEngineType<?> getType() { return type; }
        @Override public void init(ScriptConsole console) {}
        @Override public void setFeatures(Map<EngineFeatureType<?>, EngineFeature> features) {}
        @Override public <F extends EngineFeature> F getFeature(EngineFeatureType<F> type) { return null; }
        @Override public ScriptValue createValue(Object object) { throw new UnsupportedOperationException(); }
        @Override public ScriptModuleHandle loadModule(ResolvedScript script) { throw new UnsupportedOperationException(); }
        @Override public ScriptModuleHandle getLoadedModule(String path) { return null; }
    }

    private static final class Resolver implements ModuleResolver {
        @Override public ResolvedScript locateScript(ResolvedPackage owner, List<String> segments) {
            String path = String.join("/", segments) + "." + getExtension();
            if (!owner.packResources().isRegularFile(owner.packRelativeRoot(), path)) return null;
            return new ResolvedScript(path, owner.packResources(), new ModuleId(segments), owner);
        }

        @Override public String getExtension() { return "mock"; }
    }
}
