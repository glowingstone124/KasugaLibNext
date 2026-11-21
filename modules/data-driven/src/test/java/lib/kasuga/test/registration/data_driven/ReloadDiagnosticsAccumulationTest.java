package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reload domain's diagnostics must be re-derived per cycle, never accumulated. Two reloads of the
 * same broken file must leave exactly one diagnostic, and that diagnostic must live in the
 * {@link Diagnostics.Domain#RELOAD_DATA} source dimension — not in the registration side's mod bucket,
 * which is never cleared by a reload.
 *
 * <p>A plain JVM test over a temporary legacy pack; no handler is registered, so the cycle is exercised
 * purely through the index reader and the per-file read guard.
 */
class ReloadDiagnosticsAccumulationTest {

    private static final String NS = "reload_accum_test";

    private static final String BAD_PATH = "content/bad.json";

    @AfterEach
    void clearDomain() {
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
        Diagnostics.clear(NS);
    }

    @Test
    void twoReloadsOfTheSameBadFileReportOneError(@TempDir Path root) throws IOException {
        ReloadOrchestrator orchestrator = new ReloadOrchestrator();
        Path namespace = root.resolve("data").resolve(NS);
        Path index = namespace.resolve(ReloadOrchestrator.indexResourcePath(NS)).resolve("index.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, "{ \"on_reload\": [ \"" + BAD_PATH + "\" ] }");
        Path badFile = namespace.resolve(BAD_PATH);
        Files.createDirectories(badFile.getParent());
        Files.writeString(badFile, "{ this is not json }");

        PackLocationInfo info = new PackLocationInfo(NS, Component.literal("Reload accumulation test"),
                PackSource.BUILT_IN, Optional.empty());
        try (MultiPackResourceManager pack = new MultiPackResourceManager(PackType.SERVER_DATA,
                List.of(new PathPackResources(info, root)))) {
            orchestrator.reload(pack);
            assertEquals(1, Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).size(),
                    "the first reload reports the broken file once: "
                            + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));

            orchestrator.reload(pack);
            assertEquals(1, Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).size(),
                    "a second reload must replace the first cycle's diagnostics, not append to them: "
                            + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
            assertTrue(Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).get(0).getMessage()
                            .contains("data/" + NS + "/" + BAD_PATH),
                    "the diagnostic must still locate the offending file: "
                            + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));

            assertEquals(List.of(), Diagnostics.errors(NS),
                    "reload diagnostics belong to the RELOAD_DATA source dimension, not the mod bucket");
        }
    }
}
