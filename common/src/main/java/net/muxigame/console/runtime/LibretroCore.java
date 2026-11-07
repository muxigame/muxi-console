package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Our binding of the public MIT libretro ABI. Never referenced by the Minecraft-side API. */
final class LibretroCore implements AutoCloseable {
    private static final int MAX_PIXELS = 2048 * 2048, MAX_MEMORY = 24 * 1024 * 1024;
    private static final int STATE_MAGIC = 0x4d585331;
    private final Api api;
    private final Thread thread = Thread.currentThread();
    private final List<Memory> strings = new ArrayList<>();
    private final Map<String, String> options = new HashMap<>(), defaults = new HashMap<>();
    private final Map<String, Memory> optionStrings = new HashMap<>();
    private final Memory systemPath, savePath, libraryPath, contentPath;
    private Memory contentMemory;
    private InputFrame input = InputFrame.idle();
    private int pixelFormat, width, height, rotation;
    private double fps = 60, sampleRate = 44100, displayAspect;
    private long number;
    private byte[] pixels = new byte[0];
    private short[] samples = new short[8192];
    private int sampleCount;
    private boolean initialized, loaded, noGame, shutdown, hardwareRequested, executionStarted;
    private volatile String callbackFailure;
    private final SystemInfo systemInfo = new SystemInfo();
    private HardwareVideo hardware;
    private int maximumWidth, maximumHeight;
    private DiskControl disks;
    // The public logger is variadic. Its fixed ABI prefix is all this no-op sink
    // consumes; extra native arguments are deliberately never dereferenced.
    private final Log log = (level, format) -> { };

    private final Environment environment = (command, data) -> {
        try { return (byte) (environment(command, data) ? 1 : 0); }
        catch (Throwable ex) { callbackFailure = "Core environment callback: " + ex.getMessage(); return 0; }
    };
    private final Video video = (data, w, h, pitch) -> {
        try { video(data, w, h, pitch.longValue()); }
        catch (Throwable ex) { callbackFailure = "Core video callback: " + ex.getMessage(); }
    };
    private final Audio audio = (left, right) -> {
        try { appendAudio(left, right); }
        catch (Throwable ex) { callbackFailure = "Core audio callback: " + ex.getMessage(); }
    };
    private final AudioBatch audioBatch = (data, frames) -> {
        try {
            long count = frames.longValue();
            if (count < 0 || count > 192000 || data == null) throw new IllegalArgumentException("Invalid audio frame count");
            for (int i = 0; i < count; i++) appendAudio(data.getShort(i * 4L), data.getShort(i * 4L + 2));
            return new SizeT(count);
        } catch (Throwable ex) { callbackFailure = "Core audio callback: " + ex.getMessage(); return new SizeT(0); }
    };
    private final InputPoll inputPoll = () -> {
        if (Thread.currentThread() != thread) callbackFailure = "Core invoked asynchronous input polling";
    };
    private final InputState inputState = (port, device, index, id) -> {
        if (Thread.currentThread() != thread) { callbackFailure = "Core invoked asynchronous input state"; return 0; }
        if (port < 0 || port >= 4) return 0;
        PortInput state = input.port(port);
        if (device == 1) {
            if (id == 256) return (short) state.buttons();
            if (id >= 0 && id < 16) return (short) ((state.buttons() >>> id) & 1);
        }
        if (device == 5 && (id == 0 || id == 1)) {
            if (index == 0) return id == 0 ? state.leftX() : state.leftY();
            if (index == 1) return id == 0 ? state.rightX() : state.rightY();
        }
        return 0;
    };

