package lib.kasuga.registration.data_driven.diagnostics;

import net.minecraft.commands.CommandSourceStack;

final class DiagnosticPermissions {
    private DiagnosticPermissions() {}

    static boolean canInspectData(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}
