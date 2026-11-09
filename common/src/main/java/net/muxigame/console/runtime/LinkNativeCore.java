package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Worker-only native access. SameBoy itself performs both CPUs and all cable bit transfers. */
final class LinkNativeCore implements LinkBackend {
    static final int SINGLE_MAGIC = 0x4d585331, LINK_MAGIC = 0x4c4e4b31;
    private static final int[] SRAM = {0x100, 0x300}, RTC = {0x201, 0x401};
    private static final int MAX_STATE = 2 * 1024 * 1024;
    private final LinkRuntime.Config config;
    private final Thread owner = Thread.currentThread();
    private final List<Memory> held = new ArrayList<>();
    private final Map<String, Memory> variables = new HashMap<>();
    private Api api;
    private boolean initialized, loaded, closed, shutdown;
    private int leftButtons, rightButtons;
    private long frameNumber;
    private byte[] pixels = new byte[160 * 288 * 4];
    private double sampleRate = 48000, fps = 59.727500569606;
    private String failure;
    private Memory system;

    private final Environment environment = (command, data) -> {
        if (Thread.currentThread() != owner) { failure = "Asynchronous link environment callback"; return 0; }
        try { return (byte) (environment(command, data) ? 1 : 0); }
        catch (Throwable invalid) { failure = "Invalid link environment callback"; return 0; }
    };
    private final Video video = (data, width, height, pitch) -> {
        if (Thread.currentThread() != owner) { failure = "Asynchronous link video callback"; return; }
        try {
            if (data == null) return;
            if (width != 160 || height != 288 || pitch.longValue() < 640 || pitch.longValue() > 8192)
                throw new IOException();
            byte[] rgba = new byte[160 * 288 * 4];
            for (int row = 0; row < 288; row++) {
                byte[] raw = data.getByteArray(row * pitch.longValue(), 640);
                for (int x = 0; x < 160; x++) {
                    int src = x * 4, dst = (row * 160 + x) * 4;
                    rgba[dst] = raw[src + 2]; rgba[dst + 1] = raw[src + 1]; rgba[dst + 2] = raw[src]; rgba[dst + 3] = (byte) 255;
                }
            }
            pixels = rgba;
        } catch (Throwable invalid) { failure = "Invalid two-machine video frame"; }
    };
    private final Audio audio = (left, right) -> { };
    private final AudioBatch batch = (data, frames) -> frames;
    private final Poll poll = () -> { if (Thread.currentThread() != owner) failure = "Asynchronous link input polling"; };
    private final Input input = (port, device, index, id) -> {
        if (Thread.currentThread() != owner) { failure = "Asynchronous link input callback"; return 0; }
        if (device != 1 || port < 0 || port > 1) return 0;
        int buttons = port == 0 ? leftButtons : rightButtons;
        return id == 256 ? (short) buttons : id >= 0 && id < 16 ? (short) ((buttons >>> id) & 1) : 0;
    };

