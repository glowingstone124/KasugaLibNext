package lib.kasuga.rendering.models.mc.component;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VfsWriteTransaction;
import lib.kasuga.core.vfs.VirtualFileSystem;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;

import java.io.IOException;

final class LivePreviewPack {
    private static final int LEGACY_FORMAT = 34;
    private static final String DESCRIPTION = "Kasuga Blockbench live preview";

    private LivePreviewPack() {}

    static void ensureMetadata(VirtualFileSystem vfs, VfsPath metadata) throws IOException {
        JsonObject root;
        JsonObject pack;
        if (vfs.exists(metadata)) {
            try {
                root = JsonParser.parseString(vfs.readString(metadata)).getAsJsonObject();
                pack = root.getAsJsonObject("pack");
                if (pack == null || pack.has("min_format") || pack.has("max_format") || pack.has("supported_formats")
                        || !pack.has("pack_format") || pack.get("pack_format").getAsInt() != LEGACY_FORMAT
                        || !pack.has("description") || !pack.get("description").isJsonPrimitive()
                        || !DESCRIPTION.equals(pack.get("description").getAsString())) return;
            } catch (RuntimeException failure) {
                throw new IOException("Invalid live preview pack metadata", failure);
            }
        } else {
            root = new JsonObject();
            pack = new JsonObject();
            root.add("pack", pack);
            pack.addProperty("pack_format", LEGACY_FORMAT);
            pack.addProperty("description", DESCRIPTION);
        }

        PackFormat version = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
        // The Blockbench plugin writes the legacy format when opening a preview directory for the first time.
        // Keep that declaration readable on 1.21.1 and add the current client format for RenderPearl.
        pack.add("supported_formats", range(LEGACY_FORMAT, version.major()));
        pack.addProperty("min_format", LEGACY_FORMAT);
        pack.add("max_format", range(version.major(), version.minor()));
        try (VfsWriteTransaction transaction = vfs.beginTransaction()) {
            transaction.putString(metadata, root.toString());
            transaction.commit();
        }
    }

    private static JsonArray range(int first, int last) {
        JsonArray range = new JsonArray(2);
        range.add(first);
        range.add(last);
        return range;
    }
}
