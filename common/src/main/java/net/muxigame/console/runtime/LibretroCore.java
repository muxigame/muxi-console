package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.io.*;
import java.nio.file.*;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Our binding of the public MIT libretro ABI. Never referenced by the Minecraft-side API. */
final class LibretroCore implements AutoCloseable {
    private static final int MAX_PIXELS = 2048 * 2048, MAX_MEMORY = 24 * 1024 * 1024;
    private final Api api;
    private final LinkNetpacket netpacket;
    private final Thread thread = Thread.currentThread();
    private final List<Memory> strings = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> options = new ConcurrentHashMap<>(), defaults = new ConcurrentHashMap<>();
    private final Map<String, Memory> optionStrings = new ConcurrentHashMap<>();
    private final Memory systemPath, savePath, libraryPath, contentPath;
    private Memory contentMemory;
    private FileChannel contentChannel;private FileLock contentLock;private MappedByteBuffer mappedContent;private String platform;
    private byte[] nativeStateBinding,nativeStateFormat;
    private volatile InputFrame input = InputFrame.idle();
    private final int[] controllerDevices = {1,1,1,1};
    private final Object micLock = new Object();
    private final Map<Long,MicStream> microphones = new HashMap<>();
    private final Object audioLock=new Object();private volatile boolean acceptingAudio;
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
        if (Thread.currentThread() != thread && !nativeLayout()) callbackFailure = "Core invoked asynchronous input polling";
    };
    private final InputState inputState = (port, device, index, id) -> {
        if (Thread.currentThread() != thread && !nativeLayout()) { callbackFailure = "Core invoked asynchronous input state"; return 0; }
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
        if (device == 5 && index == 2 && id >= 0 && id < 16) return (short)(((state.buttons() >>> id)&1)*32767);
        if (device == 6 && index == 0) {
            PortInput.Pointer pointer=state.pointer();
            if(id==3)return (short)(pointer.valid()?1:0);
            if(id==2)return (short)(pointer.pressed()?1:0);
            if(!pointer.valid())return 0;
            if(id==0||id==1)return pointerCoordinate(pointer,id==0);
        }
        // Absolute touch is delivered through POINTER; mouse deltas remain zero.
        if(device==2&&index==0&&id==2)return (short)(state.pointer().pressed()?1:0);
        return 0;
    };
    private final SensorState sensorState=(port,action,rate)->(byte)(port>=0&&port<4&&action>=0&&action<4&&rate>=0&&rate<=1000?1:0);
    private final SensorInput sensorInput=(port,id)->{
        if(port<0||port>=4)return 0;
        PortInput.Motion m=input.port(port).motion();
        return switch(id){case 0->m.accelerationX();case 1->m.accelerationY();case 2->m.accelerationZ();case 3->m.gyroX();case 4->m.gyroY();case 5->m.gyroZ();default->0;};
    };
    private final MicOpen micOpen=params->{synchronized(micLock){
        int rate=params==null?48000:params.getInt(0);if(rate<8000||rate>48000||microphones.size()>=4)return null;
        MicStream stream=new MicStream(rate);microphones.put(Pointer.nativeValue(stream.handle),stream);return stream.handle;
    }};
    private final MicClose micClose=handle->{synchronized(micLock){MicStream stream=microphones.remove(Pointer.nativeValue(handle));if(stream!=null)stream.handle.close();}};
    private final MicParams micParams=(handle,params)->{synchronized(micLock){MicStream stream=microphones.get(Pointer.nativeValue(handle));if(stream==null||params==null)return 0;params.setInt(0,stream.rate);return 1;}};
    private final MicSetState micSetState=(handle,state)->{synchronized(micLock){MicStream stream=microphones.get(Pointer.nativeValue(handle));if(stream==null)return 0;stream.active=state!=0;if(!stream.active)stream.clear();return 1;}};
    private final MicGetState micGetState=handle->{synchronized(micLock){MicStream stream=microphones.get(Pointer.nativeValue(handle));return (byte)(stream!=null&&stream.active?1:0);}};
    private final MicRead micRead=(handle,destination,count)->{synchronized(micLock){
        MicStream stream=microphones.get(Pointer.nativeValue(handle));long requested=count.longValue();
        if(stream==null||!stream.active||destination==null||requested<0||requested>48000)return -1;
        for(int i=0;i<requested;i++)destination.setShort(i*2L,stream.take());return (int)requested;
    }};

    LibretroCore(Path library, Path content, Path system, Path save, Map<String, String> configured) throws IOException {
        this(library,content,system,save,configured,"");
    }
    LibretroCore(Path library,Path content,Path system,Path save,Map<String,String> configured,String platform)throws IOException{
        this(library,content,system,save,configured,platform,null);
    }
    LibretroCore(Path library,Path content,Path system,Path save,Map<String,String> configured,String platform,LinkNetpacket netpacket)throws IOException{
        this.netpacket=netpacket;
        this.platform=platform;
        options.putAll(configured);
        String nativeSave = NativeProfilePath.forCore(save, platform);
        systemPath = string(system.toAbsolutePath().toString()); savePath = string(nativeSave);
        libraryPath = string(library.toAbsolutePath().toString());
        contentPath = string(content == null ? "" : content.toAbsolutePath().getParent().toString());
        api = Native.load(library.toAbsolutePath().toString(), Api.class, Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
        try {
            if (api.retro_api_version() != 1) throw new IOException("Unsupported libretro API version");
            api.retro_set_environment(environment); api.retro_set_video_refresh(video);
            api.retro_set_audio_sample(audio); api.retro_set_audio_sample_batch(audioBatch);
            api.retro_set_input_poll(inputPoll); api.retro_set_input_state(inputState);
            api.retro_get_system_info(systemInfo); systemInfo.read();
            if(this.platform.isEmpty())this.platform=switch(readString(systemInfo.library_name).toLowerCase(Locale.ROOT)){case "melonds ds"->"nds";case "azahar"->"n3ds";case "dolphin-emu"->"wii";default->"";};
            if(this.platform.equals("n3ds")){
                nativeStateBinding=NativeStateBinding.identity(save,nativeSave);
                String actualLibrarySha=NativeStateBinding.libraryDigest(library);
                if(NativeStateBinding.reviewedPortableLibrary(actualLibrarySha)){
                    int nativeAbi=NativeLibrary.getInstance(library.toAbsolutePath().toString()).getFunction("muxi_azahar_profile_state_abi").invokeInt(new Object[0]);
                    nativeStateFormat=NativeStateBinding.portableFormat(actualLibrarySha,nativeAbi);
                    if(nativeStateFormat==null)throw new IOException("Reviewed portable native core reported an unsupported profile-state ABI");
                }
            }
            api.retro_init(); initialized = true;
            GameInfo game = null;
            if (content != null) {
                game = new GameInfo(); game.path = string(content.toAbsolutePath().toString());
                if (systemInfo.need_fullpath == 0) {
                    long size = Files.size(content);
                    if(this.platform.equals("nds")){
                        if(size<1||size>512L*1024*1024)throw new IOException("DS content exceeds 512 MiB");contentChannel=FileChannel.open(content,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);contentLock=contentChannel.lock(0,Long.MAX_VALUE,true);mappedContent=contentChannel.map(FileChannel.MapMode.READ_ONLY,0,size);game.data=Native.getDirectBufferPointer(mappedContent);game.size=new SizeT(size);
                    }else{if (size <= 0 || size > 128 * 1024 * 1024) throw new IOException("In-memory content must be between 1 byte and 128 MiB");byte[] raw = Files.readAllBytes(content); contentMemory = new Memory(raw.length);contentMemory.write(0, raw, 0, raw.length); game.data = contentMemory; game.size = new SizeT(raw.length);}
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
            // These cores advertise the union of every optional 10x layout.
            // Our fixed native-resolution layout negotiates its actual geometry.
            if (hardware != null) hardware.initialize(nativeLayout() ? width : maximumWidth, nativeLayout() ? height : maximumHeight);
            checkCallback();
        } catch (IOException | RuntimeException | LinkageError ex) {
            try { close(); } catch (IOException | RuntimeException | LinkageError cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
    }

    CoreInfo info() { return new CoreInfo(readString(systemInfo.library_name), readString(systemInfo.library_version), width, height, fps, sampleRate, displayAspect); }
    AvFrame step(InputFrame input) throws IOException {
        checkThread(); this.input = input; synchronized(audioLock){sampleCount = 0;} callbackFailure = null;
        for(int port=0;port<4;port++)if(controllerDevices[port]!=input.port(port).device()){controllerDevices[port]=input.port(port).device();api.retro_set_controller_port_device(port,controllerDevices[port]);}
        synchronized(micLock){for(MicStream stream:microphones.values())stream.append(input.port(0).microphone());}
        acceptingAudio=true;try{if (!shutdown) runCore(); checkCallback();}finally{acceptingAudio=false;}
        synchronized(audioLock){return new AvFrame(number++, width, height, rotation, pixels.clone(), Arrays.copyOf(samples, sampleCount), sampleRate, shutdown, displayAspect,surfaces());}
    }
    private List<AvFrame.Surface> surfaces(){
        if(platform.equals("nds")&&width==256&&height==384)return List.of(new AvFrame.Surface("top",0,0,256,192,256,192,false),new AvFrame.Surface("bottom",0,192,256,192,256,192,true));
        if(platform.equals("n3ds")&&width==400&&height==480)return List.of(new AvFrame.Surface("top",0,0,400,240,400,240,false),new AvFrame.Surface("bottom",40,240,320,240,320,240,true));
        return width>0&&height>0?List.of(new AvFrame.Surface("primary",0,0,width,height,width,height,platform.equals("wii"))):List.of();
    }
    private short pointerCoordinate(PortInput.Pointer pointer,boolean horizontal){
        for(AvFrame.Surface surface:surfaces())if(surface.id().equals(pointer.surface())){
            double local=((horizontal?pointer.x():pointer.y())+32767)/65534.0;
            double pixel=(horizontal?surface.x():surface.y())+local*(horizontal?surface.width():surface.height());
            double total=horizontal?width:height;return (short)Math.max(-32767,Math.min(32767,Math.round((pixel/total)*65534-32767)));
        }
        return horizontal?pointer.x():pointer.y();
    }
    void reset() throws IOException { checkThread(); api.retro_reset(); checkCallback(); }
    byte[] readSram() throws IOException { checkThread(); return readMemory(0); }
    void writeSram(byte[] data) throws IOException { checkThread(); validateMemory(0, data); writeMemory(0, data); }

    private record SavedState(byte[] core, byte[] sram, byte[] rtc) { }
    private void runCore() throws IOException {
        if(netpacket!=null)netpacket.beforeRun();
        if (hardware != null) hardware.beforeFrame(!platform.equals("wii"));
        executionStarted = true; api.retro_run();
    }
    private void ensureHardwareExecutionStarted() throws IOException {
        if (hardware != null && hardware.initialized() && loaded && !executionStarted) {
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
                if (saved != 0) return normalizeState(new SavedState(buffer.getByteArray(0, size), readMemory(0), readMemory(1)));
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
    long stateSize()throws IOException{checkThread();ensureHardwareExecutionStarted();long size=api.retro_serialize_size().longValue();if(size<0||size>1024L*1024*1024)throw new IOException("Core state size exceeds one GiB");return size;}
    byte[] saveState() throws IOException {
        checkThread(); SavedState state = captureState();
        return Wire.body(out -> { NativeStateBinding.write(out,nativeStateBinding,nativeStateFormat); Wire.bytes(out, state.core); Wire.bytes(out, state.sram); Wire.bytes(out, state.rtc); });
    }
    private static final long MAX_SPOOL=512L*1024*1024;
    void saveStateTo(Path target)throws IOException{
        checkThread();ensureHardwareExecutionStarted();
        if(!target.isAbsolute()||target.getParent()==null||!target.getParent().toRealPath().equals(target.getParent())||Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("State spool must be a new file in an ordinary local directory");
        if(!nativeLayout()){Files.write(target,saveState(),StandardOpenOption.CREATE_NEW);return;}
        long size=stateSize();if(size==0)throw new IOException("Core does not support save states");if(size>MAX_SPOOL-2L*MAX_MEMORY-NativeStateBinding.envelopeBytes(nativeStateBinding,nativeStateFormat))throw new IOException("Core state exceeds streaming spool budget");
        try(Memory buffer=new Memory(size)){
            if(api.retro_serialize(buffer,new SizeT(size))==0)throw new IOException("Core refused to save state");checkCallback();
            byte[] sram=readMemory(0),rtc=readMemory(1);boolean created=false,complete=false;
            try{
                try(var output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)))){
                    created=true;NativeStateBinding.write(output,nativeStateBinding,nativeStateFormat);output.writeInt((int)size);byte[] chunk=new byte[65536];
                    for(long offset=0;offset<size;){int count=(int)Math.min(chunk.length,size-offset);buffer.read(offset,chunk,0,count);output.write(chunk,0,count);offset+=count;}
                    Wire.bytes(output,sram);Wire.bytes(output,rtc);
                }
                complete=true;
            }finally{if(created&&!complete)Files.deleteIfExists(target);}
        }
    }
    private void applyStateFrom(Path source)throws IOException{
        if(!source.isAbsolute()||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||!source.toRealPath().equals(source)||Files.size(source)<16||Files.size(source)>MAX_SPOOL)throw new IOException("Invalid local state spool");
        try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)))){
            NativeStateBinding.read(in,nativeStateBinding,nativeStateFormat);int length=in.readInt();
            if(length<=0||length>MAX_SPOOL-16||length>Files.size(source)-16)throw new IOException("Invalid streaming core state size");
            try(Memory buffer=new Memory(length)){
                byte[] chunk=new byte[65536];for(long offset=0;offset<length;){int count=(int)Math.min(chunk.length,length-offset);in.readFully(chunk,0,count);buffer.write(offset,chunk,0,count);offset+=count;}
                int sramLength=in.readInt();if(sramLength<0||sramLength>MAX_MEMORY)throw new IOException("Invalid streaming SRAM size");byte[] sram=in.readNBytes(sramLength);if(sram.length!=sramLength)throw new EOFException();
                int rtcLength=in.readInt();if(rtcLength<0||rtcLength>MAX_MEMORY)throw new IOException("Invalid streaming RTC size");byte[] rtc=in.readNBytes(rtcLength);if(rtc.length!=rtcLength||in.read()!=-1)throw new IOException("Truncated or trailing streaming state");
                if(mgbaState()){if(length>MAX_MEMORY)throw new IOException("mGBA state exceeds 24 MiB");SavedState normalized=normalizeState(new SavedState(buffer.getByteArray(0,length),sram,rtc));buffer.write(0,normalized.core,0,length);sram=normalized.sram;}
                validateStatePrefix(buffer,length);if(api.retro_unserialize(buffer,new SizeT(length))==0)throw new IOException("Core rejected saved state");
                AvInfo av=new AvInfo();api.retro_get_system_av_info(av);av.read();setAv(av);validateMemory(0,sram);validateMemory(1,rtc);writeMemory(0,sram);writeMemory(1,rtc);checkCallback();
            }
        }
    }
    void loadStateFrom(Path source)throws IOException{
        checkThread();if(!source.isAbsolute()||source.getParent()==null)throw new IOException("Invalid state spool path");
        // Check ownership before serializing rollback or calling retro_unserialize.
        try(var header=new DataInputStream(Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS))){NativeStateBinding.read(header,nativeStateBinding,nativeStateFormat);}
        Path rollback=source.resolveSibling("muxi-rollback-"+UUID.randomUUID()+".mxs1");
        saveStateTo(rollback);InputFrame priorInput=input;boolean priorShutdown=shutdown;
        int priorWidth=width,priorHeight=height,priorRotation=rotation,priorFormat=pixelFormat,priorCount=sampleCount;
        double priorFps=fps,priorRate=sampleRate,priorAspect=displayAspect;byte[] priorPixels=pixels;short[] priorSamples=samples.clone();
        try{
            try{applyStateFrom(source);input=InputFrame.idle();sampleCount=0;shutdown=false;}
            catch(IOException|RuntimeException|LinkageError failure){
                try{callbackFailure=null;applyStateFrom(rollback);input=priorInput;shutdown=priorShutdown;width=priorWidth;height=priorHeight;rotation=priorRotation;pixelFormat=priorFormat;sampleCount=priorCount;fps=priorFps;sampleRate=priorRate;displayAspect=priorAspect;pixels=priorPixels;samples=priorSamples;}
                catch(IOException|RuntimeException|LinkageError rollbackFailure){try{close();}catch(IOException|RuntimeException|LinkageError cleanup){rollbackFailure.addSuppressed(cleanup);}IOException fatal=new IOException("Streaming state failed and rollback failed; emulator stopped",failure);fatal.addSuppressed(rollbackFailure);throw fatal;}
                throw new IOException("Streaming state rejected; previous state restored: "+failure.getMessage(),failure);
            }
        }finally{Files.deleteIfExists(rollback);}
    }
    private byte[] statePart(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_MEMORY || length > in.available()) throw new IOException("Invalid saved state component size");
        return in.readNBytes(length);
    }
    private SavedState parseState(byte[] state) throws IOException {
        if (state == null || state.length < 16 || state.length > Wire.MAX_BYTES) throw new IOException("Invalid saved state envelope size");
        var in = Wire.input(state);
        NativeStateBinding.read(in,nativeStateBinding,nativeStateFormat);
        SavedState target = new SavedState(statePart(in), statePart(in), statePart(in)); Wire.end(in);
        if (target.core.length == 0) throw new IOException("Empty core state");
        return target;
    }
    private void applyState(SavedState state) throws IOException {
        state=normalizeState(state);
        try (Memory buffer = new Memory(state.core.length)) {
            buffer.write(0, state.core, 0, state.core.length);
            validateStatePrefix(buffer,state.core.length);
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
    private boolean mgbaState(){return readString(systemInfo.library_name).equalsIgnoreCase("mGBA");}
    private SavedState normalizeState(SavedState state)throws IOException{
        if(!mgbaState())return state;
        var normalized=MgbaStateNormalizer.normalize(state.core,state.sram);return normalized.normalized()?new SavedState(normalized.core(),normalized.sram(),state.rtc):state;
    }
    private void validateStatePrefix(Pointer data,long length)throws IOException{
        if(!platform.equals("wii"))return;
        // This fixed Dolphin revision returns true even when PointerWrap rejects
        // a state. State::DoState begins with mode and two little-endian RAM sizes.
        if(length<9)throw new IOException("Truncated Dolphin state prefix");
        int mode=data.getByte(0)&255;long mem1=Integer.toUnsignedLong(data.getInt(1)),mem2=Integer.toUnsignedLong(data.getInt(5));
        if(java.nio.ByteOrder.nativeOrder()!=java.nio.ByteOrder.LITTLE_ENDIAN){mem1=Integer.toUnsignedLong(Integer.reverseBytes(data.getInt(1)));mem2=Integer.toUnsignedLong(Integer.reverseBytes(data.getInt(5)));}
        if(mode>1||mem1<1024*1024||mem1>128L*1024*1024||mem1%(1024*1024)!=0||mem2>128L*1024*1024||mem2%(1024*1024)!=0||length<9+mem1+mem2)throw new IOException("Dolphin state mode, memory layout or byte length is invalid");
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
        // Dolphin reads its already-negotiated options from its CPU thread.
        // GET_VARIABLE is read-only apart from the concurrent, lifetime-owned
        // UTF-8 cache; renderer negotiation and all mutating callbacks stay on
        // the owner thread. The other observed async query is target audio rate.
        if (Thread.currentThread() != thread && !(nativeLayout() && (command == 15 || command == (81 | 0x10000)))) throw new IOException("Core invoked an asynchronous environment callback (command="+command+")");
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
            case 24 -> { data.setLong(0, (1L << 1) | (1L << 2) | (1L << 5) | (1L << 6)); yield true; }
            case 25 | 0x10000 -> {data.setPointer(0,CallbackReference.getFunctionPointer(sensorState));data.setPointer(Native.POINTER_SIZE,CallbackReference.getFunctionPointer(sensorInput));yield true;}
            case 75 | 0x10000 -> {
                if(data==null||data.getInt(0)!=1)yield false;
                Callback[] callbacks={micOpen,micClose,micParams,micSetState,micGetState,micRead};
                long start=Native.POINTER_SIZE==8?8:4;
                for(int i=0;i<callbacks.length;i++)data.setPointer(start+(long)i*Native.POINTER_SIZE,CallbackReference.getFunctionPointer(callbacks[i]));yield true;
            }
            case 78 -> data!=null&&netpacket!=null&&netpacket.configure(data);
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
        if (hardware != null && hardware.initialized() && nativeLayout()) hardware.ensureSize(geometry.base_width, geometry.base_height);
        if (pixels.length == 0) { width = geometry.base_width; height = geometry.base_height; pixels = new byte[width * height * 4]; }
    }
    private boolean nativeLayout() { return platform.equals("nds") || platform.equals("n3ds") || platform.equals("wii"); }
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
        if(!nativeLayout())checkThread();
        synchronized(audioLock){
            if(nativeLayout()&&!acceptingAudio)return;
            if (sampleCount >= 192000 * 2) throw new IOException("Core produced excessive audio in one frame");
            if (sampleCount + 2 > samples.length) samples = Arrays.copyOf(samples, Math.min(192000 * 2, samples.length * 2));
            samples[sampleCount++] = left; samples[sampleCount++] = right;
        }
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
        if(netpacket!=null)try{netpacket.close();}catch(IOException|RuntimeException|LinkageError failure){IOException closed=new IOException("Netpacket bridge did not close cleanly",failure);if(startupFailure==null)startupFailure=closed;else startupFailure.addSuppressed(closed);}
        // Azahar's destroy hook releases its live GPU renderer; Shutdown in
        // retro_unload_game destroys that GPU, so notify while it still exists.
        if (platform.equals("n3ds") && hardware != null) hardware.notifyContextDestroyed();
        if (loaded) { api.retro_unload_game(); loaded = false; }
        if (hardware != null) hardware.notifyContextDestroyed();
        if (initialized) { api.retro_deinit(); initialized = false; }
        if (hardware != null) { hardware.close(); hardware = null; }
        if (contentMemory != null) { contentMemory.close(); contentMemory = null; }
        if(contentLock!=null){contentLock.close();contentLock=null;}if(contentChannel!=null){contentChannel.close();contentChannel=null;}mappedContent=null;
        synchronized(micLock){for(MicStream stream:microphones.values())stream.handle.close();microphones.clear();}
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
    public interface SensorState extends Callback {byte invoke(int port,int action,int rate);}
    public interface SensorInput extends Callback {float invoke(int port,int id);}
    public interface MicOpen extends Callback {Pointer invoke(Pointer params);}
    public interface MicClose extends Callback {void invoke(Pointer handle);}
    public interface MicParams extends Callback {byte invoke(Pointer handle,Pointer params);}
    public interface MicSetState extends Callback {byte invoke(Pointer handle,byte enabled);}
    public interface MicGetState extends Callback {byte invoke(Pointer handle);}
    public interface MicRead extends Callback {int invoke(Pointer handle,Pointer pcm,SizeT count);}
    private static final class MicStream {
        final Memory handle=new Memory(1);final int rate;final short[] pending=new short[19200];boolean active;int head,size;double phase;
        MicStream(int rate){this.rate=rate;}
        void clear(){head=size=0;phase=0;}
        short take(){if(size==0)return 0;short value=pending[head];head=(head+1)%pending.length;size--;return value;}
        void append(PortInput.Microphone microphone){
            if(!microphone.connected()){clear();return;}if(!active)return;short[] data=microphone.samples();double stride=(double)microphone.sampleRate()/rate;
            while(phase<data.length){if(size==pending.length){head=(head+1)%pending.length;size--;}pending[(head+size)%pending.length]=data[(int)phase];size++;phase+=stride;}
            phase-=data.length;
        }
    }
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
