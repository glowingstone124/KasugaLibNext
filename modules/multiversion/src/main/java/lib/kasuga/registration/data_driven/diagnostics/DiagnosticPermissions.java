package lib.kasuga.registration.data_driven.diagnostics;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

final class DiagnosticPermissions {
    private DiagnosticPermissions() {}

    static boolean canInspectData(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
