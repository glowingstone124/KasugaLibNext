package lib.kasuga.rendering.models.mc.component;

import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VfsWriteTransaction;
import lib.kasuga.core.vfs.VirtualFileSystem;

import java.io.IOException;

final class LivePreviewPack {
    private LivePreviewPack() {}

    static void ensureMetadata(VirtualFileSystem vfs, VfsPath metadata) throws IOException {
        if (vfs.exists(metadata)) return;
        try (VfsWriteTransaction tx = vfs.beginTransaction()) {
            tx.putString(metadata, "{\"pack\":{\"pack_format\":34,\"description\":\"Kasuga Blockbench live preview\"}}");
            tx.commit();
        }
    }
}
