package lib.kasuga.scripting.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ScriptEngineErrorScreen extends ScriptEngineDiagnostics {
    public ScriptEngineErrorScreen(Map<String, List<Throwable>> errors, Screen previous) {
        super(Component.literal("Script engine startup failed"), summarize(errors), previous, true);
    }

    private static Component summarize(Map<String, List<Throwable>> errors) {
        StringBuilder details = new StringBuilder();
        for (Map.Entry<String, List<Throwable>> engine : new TreeMap<>(errors).entrySet()) {
            details.append(engine.getKey()).append('\n');
            for (Throwable issue : engine.getValue()) details.append("  ").append(issue).append('\n');
        }
        return Component.literal(details.toString());
    }
}
