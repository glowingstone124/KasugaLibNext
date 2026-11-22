package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo.TBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.texture.GpuTexture2D;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Seamless noise/Worley atlases and a compact per-frame RGBA32F simulation TBO. */
final class NebulaTextures extends NebulaTextureData {
    private ByteBuffer dataStorage;
    private FloatBuffer dataBuffer;
    private GpuTexture2D noiseTexture;
    private GpuTexture2D cellTexture;
    private TBOBuffer dataBufferObject;
    private boolean lastDeleteVerified = true;

    int noiseTexture() { return noiseTexture.id(); }
    int cellTexture() { return cellTexture.id(); }
    TBOBuffer dataBuffer() { return dataBufferObject; }
    boolean allocated() { return noiseTexture != null || cellTexture != null || dataBufferObject != null; }
    boolean lastDeleteVerified() { return lastDeleteVerified; }

    void ensureReady() {
        RenderSystem.assertOnRenderThread();
        if (noiseTexture != null && cellTexture != null && dataBufferObject != null
                && noiseTexture.isOpen() && cellTexture.isOpen() && !dataBufferObject.isClosed()) {
            return;
        }
        deleteNow();
        ByteBuffer noise = BufferUtils.createByteBuffer(NOISE_SIZE * NOISE_SIZE * 4);
        noise.put(buildNoiseLutRgba()).flip();
        ByteBuffer cell = BufferUtils.createByteBuffer(CELL_ATLAS_WIDTH * CELL_ATLAS_HEIGHT * 4);
        cell.put(buildWorleyAtlasRgba()).flip();
        noiseTexture = GpuTexture2D.rgba8(NOISE_SIZE, NOISE_SIZE, org.lwjgl.opengl.GL11.GL_REPEAT, noise);
        cellTexture = GpuTexture2D.rgba8(CELL_ATLAS_WIDTH, CELL_ATLAS_HEIGHT, GL12.GL_CLAMP_TO_EDGE, cell);
        int byteCount = DATA_WIDTH * DATA_HEIGHT * 4 * Float.BYTES;
        dataStorage = BufferUtils.createByteBuffer(byteCount).order(ByteOrder.nativeOrder());
        dataBuffer = dataStorage.asFloatBuffer();
        for (int i = 0; i < DATA_WIDTH * DATA_HEIGHT * 4; i++) dataBuffer.put(0f);
        dataBuffer.flip();
        dataStorage.position(0).limit(byteCount);
        dataBufferObject = new TBOBuffer(() -> 7, dataStorage, GL15.GL_DYNAMIC_DRAW);
    }

    void uploadFrameData(float time, Vector3f eye, Vector3f camForward, float[] wake,
                         float[] lightData, int lightCount, float[] boltData, int segmentCount,
                         NebulaFlight.Pose craft, NebulaFlight.WarpState warp) {
        ensureReady();
        writeFrame(dataBuffer, time, eye, camForward, wake, lightData, lightCount, boltData, segmentCount, craft, warp);
        dataStorage.position(0).limit(dataStorage.capacity());
        dataBufferObject.updateAll(dataStorage);
    }

    void release() {
        if (RenderSystem.isOnRenderThread()) deleteNow();
        else RenderSystem.recordRenderCall(this::deleteNow);
    }

    private void deleteNow() {
        RenderSystem.assertOnRenderThread();
        boolean verified = true;
        if (noiseTexture != null) {
            int retired = noiseTexture.id();
            noiseTexture.close(); verified &= !org.lwjgl.opengl.GL11.glIsTexture(retired); noiseTexture = null;
        }
        if (cellTexture != null) {
            int retired = cellTexture.id();
            cellTexture.close(); verified &= !org.lwjgl.opengl.GL11.glIsTexture(retired); cellTexture = null;
        }
        if (dataBufferObject != null) {
            int retiredBuffer = dataBufferObject.getBufferId(), retiredTexture = dataBufferObject.getTextureId();
            try { dataBufferObject.close(); } catch (Exception failure) { verified = false; }
            verified &= dataBufferObject.isClosed() && !GL15.glIsBuffer(retiredBuffer)
                    && !org.lwjgl.opengl.GL11.glIsTexture(retiredTexture);
            dataBufferObject = null;
        }
        dataBuffer = null;
        dataStorage = null;
        lastDeleteVerified = verified;
    }
}
