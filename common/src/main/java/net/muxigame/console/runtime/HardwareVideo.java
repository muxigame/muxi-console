package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

/** Worker-only hidden OpenGL context implementing the public libretro hardware ABI. */
final class HardwareVideo implements AutoCloseable {
    private final Request request;
    private long window;
    private int framebuffer, texture, depthBuffer, width, height;
    private boolean reset;
    private final Framebuffer framebufferCallback = () -> new LibretroCore.SizeT(framebuffer);
    private final Procedure procedureCallback = name -> {
        long address = GLFW.glfwGetProcAddress(name.getString(0, "UTF-8"));
        return address == 0 ? null : new Pointer(address);
    };

    HardwareVideo(Pointer pointer) throws IOException {
        request = new Request(pointer);
        if (request.context_type != 1 && request.context_type != 3)
            throw new IOException("Only desktop OpenGL hardware contexts are supported");
        if (request.context_reset == null) throw new IOException("Hardware core has no context reset callback");
        request.get_current_framebuffer = CallbackReference.getFunctionPointer(framebufferCallback);
        request.get_proc_address = CallbackReference.getFunctionPointer(procedureCallback);
        request.cache_context = 0;
        request.write();
    }

    void initialize(int maximumWidth, int maximumHeight) throws IOException {
        if (maximumWidth <= 0 || maximumHeight <= 0 || (long) maximumWidth * maximumHeight > 2048L * 2048)
            throw new IOException("Hardware framebuffer exceeds 2048 x 2048 pixels");
        if (!GLFW.glfwInit()) throw new IOException("Cannot initialize the worker's hidden OpenGL context");
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_DOUBLEBUFFER, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, request.context_type == 3 ? request.version_major : 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, request.context_type == 3 ? request.version_minor : 3);
            if (request.context_type == 3) GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            if (request.debug_context != 0) GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_DEBUG_CONTEXT, GLFW.GLFW_TRUE);
            window = GLFW.glfwCreateWindow(16, 16, "Muxi Console worker", 0, 0);
            if (window == 0) throw new IOException("Cannot create the requested desktop OpenGL context");
            GLFW.glfwMakeContextCurrent(window); GL.createCapabilities();
            resize(maximumWidth, maximumHeight);
            Function.getFunction(request.context_reset).invokeVoid(new Object[0]); reset = true;
        } catch (IOException | RuntimeException | LinkageError failure) { close(); throw failure; }
    }

    private void resize(int w, int h) throws IOException {
        width = w; height = h;
        framebuffer = GL30.glGenFramebuffers(); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        texture = GL11.glGenTextures(); GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        if (request.depth != 0) {
            depthBuffer = GL30.glGenRenderbuffers(); GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, request.stencil != 0 ? GL30.GL_DEPTH24_STENCIL8 : GL14.GL_DEPTH_COMPONENT24, w, h);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, request.stencil != 0 ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthBuffer);
        }
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new IOException("Worker OpenGL framebuffer is incomplete");
    }

    void beforeFrame() {
        GLFW.glfwMakeContextCurrent(window);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
    }

    byte[] read(int w, int h) throws IOException {
        if (w <= 0 || h <= 0 || w > width || h > height) throw new IOException("Hardware frame exceeds the negotiated framebuffer");
        int priorFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int priorPack = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT), priorRow = GL11.glGetInteger(GL12.GL_PACK_ROW_LENGTH);
        int priorSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS), priorSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int priorBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        ByteBuffer raw = MemoryUtil.memAlloc(w * h * 4);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1); GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0); GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, raw);
            byte[] result = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                int sourceY = request.bottom_left_origin != 0 ? h - y - 1 : y;
                raw.get(sourceY * w * 4, result, y * w * 4, w * 4);
            }
            for (int i = 3; i < result.length; i += 4) result[i] = (byte) 255;
            return result;
        } finally {
            MemoryUtil.memFree(raw); GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, priorPack);
            GL11.glPixelStorei(GL12.GL_PACK_ROW_LENGTH, priorRow); GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, priorBuffer);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, priorSkipRows); GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, priorSkipPixels);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, priorFramebuffer);
        }
    }

    void notifyContextDestroyed() {
        if (window == 0 || !reset) return;
        GLFW.glfwMakeContextCurrent(window); reset = false;
        if (request.context_destroy != null) Function.getFunction(request.context_destroy).invokeVoid(new Object[0]);
    }

    @Override public void close() {
        if (window != 0) {
            GLFW.glfwMakeContextCurrent(window);
            try { notifyContextDestroyed(); }
            finally {
                reset = false;
                if (depthBuffer != 0) GL30.glDeleteRenderbuffers(depthBuffer);
                if (texture != 0) GL11.glDeleteTextures(texture);
                if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
                depthBuffer = texture = framebuffer = 0;
                GL.setCapabilities(null); GLFW.glfwMakeContextCurrent(0); GLFW.glfwDestroyWindow(window); window = 0;
                GLFW.glfwTerminate();
            }
        } else GLFW.glfwTerminate();
    }

    public interface Framebuffer extends Callback { LibretroCore.SizeT invoke(); }
    public interface Procedure extends Callback { Pointer invoke(Pointer name); }
    @Structure.FieldOrder({"context_type", "context_reset", "get_current_framebuffer", "get_proc_address", "depth", "stencil", "bottom_left_origin", "version_major", "version_minor", "cache_context", "context_destroy", "debug_context"})
    public static final class Request extends Structure {
        public int context_type;
        public Pointer context_reset, get_current_framebuffer, get_proc_address;
        public byte depth, stencil, bottom_left_origin;
        public int version_major, version_minor;
        public byte cache_context;
        public Pointer context_destroy;
        public byte debug_context;
        public Request() { }
        public Request(Pointer pointer) { super(pointer); read(); }
    }
}