    LibretroCore(Path library, Path content, Path system, Path save, Map<String, String> configured) throws IOException {
        options.putAll(configured);
        systemPath = string(system.toAbsolutePath().toString()); savePath = string(save.toAbsolutePath().toString());
        libraryPath = string(library.toAbsolutePath().toString());
        contentPath = string(content == null ? "" : content.toAbsolutePath().getParent().toString());
        api = Native.load(library.toAbsolutePath().toString(), Api.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        try {
            if (api.retro_api_version() != 1) throw new IOException("Unsupported libretro API version");
            api.retro_set_environment(environment); api.retro_set_video_refresh(video);
            api.retro_set_audio_sample(audio); api.retro_set_audio_sample_batch(audioBatch);
            api.retro_set_input_poll(inputPoll); api.retro_set_input_state(inputState);
            api.retro_get_system_info(systemInfo); systemInfo.read();
            api.retro_init(); initialized = true;
            GameInfo game = null;
            if (content != null) {
                game = new GameInfo(); game.path = string(content.toAbsolutePath().toString());
                if (systemInfo.need_fullpath == 0) {
                    long size = Files.size(content);
                    if (size <= 0 || size > 128 * 1024 * 1024) throw new IOException("In-memory content must be between 1 byte and 128 MiB");
                    byte[] raw = Files.readAllBytes(content); contentMemory = new Memory(raw.length);
                    contentMemory.write(0, raw, 0, raw.length); game.data = contentMemory; game.size = new SizeT(raw.length);
                }
                game.write();
            } else if (!noGame) throw new IOException("Core requires game content");
            if (api.retro_load_game(game == null ? null : game.getPointer()) == 0) {
                if (hardwareRequested) throw new IOException("Core rejected the available desktop OpenGL hardware renderer");
                throw new IOException("Core rejected game content");
            }
            loaded = true;
            for (int port = 0; port < 4; port++) api.retro_set_controller_port_device(port, 1);
            AvInfo av = new AvInfo(); api.retro_get_system_av_info(av); av.read(); setAv(av);
            if (hardware != null) hardware.initialize(maximumWidth, maximumHeight);
            checkCallback();
        } catch (IOException | RuntimeException | LinkageError ex) {
            try { close(); } catch (IOException | RuntimeException | LinkageError cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }

    CoreInfo info() { return new CoreInfo(readString(systemInfo.library_name), readString(systemInfo.library_version), width, height, fps, sampleRate, displayAspect); }
    AvFrame step(InputFrame input) throws IOException {
        checkThread(); this.input = input; sampleCount = 0; callbackFailure = null;
        if (!shutdown) runCore(); checkCallback();
        return new AvFrame(number++, width, height, rotation, pixels.clone(), Arrays.copyOf(samples, sampleCount), sampleRate, shutdown, displayAspect);
    }
    void reset() throws IOException { checkThread(); api.retro_reset(); checkCallback(); }
    byte[] readSram() throws IOException { checkThread(); return readMemory(0); }
    void writeSram(byte[] data) throws IOException { checkThread(); validateMemory(0, data); writeMemory(0, data); }

    private record SavedState(byte[] core, byte[] sram, byte[] rtc) { }
    private void runCore() {
        if (hardware != null) hardware.beforeFrame();
        executionStarted = true; api.retro_run();
    }
    private void ensureHardwareExecutionStarted() throws IOException {
        if (hardware != null && loaded && !executionStarted) {
            // Some hardware cores finish initialization on their first coroutine
            // entry, rather than retro_load_game/context_reset. Until then they
            // cannot serialize the rollback state or stop that coroutine safely.
            input = InputFrame.idle(); sampleCount = 0; runCore(); checkCallback();
        }
    }
    private SavedState captureState() throws IOException {
        ensureHardwareExecutionStarted();
        for (int attempt = 0; attempt < 2; attempt++) {
            int size = bounded(api.retro_serialize_size().longValue(), "Core state");
            if (size == 0) throw new IOException("Core does not support save states");
            try (Memory buffer = new Memory(size)) {
                byte saved = api.retro_serialize(buffer, new SizeT(size));
                checkCallback();
                if (saved != 0) return new SavedState(buffer.getByteArray(0, size), readMemory(0), readMemory(1));
            }
            if (executionStarted || attempt != 0) throw new IOException("Core refused to save state");
            // SameBoy advertises a state size before its first retro_run, but
            // refuses serialization until that first frame initializes it.
            // Only a rejected, never-executed core gets this one-frame retry;
            // already-ready cores retain their original SRAM and boot semantics.
            input = InputFrame.idle(); sampleCount = 0; runCore(); checkCallback();
        }
        throw new IOException("Core refused to save state after initialization");
    }
    byte[] saveState() throws IOException {
        checkThread(); SavedState state = captureState();
        return Wire.body(out -> { out.writeInt(STATE_MAGIC); Wire.bytes(out, state.core); Wire.bytes(out, state.sram); Wire.bytes(out, state.rtc); });
    }
    private byte[] statePart(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_MEMORY || length > in.available()) throw new IOException("Invalid saved state component size");
        return in.readNBytes(length);
    }
    private SavedState parseState(byte[] state) throws IOException {
        if (state == null || state.length < 16 || state.length > Wire.MAX_BYTES) throw new IOException("Invalid saved state envelope size");
        var in = Wire.input(state);
        if (in.readInt() != STATE_MAGIC) throw new IOException("Invalid Muxi Console state envelope");
        SavedState target = new SavedState(statePart(in), statePart(in), statePart(in)); Wire.end(in);
        if (target.core.length == 0) throw new IOException("Empty core state");
        return target;
    }
    private void applyState(SavedState state) throws IOException {
        try (Memory buffer = new Memory(state.core.length)) {
            buffer.write(0, state.core, 0, state.core.length);
            if (api.retro_unserialize(buffer, new SizeT(state.core.length)) == 0) throw new IOException("Core rejected saved state");
        }
        // MXS1 deliberately contains no frontend geometry metadata. Query the
        // restored core's public AV information, including states saved before v2.
        AvInfo av = new AvInfo(); api.retro_get_system_av_info(av); av.read(); setAv(av);
        // The serialized core may establish the cartridge memory type. For example,
        // a fresh mGBA instance exposes 128 KiB until SRAM autodetection selects 32 KiB.
        validateMemory(0, state.sram); validateMemory(1, state.rtc);
        writeMemory(0, state.sram); writeMemory(1, state.rtc); checkCallback();
    }
    void loadState(byte[] state) throws IOException {
        checkThread(); SavedState target = parseState(state); checkCallback();
        SavedState prior = captureState();
        InputFrame priorInput = input; boolean priorShutdown = shutdown;
        int priorWidth = width, priorHeight = height, priorRotation = rotation, priorPixelFormat = pixelFormat, priorSampleCount = sampleCount;
        double priorFps = fps, priorRate = sampleRate, priorAspect = displayAspect; byte[] priorPixels = pixels; short[] priorSamples = samples.clone();
        try {
            applyState(target); input = InputFrame.idle(); sampleCount = 0; shutdown = false;
        } catch (IOException | RuntimeException | LinkageError failure) {
            try {
                callbackFailure = null; applyState(prior);
                input = priorInput; shutdown = priorShutdown; width = priorWidth; height = priorHeight; rotation = priorRotation; pixelFormat = priorPixelFormat;
                fps = priorFps; sampleRate = priorRate; displayAspect = priorAspect; pixels = priorPixels; samples = priorSamples; sampleCount = priorSampleCount;
            } catch (IOException | RuntimeException | LinkageError rollbackFailure) {
                try { close(); } catch (RuntimeException | LinkageError closeFailure) { rollbackFailure.addSuppressed(closeFailure); }
                IOException fatal = new IOException("Saved state failed and rollback failed; emulator core stopped", failure);
                fatal.addSuppressed(rollbackFailure); throw fatal;
            }
            throw new IOException("Saved state rejected; previous running state restored: " + failure.getMessage(), failure);
        }
    }

    private byte[] readMemory(int id) throws IOException {
        int size = bounded(api.retro_get_memory_size(id).longValue(), "Cartridge memory");
        Pointer data = api.retro_get_memory_data(id);
        if (size != 0 && data == null) throw new IOException("Core returned inaccessible cartridge memory");
        return size == 0 ? new byte[0] : data.getByteArray(0, size);
    }
    private void validateMemory(int id, byte[] data) throws IOException {
        int size = bounded(api.retro_get_memory_size(id).longValue(), "Cartridge memory");
        if (data.length != size || (size != 0 && api.retro_get_memory_data(id) == null)) throw new IOException("Cartridge memory size differs from loaded content");
    }
    private void writeMemory(int id, byte[] data) { if (data.length != 0) api.retro_get_memory_data(id).write(0, data, 0, data.length); }
    private int bounded(long value, String label) throws IOException {
        if (value < 0 || value > MAX_MEMORY) throw new IOException(label + " exceeds 24 MiB"); return (int) value;
    }

    private boolean environment(int command, Pointer data) throws IOException {
        if (Thread.currentThread() != thread) throw new IOException("Core invoked an asynchronous environment callback");
        return switch (command) {
            case 1 -> { int value = data.getInt(0); if (value < 0 || value > 3) yield false; rotation = value; yield true; }
            case 2 -> { data.setByte(0, (byte) 0); yield true; }
            case 3 -> { data.setByte(0, (byte) 1); yield true; }
            case 6, 8, 11, 35 -> true;
            case 7 -> { shutdown = true; yield true; }
            case 9 -> { data.setPointer(0, systemPath); yield true; }
            case 10 -> { int format = data.getInt(0); if (format < 0 || format > 2) yield false; pixelFormat = format; yield true; }
            case 13, 58 -> { disks = data == null ? null : new DiskControl(data); yield true; }
            case 14, 14 | 0x10000 -> {
                hardwareRequested = true;
                if (data == null || hardware != null) yield false;
                int type = data.getInt(0); if (type != 1 && type != 3) yield false;
                hardware = new HardwareVideo(data); yield true;
            }
            case 15 -> {
                String key = readString(data.getPointer(0)), value = options.getOrDefault(key, defaults.get(key));
                if (value == null) { data.setPointer(Native.POINTER_SIZE, null); yield false; }
                Memory text = optionStrings.computeIfAbsent(key + "\u0000" + value, ignored -> string(value));
                data.setPointer(Native.POINTER_SIZE, text); yield true;
            }
            case 16 -> {
                if (data != null) for (int i = 0; i < 256; i++) {
                    long offset = i * 2L * Native.POINTER_SIZE; Pointer key = data.getPointer(offset); if (key == null) break;
                    String definition = readString(data.getPointer(offset + Native.POINTER_SIZE)); int separator = definition.indexOf(';');
                    if (separator >= 0) defaults.put(readString(key), definition.substring(separator + 1).trim().split("\\|", 2)[0]);
                }
                yield true;
            }
            case 17 -> { data.setByte(0, (byte) 0); yield true; }
            case 18 -> { noGame = data.getByte(0) != 0; yield true; }
            case 19 -> { data.setPointer(0, libraryPath); yield true; }
            case 24 -> { data.setLong(0, (1L << 1) | (1L << 5)); yield true; }
            case 27 -> { data.setPointer(0, CallbackReference.getFunctionPointer(log)); yield true; }
            case 30 -> { data.setPointer(0, contentPath); yield true; }
            case 31 -> { data.setPointer(0, savePath); yield true; }
            case 32 -> { setAv(new AvInfo(data)); yield true; }
            case 37 -> { setGeometry(new Geometry(data)); yield true; }
            case 38 -> { data.setPointer(0, string("Muxi Console")); yield true; }
            case 39 -> { data.setInt(0, 0); yield true; }
            case 47 | 0x10000 -> { data.setInt(0, 3); yield true; }
            case 51 | 0x10000 -> true;
            case 52 -> { data.setInt(0, 1); yield true; }
            case 53 -> { readOptions(data, false); yield true; }
            case 54 -> { readOptions(data == null ? null : data.getPointer(0), false); yield true; }
            case 56 -> { data.setInt(0, 3); yield true; }
            case 57 -> { data.setInt(0, 1); yield true; }
            // Published in SameBoy's bundled libretro ABI: an unsigned Hz target.
            // Negotiate an output-device rate instead of its multi-MHz fallback.
            case 81 | 0x10000 -> { data.setInt(0, 48000); yield true; }
            case 67 -> { readOptions(data == null ? null : data.getPointer(Native.POINTER_SIZE), true); yield false; }
            case 68 -> {
                Pointer us = data == null ? null : data.getPointer(0);
                readOptions(us == null ? null : us.getPointer(Native.POINTER_SIZE), true); yield false;
            }
            default -> false;
        };
    }

    private void readOptions(Pointer definitions, boolean v2) {
        if (definitions == null) return;
        int leading = v2 ? 6 : 3, fields = leading + 256 + 1;
        for (int i = 0; i < 256; i++) {
            long base = (long) i * fields * Native.POINTER_SIZE; Pointer key = definitions.getPointer(base); if (key == null) break;
            Pointer value = definitions.getPointer(base + (long) (fields - 1) * Native.POINTER_SIZE);
            if (value == null) value = definitions.getPointer(base + (long) leading * Native.POINTER_SIZE);
            if (value != null) defaults.put(readString(key), readString(value));
        }
    }
    private void setAv(AvInfo info) throws IOException {
        setGeometry(info.geometry);
        if (!Double.isFinite(info.timing.fps) || info.timing.fps <= 0 || info.timing.fps > 1000 ||
                !Double.isFinite(info.timing.sample_rate) || info.timing.sample_rate < 0 || info.timing.sample_rate > 192000)
            throw new IOException("Unsupported core audio/video timing: fps=" + info.timing.fps + ", sampleRate=" + info.timing.sample_rate);
        fps = info.timing.fps; sampleRate = info.timing.sample_rate;
    }
    private void setGeometry(Geometry geometry) throws IOException {
        if (geometry.base_width <= 0 || geometry.base_height <= 0 || (long) geometry.base_width * geometry.base_height > MAX_PIXELS)
            throw new IOException("Unsupported core video geometry");
        if (!Float.isFinite(geometry.aspect_ratio)) throw new IOException("Invalid core display aspect");
        displayAspect = geometry.aspect_ratio > 0 ? geometry.aspect_ratio : 0;
        maximumWidth = Math.max(geometry.base_width, geometry.max_width);
        maximumHeight = Math.max(geometry.base_height, geometry.max_height);
        if (pixels.length == 0) { width = geometry.base_width; height = geometry.base_height; pixels = new byte[width * height * 4]; }
    }
    private void video(Pointer data, int w, int h, long pitch) throws IOException {
        checkThread();
        if (data == null) return; // Public ABI: repeat the previous complete frame.
        if (Pointer.nativeValue(data) == -1L) {
            if (hardware == null) throw new IOException("Hardware frame received without a negotiated renderer");
            pixels = hardware.read(w, h); width = w; height = h; return;
        }
        int bpp = pixelFormat == 1 ? 4 : 2;
        if (w <= 0 || h <= 0 || (long) w * h > MAX_PIXELS || pitch < (long) w * bpp || pitch > 1024 * 1024)
            throw new IOException("Unsupported video frame size/pitch");
        byte[] rgba = new byte[w * h * 4];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            long source = y * pitch + (long) x * bpp; int red, green, blue;
            if (pixelFormat == 1) { int color = data.getInt(source); red = (color >>> 16) & 255; green = (color >>> 8) & 255; blue = color & 255; }
            else {
                int color = data.getShort(source) & 0xffff;
                if (pixelFormat == 2) { red = ((color >>> 11) & 31) * 255 / 31; green = ((color >>> 5) & 63) * 255 / 63; }
                else { red = ((color >>> 10) & 31) * 255 / 31; green = ((color >>> 5) & 31) * 255 / 31; }
                blue = (color & 31) * 255 / 31;
            }
            int target = (y * w + x) * 4; rgba[target] = (byte) red; rgba[target + 1] = (byte) green; rgba[target + 2] = (byte) blue; rgba[target + 3] = (byte) 255;
        }
        width = w; height = h; pixels = rgba;
    }
    private void appendAudio(short left, short right) throws IOException {
        checkThread(); if (sampleCount >= 192000 * 2) throw new IOException("Core produced excessive audio in one frame");
        if (sampleCount + 2 > samples.length) samples = Arrays.copyOf(samples, Math.min(192000 * 2, samples.length * 2));
        samples[sampleCount++] = left; samples[sampleCount++] = right;
    }
    private void checkThread() throws IOException { if (Thread.currentThread() != thread || !loaded) throw new IOException("Core call outside its active worker thread"); }
    private void checkCallback() throws IOException { if (callbackFailure != null) throw new IOException(callbackFailure); }
    private Memory string(String value) {
        byte[] raw = value.getBytes(java.nio.charset.StandardCharsets.UTF_8); Memory memory = new Memory(raw.length + 1L);
        memory.write(0, raw, 0, raw.length); memory.setByte(raw.length, (byte) 0); strings.add(memory); return memory;
    }
    private static String readString(Pointer pointer) { return pointer == null ? "" : pointer.getString(0, "UTF-8"); }
    @Override public void close() throws IOException {
        IOException startupFailure = null;
        try { ensureHardwareExecutionStarted(); } catch (IOException failure) { startupFailure = failure; }
        if (loaded) { api.retro_unload_game(); loaded = false; }
        if (hardware != null) hardware.notifyContextDestroyed();
        if (initialized) { api.retro_deinit(); initialized = false; }
        if (hardware != null) { hardware.close(); hardware = null; }
        if (contentMemory != null) { contentMemory.close(); contentMemory = null; }
        for (Memory memory : strings) memory.close(); strings.clear();
        if (startupFailure != null) throw startupFailure;
    }

    DiskState diskState() throws IOException {
        checkThread(); if (disks == null) return new DiskState(0, 0, true);
        int count = disks.unsigned(4), index = disks.unsigned(2);
        if (count < 0 || count > 256 || index < 0 || index > count) throw new IOException("Invalid disk state from core");
        return new DiskState(count, index, disks.bool(1));
    }
    void selectDisk(int index) throws IOException {
        checkThread(); DiskState state = diskState();
        if (disks == null || index < 0 || index >= state.count()) throw new IOException("Disk index is unavailable");
        if (!disks.bool(0, (byte) 1)) throw new IOException("Core refused disk eject");
        if (!disks.bool(3, index)) {
            disks.bool(3, state.index()); disks.bool(0, (byte) (state.ejected() ? 1 : 0));
            throw new IOException("Core refused disk selection");
        }
        if (!disks.bool(0, (byte) 0)) throw new IOException("Core refused disk insertion");
        checkCallback();
    }
    void setDiskEjected(boolean ejected) throws IOException {
        checkThread();
        if (disks == null || !disks.bool(0, (byte) (ejected ? 1 : 0))) throw new IOException("Core refused disk eject/insert");
        checkCallback();
    }
    /** Both public disk structs share these first seven function pointers. */
    private static final class DiskControl {
        private final Function[] functions = new Function[7];
        DiskControl(Pointer data) throws IOException {
            for (int i = 0; i < functions.length; i++) {
                Pointer function = data.getPointer((long) i * Native.POINTER_SIZE);
                if (function == null && i < 5) throw new IOException("Incomplete disk control interface");
                if (function != null) functions[i] = Function.getFunction(function);
            }
        }
        int unsigned(int index) { return functions[index].invokeInt(new Object[0]); }
        boolean bool(int index, Object... arguments) { return ((Number) functions[index].invoke(Byte.class, arguments)).byteValue() != 0; }
    }

    public interface Environment extends Callback { byte invoke(int command, Pointer data); }
    public interface Video extends Callback { void invoke(Pointer data, int width, int height, SizeT pitch); }
    public interface Audio extends Callback { void invoke(short left, short right); }
    public interface AudioBatch extends Callback { SizeT invoke(Pointer data, SizeT frames); }
    public interface InputPoll extends Callback { void invoke(); }
    public interface InputState extends Callback { short invoke(int port, int device, int index, int id); }
    public interface Log extends Callback { void invoke(int level, Pointer format); }
    public static final class SizeT extends IntegerType {
        public SizeT() { this(0); }
        public SizeT(long value) { super(Native.SIZE_T_SIZE, value, true); }
    }
    public interface Api extends Library {
        int retro_api_version(); void retro_set_environment(Environment callback); void retro_set_video_refresh(Video callback);
        void retro_set_audio_sample(Audio callback); void retro_set_audio_sample_batch(AudioBatch callback);
        void retro_set_input_poll(InputPoll callback); void retro_set_input_state(InputState callback);
        void retro_get_system_info(SystemInfo info); void retro_get_system_av_info(AvInfo info);
        void retro_init(); void retro_deinit(); byte retro_load_game(Pointer info); void retro_unload_game();
        void retro_set_controller_port_device(int port, int device); void retro_run(); void retro_reset();
        SizeT retro_serialize_size(); byte retro_serialize(Pointer data, SizeT size); byte retro_unserialize(Pointer data, SizeT size);
        Pointer retro_get_memory_data(int id); SizeT retro_get_memory_size(int id);
    }
    @Structure.FieldOrder({"library_name", "library_version", "valid_extensions", "need_fullpath", "block_extract"})
    public static final class SystemInfo extends Structure {
        public Pointer library_name, library_version, valid_extensions;
        public byte need_fullpath, block_extract;
    }
    @Structure.FieldOrder({"path", "data", "size", "meta"})
    public static final class GameInfo extends Structure {
        public Pointer path, data; public SizeT size = new SizeT(); public Pointer meta;
    }
    @Structure.FieldOrder({"base_width", "base_height", "max_width", "max_height", "aspect_ratio"})
    public static final class Geometry extends Structure {
        public int base_width, base_height, max_width, max_height; public float aspect_ratio;
        public Geometry() {} public Geometry(Pointer pointer) { super(pointer); read(); }
    }
    @Structure.FieldOrder({"fps", "sample_rate"})
    public static final class Timing extends Structure { public double fps, sample_rate; }
    @Structure.FieldOrder({"geometry", "timing"})
    public static final class AvInfo extends Structure {
        public Geometry geometry = new Geometry(); public Timing timing = new Timing();
        public AvInfo() {} public AvInfo(Pointer pointer) { super(pointer); read(); }
    }
}
