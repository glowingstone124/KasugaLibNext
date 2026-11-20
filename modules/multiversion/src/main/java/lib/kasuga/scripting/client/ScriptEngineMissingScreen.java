package lib.kasuga.scripting.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Set;
import java.util.TreeSet;

public final class ScriptEngineMissingScreen extends ScriptEngineDiagnostics {
    public ScriptEngineMissingScreen(Set<String> engines, Screen previous) {
        super(Component.literal("Missing script engines"), Component.literal(String.join("\n", new TreeSet<>(engines))), previous, false);
    }
}