    LinkNativeCore(LinkRuntime.Config config) throws IOException {
        this.config = config;
        try {
            if (!hash(config.core()).equals(config.coreSHA())) throw new IOException("Link bridge hash differs from its pin");
            api = Native.load(config.core().toAbsolutePath().toString(), Api.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
            if (api.retro_api_version() != 1 || api.muxi_link_abi() != 1) throw new IOException("Unsupported native link ABI");
            system = text(config.systemDirectory().toAbsolutePath().toString());
            option("sameboy_link", "enabled"); option("sameboy_screen_layout", "top-down"); option("sameboy_audio_output", "Mix");
            for (int i = 0; i < 2; i++) {
                option("sameboy_model_" + (i + 1), config.endpoints().get(i).platform().equals("gbc") ? "Game Boy Color" : "Game Boy");
                option("sameboy_mono_palette_" + (i + 1), "greyscale");
            }
            api.retro_set_environment(environment); api.retro_set_video_refresh(video);
            api.retro_set_audio_sample(audio); api.retro_set_audio_sample_batch(batch);
            api.retro_set_input_poll(poll); api.retro_set_input_state(input);
            api.retro_init(); initialized = true;
            Game[] games = (Game[]) new Game().toArray(2);
            for (int i = 0; i < 2; i++) {
                LinkRuntime.Endpoint endpoint = config.endpoints().get(i);
                if (!Files.isRegularFile(endpoint.content(), LinkOption.NOFOLLOW_LINKS)
                        || Files.size(endpoint.content()) < 32768 || Files.size(endpoint.content()) > 8 * 1024 * 1024)
                    throw new IOException("Invalid GB link content file");
                byte[] bytes = Files.readAllBytes(endpoint.content());
                if (!hex(sha().digest(bytes)).equals(endpoint.romSHA())) throw new IOException("Link content hash differs from its endpoint");
                Memory data = new Memory(bytes.length); data.write(0, bytes, 0, bytes.length); held.add(data);
                games[i].path = text(endpoint.content().toAbsolutePath().toString()); games[i].data = data; games[i].size = new SizeT(bytes.length); games[i].write();
            }
            if (api.retro_load_game_special(0x101, games[0].getPointer(), new SizeT(2)) == 0)
                throw new IOException("SameBoy rejected its two-ROM link subsystem");
            loaded = true;
            if (api.muxi_link_audio_attach() == 0) throw new IOException("Independent link audio tap unavailable");
            try (Memory av = new Memory(40)) {
                av.clear(); api.retro_get_system_av_info(av);
                fps = av.getDouble(24); sampleRate = av.getDouble(32);
                if (!Double.isFinite(fps) || fps < 1 || fps > 120 || sampleRate != 48000) throw new IOException("Invalid link core timing");
            }
            checkFailure();
        } catch (IOException | RuntimeException | LinkageError error) {
            try { close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    public CoreInfo info() { return new CoreInfo("SameBoy link", "1.0.5 90e2899 / bridge ABI1", 160, 144, fps, sampleRate, 160.0 / 144); }
    public LinkRuntime.Frame step(PortInput left, PortInput right) throws IOException {
        requireOpen(); leftButtons = left.buttons(); rightButtons = right.buttons(); failure = null;
        api.muxi_link_audio_clear(); if (!shutdown) api.retro_run(); checkFailure();
        if (api.muxi_link_audio_did_overflow() != 0) throw new IOException("Endpoint audio exceeded one-frame limit");
        AvFrame[] frames = new AvFrame[2];
        for (int i = 0; i < 2; i++) {
            short[] pcm;
            try (Memory samples = new Memory(8192 * 2)) {
                int count = api.muxi_link_audio_read(i, samples, 8192);
                if (count < 0 || count > 8192 || (count & 1) != 0) throw new IOException("Invalid endpoint PCM count");
                pcm = samples.getShortArray(0, count);
            }
            byte[] rgba = Arrays.copyOfRange(pixels, i * 160 * 144 * 4, (i + 1) * 160 * 144 * 4);
            frames[i] = new AvFrame(frameNumber, 160, 144, 0, rgba, pcm, sampleRate, shutdown, 160.0 / 144);
        }
        return new LinkRuntime.Frame(frameNumber++, frames[0], frames[1]);
    }

    public byte[] exportEndpoint(int endpoint) throws IOException {
        requireOpen(); endpoint(endpoint);
        int count = bounded(api.muxi_link_endpoint_state_size(endpoint).longValue(), MAX_STATE, "Endpoint state");
        if (count == 0) throw new IOException("Endpoint has no initialized state");
        byte[] core;
        try (Memory state = new Memory(count)) {
            if (api.muxi_link_endpoint_state_save(endpoint, state, new SizeT(count)) == 0) throw new IOException("Endpoint state export refused");
            core = state.getByteArray(0, count);
        }
        byte[] sram = memory(SRAM[endpoint]), rtc = memory(RTC[endpoint]);
        return LinkWire.body(out -> { out.writeInt(SINGLE_MAGIC); LinkWire.bytes(out, core); LinkWire.bytes(out, sram); LinkWire.bytes(out, rtc); });
    }
    private record State(byte[] core, byte[] sram, byte[] rtc) { }
    private State parseSingle(byte[] bytes) throws IOException {
        if (bytes.length > 4 * 1024 * 1024) throw new IOException("Endpoint state envelope exceeds limit");
        var in = LinkWire.input(bytes);
        if (in.readInt() != SINGLE_MAGIC) throw new IOException("Expected a single-machine MXS1 state, not a link snapshot");
        State state = new State(LinkWire.bytes(in, MAX_STATE), LinkWire.bytes(in, 512 * 1024), LinkWire.bytes(in, 65536));
        LinkWire.end(in); if (state.core.length == 0) throw new IOException("Empty endpoint core state"); return state;
    }
    public void restoreEndpoint(int endpoint, byte[] bytes) throws IOException {
        requireOpen(); endpoint(endpoint); State target = parseSingle(bytes);
        byte[] before = saveLinkState();
        try { apply(endpoint, target); checkFailure(); }
        catch (IOException | RuntimeException | LinkageError error) { rollback(before, error); }
    }
    private void apply(int endpoint, State state) throws IOException {
        try (Memory core = new Memory(state.core.length)) {
            core.write(0, state.core, 0, state.core.length);
            if (api.muxi_link_endpoint_state_load(endpoint, core, new SizeT(state.core.length)) == 0) throw new IOException("Endpoint rejected saved core state");
        }
        writeMemory(SRAM[endpoint], state.sram); writeMemory(RTC[endpoint], state.rtc);
        if (api.muxi_link_audio_attach() == 0) throw new IOException("Endpoint audio tap could not be restored");
    }
    public byte[] saveLinkState() throws IOException {
        requireOpen(); byte[][] states = {exportEndpoint(0), exportEndpoint(1)};
        int bus = api.muxi_link_bus_state(); if (bus < 0 || bus > 3) throw new IOException("Invalid cable bus state");
        return LinkWire.body(out -> {
            out.writeInt(LINK_MAGIC); out.writeShort(1); LinkWire.text(out, config.coreSHA());
            for (int i = 0; i < 2; i++) {
                LinkRuntime.Endpoint endpoint = config.endpoints().get(i);
                LinkWire.text(out, endpoint.machineId().toString()); LinkWire.text(out, endpoint.platform()); LinkWire.text(out, endpoint.romSHA());
                LinkWire.bytes(out, states[i]);
            }
            out.writeByte(bus);
        });
    }
    private record Group(State[] states, int bus) { }
    private Group parseGroup(byte[] bytes) throws IOException {
        if (bytes.length > LinkWire.MAX_BYTES) throw new IOException("Link snapshot exceeds limit");
        var in = LinkWire.input(bytes);
        if (in.readInt() != LINK_MAGIC || in.readUnsignedShort() != 1 || !LinkWire.text(in).equals(config.coreSHA()))
            throw new IOException("Link snapshot format or bridge identity differs");
        State[] states = new State[2];
        for (int i = 0; i < 2; i++) {
            LinkRuntime.Endpoint endpoint = config.endpoints().get(i);
            if (!LinkWire.text(in).equals(endpoint.machineId().toString()) || !LinkWire.text(in).equals(endpoint.platform())
                    || !LinkWire.text(in).equals(endpoint.romSHA())) throw new IOException("Link snapshot belongs to different machines or ROMs");
            states[i] = parseSingle(LinkWire.bytes(in, 4 * 1024 * 1024));
        }
        int bus = in.readUnsignedByte(); LinkWire.end(in); if (bus > 3) throw new IOException("Invalid link bus bits");
        return new Group(states, bus);
    }
    private void applyGroup(Group group) throws IOException {
        apply(0, group.states[0]); apply(1, group.states[1]);
        if (api.muxi_link_bus_restore(group.bus) == 0) throw new IOException("Cable bus restoration failed"); checkFailure();
    }
    public void loadLinkState(byte[] bytes) throws IOException {
        requireOpen(); Group target = parseGroup(bytes); byte[] before = saveLinkState();
        try { applyGroup(target); }
        catch (IOException | RuntimeException | LinkageError error) { rollback(before, error); }
    }
    private void rollback(byte[] before, Throwable original) throws IOException {
        try { failure = null; applyGroup(parseGroup(before)); }
        catch (IOException | RuntimeException | LinkageError rollback) {
            try { close(); } catch (IOException cleanup) { rollback.addSuppressed(cleanup); }
            throw new IOException("Link restore failed and rollback failed; worker core stopped", rollback);
        }
        throw new IOException("Link state rejected; both machines restored to the previous state", original);
    }

    private byte[] memory(int id) throws IOException {
        int count = bounded(api.retro_get_memory_size(id).longValue(), 512 * 1024, "Endpoint memory");
        Pointer pointer = api.retro_get_memory_data(id); if (count != 0 && pointer == null) throw new IOException("Endpoint memory inaccessible");
        return count == 0 ? new byte[0] : pointer.getByteArray(0, count);
    }
    private void writeMemory(int id, byte[] bytes) throws IOException {
        if (api.retro_get_memory_size(id).longValue() != bytes.length) throw new IOException("Endpoint save memory size differs");
        Pointer pointer = api.retro_get_memory_data(id); if (bytes.length != 0 && pointer == null) throw new IOException("Endpoint save memory inaccessible");
        if (bytes.length != 0) pointer.write(0, bytes, 0, bytes.length);
    }
    private boolean environment(int command, Pointer data) {
        switch (command) {
            case 7 -> { shutdown = true; return true; }
            case 9, 31 -> { data.setPointer(0, system); return true; }
            case 10 -> { return data.getInt(0) == 1; }
            case 15 -> {
                Memory value = variables.get(data.getPointer(0).getString(0, "UTF-8"));
                data.setPointer(Native.POINTER_SIZE, value); return value != null;
            }
            case 17 -> { data.setByte(0, (byte) 0); return true; }
            case 52 -> { data.setInt(0, 2); return true; }
            case 16, 18, 34, 35, 37, 53, 54, 67, 68, 51 | 0x10000 -> { return true; }
            case 81 | 0x10000 -> { data.setInt(0, 48000); return true; }
            default -> { return false; }
        }
    }
    private void option(String key, String value) { variables.put(key, text(value)); }
    private Memory text(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8); Memory memory = new Memory(bytes.length + 1L);
        memory.write(0, bytes, 0, bytes.length); memory.setByte(bytes.length, (byte) 0); held.add(memory); return memory;
    }
    private void requireOpen() throws IOException {
        if (Thread.currentThread() != owner || !loaded || closed) throw new IOException("Link native call outside its active worker");
    }
    private void checkFailure() throws IOException { if (failure != null) throw new IOException(failure); }
    private static void endpoint(int i) throws IOException { if (i < 0 || i > 1) throw new IOException("Invalid link endpoint"); }
    private static int bounded(long count, int limit, String label) throws IOException {
        if (count < 0 || count > limit) throw new IOException(label + " exceeds limit"); return (int) count;
    }
    static String hash(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Expected a regular link file");
        MessageDigest digest = sha(); try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[32768]; for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
    private static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    @Override public void close() throws IOException {
        if (closed) return; closed = true;
        try {
            if (api != null && loaded) { api.retro_unload_game(); loaded = false; }
            if (api != null && initialized) { api.retro_deinit(); initialized = false; }
        } finally { for (Memory memory : held) memory.close(); held.clear(); }
    }

    @Structure.FieldOrder({"path", "data", "size", "meta"})
    public static final class Game extends Structure { public Pointer path, data, meta; public SizeT size = new SizeT(); }
    public static final class SizeT extends IntegerType { public SizeT() { this(0); } public SizeT(long n) { super(Native.SIZE_T_SIZE, n, true); } }
    public interface Environment extends Callback { byte invoke(int command, Pointer data); }
    public interface Video extends Callback { void invoke(Pointer data, int width, int height, SizeT pitch); }
    public interface Audio extends Callback { void invoke(short left, short right); }
    public interface AudioBatch extends Callback { SizeT invoke(Pointer data, SizeT frames); }
    public interface Poll extends Callback { void invoke(); }
    public interface Input extends Callback { short invoke(int port, int device, int index, int id); }
    public interface Api extends Library {
        int retro_api_version(); void retro_init(); void retro_deinit(); void retro_run(); void retro_unload_game();
        void retro_set_environment(Environment callback); void retro_set_video_refresh(Video callback);
        void retro_set_audio_sample(Audio callback); void retro_set_audio_sample_batch(AudioBatch callback);
        void retro_set_input_poll(Poll callback); void retro_set_input_state(Input callback);
        byte retro_load_game_special(int type, Pointer games, SizeT count); void retro_get_system_av_info(Pointer info);
        SizeT retro_get_memory_size(int id); Pointer retro_get_memory_data(int id);
        int muxi_link_abi(); byte muxi_link_audio_attach(); void muxi_link_audio_clear(); byte muxi_link_audio_did_overflow();
        int muxi_link_audio_read(int endpoint, Pointer samples, int capacity);
        SizeT muxi_link_endpoint_state_size(int endpoint); byte muxi_link_endpoint_state_save(int endpoint, Pointer state, SizeT size);
        byte muxi_link_endpoint_state_load(int endpoint, Pointer state, SizeT size); int muxi_link_bus_state(); byte muxi_link_bus_restore(int bits);
    }
}
