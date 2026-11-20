package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.core.rendering.mixin.FrontendGpuDeviceAccess;
import lib.kasuga.core.rendering.mixin.GlDeviceWindowAccess;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.output.FrameWindowOptions;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/** The SDL window and surface borrow the game's device and command encoder. */
final class RenderPearlPreviewWindow implements AutoCloseable {
    private final long mainWindow;
    private final GpuSurface mainSurface;
    private final GpuSurface surface;
    private final GlDeviceWindowAccess glDevice;
    private final SDL_EventFilter eventWatch;
    private final GpuSurface.PresentMode mode;
    private long window;
    private long presentations;
    private volatile boolean closeRequested;
    private boolean reconfigure;
    private TextureTarget scaled;

    RenderPearlPreviewWindow(Minecraft client, FrameWindowOptions options) {
        mainWindow = client.getWindow().handle();
        mainSurface = client.windowSurface();
        long graphicsFlag = SDLVideo.SDL_GetWindowFlags(mainWindow) & (SDLVideo.SDL_WINDOW_OPENGL | SDLVideo.SDL_WINDOW_VULKAN);
        if (graphicsFlag == 0) throw new IllegalStateException("Native output requires a display window");
        glDevice = graphicsFlag == SDLVideo.SDL_WINDOW_OPENGL
                ? (GlDeviceWindowAccess) ((FrontendGpuDeviceAccess) RenderSystem.getDevice()).kasuga$backend() : null;
        window = SDLVideo.SDL_CreateWindow(options.title(), options.width(), options.height(), graphicsFlag
                | SDLVideo.SDL_WINDOW_HIDDEN | SDLVideo.SDL_WINDOW_RESIZABLE | SDLVideo.SDL_WINDOW_HIGH_PIXEL_DENSITY);
        if (window == 0) throw sdlFailure("Could not create a frame output window");
        int windowId = SDLVideo.SDL_GetWindowID(window);
        eventWatch = SDL_EventFilter.create((userData, eventPointer) -> {
            SDL_Event event = SDL_Event.create(eventPointer);
            if (event.type() == SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED && event.window().windowID() == windowId) {
                closeRequested = true;
            }
            return true;
        });
        GpuSurface created = null;
        boolean watching = false;
        try {
            if (!SDLEvents.SDL_AddEventWatch(eventWatch, 0)) throw sdlFailure("Could not watch for window closure");
            watching = true;
            created = RenderSystem.getDevice().createSurface(window, this::isMinimized);
            mode = GpuSurface.PresentMode.getSupportedVsyncMode(created.supportedPresentModes(), false);
            if (options.visible() && !SDLVideo.SDL_ShowWindow(window)) throw sdlFailure("Could not show window");
            surface = created;
        } catch (RuntimeException failure) {
            try {
                if (created != null) created.close();
            } finally {
                if (watching) SDLEvents.SDL_RemoveEventWatch(eventWatch, 0);
                eventWatch.free();
                SDLVideo.SDL_DestroyWindow(window);
            }
            throw failure;
        }
    }

    long handle() { return window; }
    long presentations() { return presentations; }
    boolean closeRequested() { return closeRequested; }
    boolean isAcquired() { return surface.isAcquired(); }
    void requestClose() { closeRequested = true; }

    int[] framebufferSize() {
        if (window != 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                if (SDLVideo.SDL_GetWindowSizeInPixels(window, width, height)) {
                    return new int[]{width.get(0), height.get(0)};
                }
            }
        }
        return new int[]{0, 0};
    }

    void resize(int width, int height) {
        if (window == 0) throw new IllegalStateException("Frame output window is closed");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Window dimensions must be positive");
        if (!SDLVideo.SDL_SetWindowSize(window, width, height)) throw sdlFailure("Could not resize window");
    }

    void blit(GpuTextureView color) {
        if (closeRequested || surface.isAcquired() || isMinimized()) return;
        int[] size = framebufferSize();
        int width = size[0];
        int height = size[1];
        if (width <= 0 || height <= 0) return;
        GpuTextureView image = fitToSurface(color, width, height);
        GpuSurface.Configuration configuration = new GpuSurface.Configuration(width, height, mode);
        try {
            if (reconfigure || surface.isSuboptimal() || !configuration.equals(surface.currentConfiguration().orElse(null))) {
                surface.configure(configuration);
                reconfigure = false;
            }
            surface.acquireNextTexture();
            surface.blitFromTexture(RenderSystem.getDevice().createCommandEncoder(), image);
            if (glDevice != null) {
                SDLVideo.SDL_GL_SetSwapInterval(0);
                presentSubmittedFrame();
            }
        } catch (SurfaceException temporary) {
            // Compositors can invalidate the surface during resize or minimization. Retry on the next frame.
            reconfigure = true;
        } finally {
            if (glDevice != null && SDLVideo.SDL_GL_GetCurrentWindow() != mainWindow) {
                glDevice.kasuga$makeCurrent(mainWindow);
                GpuSurface.Configuration mainConfiguration = mainSurface.currentConfiguration().orElse(null);
                if (mainConfiguration != null) {
                    SDLVideo.SDL_GL_SetSwapInterval(mainConfiguration.presentMode() == GpuSurface.PresentMode.FIFO ? 1 : 0);
                }
            }
        }
    }

    void presentSubmittedFrame() {
        if (!surface.isAcquired()) return;
        surface.present();
        presentations++;
    }

    private GpuTextureView fitToSurface(GpuTextureView color, int width, int height) {
        if (width == color.getWidth(0) && height == color.getHeight(0)) {
            releaseScaled();
            return color;
        }
        if (scaled == null) {
            scaled = new TextureTarget("Kasuga frame output window", width, height, GpuFormat.RGBA8_UNORM, null);
        } else if (scaled.width != width || scaled.height != height) {
            scaled.resize(width, height);
        }
        GpuTextureView view = scaled.getColorTextureView();
        PostProcessContext.fitColor(color, view);
        return view;
    }

    private boolean isMinimized() {
        return (SDLVideo.SDL_GetWindowFlags(window) & SDLVideo.SDL_WINDOW_MINIMIZED) != 0;
    }

    private void releaseScaled() {
        if (scaled == null) return;
        scaled.destroyBuffers();
        scaled = null;
    }

    @Override
    public void close() {
        if (window == 0) return;
        surface.close();
        releaseScaled();
        SDLEvents.SDL_RemoveEventWatch(eventWatch, 0);
        eventWatch.free();
        SDLVideo.SDL_DestroyWindow(window);
        window = 0;
    }

    private static IllegalStateException sdlFailure(String operation) {
        return new IllegalStateException(operation + ": " + SDLError.SDL_GetError());
    }
}
