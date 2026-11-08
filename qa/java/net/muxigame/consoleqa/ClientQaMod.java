package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.client.ui.WriterScreen;
import net.muxigame.console.mc.ClientGate;
import net.muxigame.console.mc.CardData;
import net.muxigame.console.mc.Wire;
import net.muxigame.console.runtime.PortInput;
import org.lwjgl.glfw.GLFW;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

/** Runs normal UI callbacks and network actions. None of these callbacks represents physical OS input. */
@Mod(ClientQaMod.ID)
public final class ClientQaMod {
    public static final String ID = "muxi_console_qa_client";
    private final QaOwner owner = new QaOwner(ID, "client");
    private JsonObject writerContext;
    private JsonObject sessionContext;
    private String lastCode = "", probe = "", screenshot = "";
    private boolean listening;
    private CompletableFuture<Optional<byte[]>> probeFuture;
    private Path diagnostic;
    private Path gbDiagnostic;
    private long browserPosition = Long.MIN_VALUE;
    private String browserSide = "SOUTH";
    private JsonObject browserNative = new JsonObject();
    private long clientTicks;
    private BlockPos destroying;
    private final Map<Long,String> videoHistory=new LinkedHashMap<>(),audioHistory=new LinkedHashMap<>();
    private String historyLease="";
    private String stateProbe="",stateProbeRequest="";
    private long sharedPcmBytes,sharedPcmNonzeroSamples;
    private final DockActions dockActions=new DockActions();
    private final HeldActions heldActions=new HeldActions();
    private final PolishActions polishActions=new PolishActions();
    public ClientQaMod() { NeoForge.EVENT_BUS.addListener(this::tick);NeoForge.EVENT_BUS.addListener(this::systemMessage); }
    private void systemMessage(ClientChatReceivedEvent.System event){if(event.isOverlay())CartridgeActions.message(event.getMessage().getString());}
    private void tick(ClientTickEvent.Post event) {
        clientTicks++;
        sampleShared();
        if (!listening) {
            var productReceiver = ClientGate.receiver;
            ClientGate.receiver = message -> {
                productReceiver.accept(message);
                if (message.operation().equals("writer_open")) writerContext = message.body().deepCopy();
                if (message.operation().equals("session_start")) sessionContext = message.body().deepCopy();
                if (message.operation().equals("error")) lastCode = message.body().get("code").getAsString();
                if((message.operation().equals("state_chunk")||message.operation().equals("state_denied"))&&message.body().has("request")&&message.body().get("request").getAsString().equals(stateProbeRequest))stateProbe=message.operation().equals("state_denied")?"denied":"granted:"+message.data().length;
                if(message.operation().equals("shared_audio")){byte[] pcm=message.data();sharedPcmBytes+=pcm.length;for(int i=0;i+1<pcm.length;i+=2)if(pcm[i]!=0||pcm[i+1]!=0)sharedPcmNonzeroSamples++;}
            };
            listening = true;
        }
        if (probeFuture != null && probeFuture.isDone()) {
            try { probe = probeFuture.join().isPresent() ? "granted" : "denied"; }
            catch (RuntimeException error) { probe = "error:" + error.getMessage(); }
            probeFuture = null;
        }
        owner.poll(this::dispatch);
        CartridgeActions.tick(Minecraft.getInstance());
        dockActions.tick(Minecraft.getInstance());
        if(destroying!=null){Minecraft mc=Minecraft.getInstance();if(mc.level==null||mc.level.getBlockState(destroying).isAir()){if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();destroying=null;}else mc.gameMode.continueDestroyBlock(destroying,Direction.UP);}
    }
    private JsonObject dispatch(JsonObject command) {
        Minecraft client = Minecraft.getInstance();
        String action = command.get("action").getAsString();
        if(action.startsWith("cartridge_")){try{CartridgeActions.command(client,command);JsonObject result=observe(client);if(action.equals("cartridge_cache"))result.add("cacheCheck",CartridgeActions.cache(client,command.get("hash").getAsString()));return result;}catch(Exception e){throw new IllegalStateException(e);}}
        if(action.startsWith("polish_")){try{JsonObject callback=polishActions.command(client,command);JsonObject result=observe(client);result.add("polishCallback",callback);return result;}catch(Exception e){throw new IllegalStateException(e);}}
        if(action.startsWith("hand_")){JsonObject callback=heldActions.command(client,command);JsonObject result=observe(client);result.add("handCallback",callback);return result;}
        if(action.startsWith("dock_")){dockActions.command(client,command);return observe(client);}
        try {
            switch (action) {
                case "observe" -> { }
                case "review_view" -> NativeReview.view(client,command.has("held")&&command.get("held").getAsBoolean());
                case "swap_hands" -> client.getConnection().send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,BlockPos.ZERO,Direction.DOWN));
                case "select_card" -> {String instance=command.get("instance").getAsString();int selected=-1;for(int i=0;i<36;i++)if(CardData.read(client.player.getInventory().getItem(i)).map(c->c.instanceId().toString().equals(instance)).orElse(false)){selected=i;break;}if(selected<0)throw new IllegalArgumentException("The real cartridge is absent");if(selected>8){client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId,selected,8,net.minecraft.world.inventory.ClickType.SWAP,client.player);selected=8;}client.player.getInventory().selected=selected;client.getConnection().send(new ServerboundSetCarriedItemPacket(selected));}
                case "state_probe" -> {UUIDCheck(command.get("generation").getAsString());var state=ConsoleClient.INSTANCE.snapshot();if(!state.ready())throw new IllegalStateException("Actual occupied control seat required");stateProbe="pending";stateProbeRequest=java.util.UUID.randomUUID().toString();JsonObject j=new JsonObject();j.addProperty("request",stateProbeRequest);j.addProperty("room",state.room());j.addProperty("epoch",state.epoch());j.addProperty("generation",command.get("generation").getAsString());j.addProperty("offset",0);Wire.command("state_read",j);}
                case "browser_native" -> {
                    browserPosition=command.get("position").getAsLong();browserSide=command.get("side").getAsString();
                    browserNative=NativeReview.browser(client,browserPosition,browserSide,true);
                }
                case "browser_select" -> {
                    if(client.screen!=null)client.setScreen(null);
                    int selected=command.has("slot")?command.get("slot").getAsInt():1;if(selected<0||selected>8)throw new IllegalArgumentException("Actual hotbar controller required");client.player.getInventory().selected=selected;client.getConnection().send(new ServerboundSetCarriedItemPacket(selected));
                    client.player.setShiftKeyDown(true);client.getConnection().send(new ServerboundPlayerCommandPacket(client.player,ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY));
                    BlockPos pos=BlockPos.of(command.get("position").getAsLong());Direction side=Direction.valueOf(command.get("side").getAsString());
                    client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getNormal()).scale(.5)),side,pos,false));
                    client.player.setShiftKeyDown(false);client.getConnection().send(new ServerboundPlayerCommandPacket(client.player,ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                }
                case "place_model" -> {BlockPos floor=new BlockPos(5,89,6);client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false));}
                case "destroy_model" -> {destroying=new BlockPos(5,90,6);client.gameMode.startDestroyBlock(destroying,Direction.UP);}
                case "slot" -> {
                    int slot = command.get("slot").getAsInt(); if (slot < 0 || slot > 8) throw new IllegalArgumentException("Hotbar only");
                    client.player.getInventory().selected = slot;
                    client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
                }
                case "use_block" -> {
                    lastCode="";
                    if (client.screen != null) client.screen.onClose();
                    BlockPos pos = new BlockPos(command.get("x").getAsInt(), command.has("y")?command.get("y").getAsInt():90, command.has("z")?command.get("z").getAsInt():8);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false));
                }
                case "drop" -> client.player.drop(true);
                case "close_screen" -> { if (client.screen != null) client.screen.onClose(); }
                case "writer_drop" -> {
                    if (!(client.screen instanceof WriterScreen writer)) throw new IllegalStateException("The real writer screen must be open");
                    String rom=command.has("rom")?command.get("rom").getAsString():"nes";
                    writer.onFilesDrop(List.of(switch(rom){case "gb"->gbDiagnostic();case "gba"->extraDiagnostic("original-arm.gba");case "large_gba"->extraDiagnostic("large-original-arm.gba");case "pce"->extraDiagnostic("original.pce");case "n64"->extraDiagnostic("original-vi.z64");case "arcade"->extraDiagnostic("pong.zip");default->diagnostic();}));
                }
                case "writer_confirm" -> {
                    if (!(client.screen instanceof WriterScreen)) throw new IllegalStateException("Writer screen required");
                    Checkbox box = client.screen.children().stream().filter(Checkbox.class::isInstance).map(Checkbox.class::cast).findFirst().orElseThrow();
                    box.onPress();
                }
                case "button" -> {
                    String text = command.get("text").getAsString();
                    Button button = client.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                            .filter(widget -> widget.getMessage().getString().equals(text)).findFirst().orElseThrow();
                    if (!button.active) throw new IllegalStateException("Actual screen button is disabled: " + text);
                    button.onPress();
                }
                case "rights_negative" -> {
                    if (writerContext == null || !(client.screen instanceof WriterScreen)) throw new IllegalStateException("Open the real writer first");
                    JsonObject payload = writerContext.deepCopy(); payload.addProperty("filename", "two-port-battery.nes");
                    payload.addProperty("title", "Muxi two-port diagnostic"); payload.addProperty("platform", "nes");
                    payload.addProperty("size", Files.size(diagnostic())); payload.addProperty("hash", owner.pin("rom.sha256"));
                    lastCode = ""; Wire.command("upload_begin", payload);
                }
                case "probe" -> {
                    probe = "pending"; String room = command.has("room") ? command.get("room").getAsString() : "";
                    long epoch = command.has("epoch") ? command.get("epoch").getAsLong() : 0;
                    String hash=command.has("hash")?command.get("hash").getAsString():owner.pin("rom.sha256");if(!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("SHA256 required");
                    probeFuture = ConsoleClient.INSTANCE.probeDownload(hash, room, epoch);
                }
                case "toggle_tv" -> {
                    throw new IllegalStateException("The F view shortcut was removed; right click toggles fullscreen modes");
                }
                case "input_source" -> {
                    int mask = command.get("buttons").getAsInt();
                    if (mask < 0) ConsoleClient.INSTANCE.setInputSource(null);
                    else { short[] axes=new short[4];String[] names={"leftX","leftY","rightX","rightY"};for(int i=0;i<4;i++){int value=command.has(names[i])?command.get(names[i]).getAsInt():0;if(value<Short.MIN_VALUE||value>Short.MAX_VALUE)throw new IllegalArgumentException("Signed 16-bit axes only");axes[i]=(short)value;}PortInput input = new PortInput(mask,axes[0],axes[1],axes[2],axes[3]); ConsoleClient.INSTANCE.setInputSource(() -> input); }
                }
                case "repeat_join" -> {
                    var current = ConsoleClient.INSTANCE.snapshot();
                    if (!current.ready() || sessionContext == null) throw new IllegalStateException("An active real session is required");
                    JsonObject context = new JsonObject(); context.add("position", sessionContext.get("position"));
                    ConsoleClient.INSTANCE.device(context, "join", current.port());
                }
                case "handheld_load", "handheld_use" -> {
                    if (client.screen != null) client.screen.onClose();
                    client.player.getInventory().selected = 2;
                    client.getConnection().send(new ServerboundSetCarriedItemPacket(2));
                    boolean loading = action.equals("handheld_load");
                    client.player.setShiftKeyDown(loading);
                    client.getConnection().send(new ServerboundPlayerCommandPacket(client.player, loading
                            ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                    client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    if (loading) {
                        client.player.setShiftKeyDown(false);
                        client.getConnection().send(new ServerboundPlayerCommandPacket(client.player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                    }
                }
                case "pixel_handheld_use" -> {
                    if(client.screen!=null)throw new IllegalStateException("Use the real handheld from the world");
                    int slot=command.get("slot").getAsInt();if(slot!=2&&slot!=7)throw new IllegalArgumentException("The two ordinary pixel fixture handhelds only");
                    client.player.getInventory().selected=slot;client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
                    boolean loading=command.has("load")&&command.get("load").getAsBoolean();
                    client.player.setShiftKeyDown(loading);client.getConnection().send(new ServerboundPlayerCommandPacket(client.player,loading?ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY:ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
                    client.gameMode.useItem(client.player,InteractionHand.MAIN_HAND);
                    if(loading){client.player.setShiftKeyDown(false);client.getConnection().send(new ServerboundPlayerCommandPacket(client.player,ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));}
                }
                case "leave" -> { ConsoleClient.INSTANCE.setInputSource(null); ConsoleClient.INSTANCE.leave(); }
                case "screenshot" -> {
                    String name = command.get("name").getAsString(); if (!name.matches("[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Simple screenshot name required");
                    screenshot = "pending";
                    Screenshot.grab(client.gameDirectory, name + ".png", client.getMainRenderTarget(), component -> screenshot = name + ".png");
                }
                default -> throw new IllegalArgumentException("Unsupported client QA action");
            }
        } catch (IOException error) { throw new IllegalStateException(error); }
        return observe(client);
    }

    private Path diagnostic() throws IOException {
        if (diagnostic == null) {
            Path directory = owner.validatedRoot().resolve(System.getProperty("qa.local.role")).resolve("console-qa");
            Files.createDirectories(directory); diagnostic = directory.resolve("two-port-battery.nes");
            try (var input = ClientQaMod.class.getResourceAsStream("/diagnostics/two-port-battery.nes")) {
                if (input == null) throw new IOException("Missing original diagnostic ROM");
                Files.copy(input, diagnostic);
            }
        }
        return diagnostic;
    }

    private Path gbDiagnostic() throws IOException {
        if(gbDiagnostic==null){Path directory=owner.validatedRoot().resolve(System.getProperty("qa.local.role")).resolve("console-qa");Files.createDirectories(directory);gbDiagnostic=directory.resolve("original.gb");try(var input=ClientQaMod.class.getResourceAsStream("/diagnostics/original.gb")){if(input==null)throw new IOException("Missing original GB diagnostic");Files.copy(input,gbDiagnostic);}}
        return gbDiagnostic;
    }
    private Path extraDiagnostic(String name)throws IOException{
        if(!java.util.Set.of("original-vi.z64","original-arm.gba","large-original-arm.gba","original.pce","pong.zip").contains(name))throw new IOException("Known authored diagnostics only");Path directory=owner.validatedRoot().resolve(System.getProperty("qa.local.role")).resolve("console-qa");Files.createDirectories(directory);Path target=directory.resolve(name);if(!Files.exists(target))try(var input=ClientQaMod.class.getResourceAsStream("/diagnostics/"+name)){if(input==null)throw new IOException("Missing original diagnostic");Files.copy(input,target);}return target;
    }
    private static void UUIDCheck(String value){java.util.UUID.fromString(value);}
    private static JsonObject publicRecord(Object value)throws ReflectiveOperationException{
        JsonObject out=new JsonObject();if(value==null)return out;for(var component:value.getClass().getRecordComponents()){Object field=component.getAccessor().invoke(value);if(field instanceof Number n)out.addProperty(component.getName(),n);else if(field instanceof Boolean b)out.addProperty(component.getName(),b);else if(field instanceof String s)out.addProperty(component.getName(),s);}return out;
    }
    private JsonObject shared(){
        JsonObject out=new JsonObject();try{Class<?> type=Class.forName("net.muxigame.console.client.SharedPlayClient");Object instance=type.getField("INSTANCE").get(null);out=publicRecord(type.getMethod("snapshot").invoke(instance));out.addProperty("available",true);out.add("lease",publicRecord(ConsoleClient.class.getMethod("sharedLease").invoke(ConsoleClient.INSTANCE)));}catch(ReflectiveOperationException|LinkageError error){out.addProperty("available",false);}return out;
    }
    private void sampleShared(){
        JsonObject s=shared();if(!s.has("lease"))return;JsonObject lease=s.getAsJsonObject("lease");if(!lease.has("room"))return;String key=lease.get("room").getAsString()+"/"+lease.get("epoch").getAsLong();if(!key.equals(historyLease)){historyLease=key;videoHistory.clear();audioHistory.clear();}boolean leader=lease.get("leader").getAsBoolean();remember(videoHistory,s,leader?"sentVideoFrame":"lastVideo",leader?"sentVideoCrc":"receivedVideoCrc");remember(audioHistory,s,leader?"sentAudioFrame":"lastAudio",leader?"sentAudioCrc":"receivedAudioCrc");
    }
    private void remember(Map<Long,String> history,JsonObject s,String frame,String crc){if(!s.has(frame)||!s.has(crc))return;long number=s.get(frame).getAsLong();String value=s.get(crc).getAsString();if(number<0||!value.matches("[a-f0-9]{1,8}"))return;history.put(number,value);while(history.size()>128)history.remove(history.keySet().iterator().next());}

    private JsonObject observe(Minecraft client) {
        JsonObject result = new JsonObject(); result.addProperty("connected", client.player != null && client.getConnection() != null);
        result.addProperty("screen", client.screen == null ? "" : client.screen.getClass().getName());
        result.addProperty("snapshotAdapterReady", true); result.addProperty("lastCode", lastCode); result.addProperty("probe", probe); result.addProperty("screenshot", screenshot);
        result.add("browser",NativeReview.browser(client,browserPosition,browserSide,false));result.add("browserNative",browserNative);
        result.addProperty("hideGui",client.options.hideGui);result.addProperty("fov",client.options.fov().get());
        result.addProperty("clientTicks",clientTicks);
        result.add("qaChannel",owner.replyStatus());
        result.addProperty("stateProbe",stateProbe);
        result.addProperty("sharedPcmBytes",sharedPcmBytes);result.addProperty("sharedPcmNonzeroSamples",sharedPcmNonzeroSamples);
        JsonArray pads=new JsonArray();for(var device:net.muxigame.console.client.input.InputProfiles.availableGamepads()){JsonObject pad=new JsonObject();pad.addProperty("id",device.id());pad.addProperty("name",device.name());try{pad.addProperty("guidSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(device.guid().getBytes(java.nio.charset.StandardCharsets.UTF_8))));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}pads.add(pad);}result.add("availableGamepads",pads);
        result.add("shared",shared());JsonObject video=new JsonObject(),audio=new JsonObject();videoHistory.forEach((k,v)->video.addProperty(k.toString(),v));audioHistory.forEach((k,v)->audio.addProperty(k.toString(),v));result.add("sharedVideoHistory",video);result.add("sharedAudioHistory",audio);
        result.add("picture",NativeReview.picture(client));
        result.add("dock",DockActions.observe(client));
        result.add("heldReview",heldActions.observe(client));
        result.add("polish",PolishActions.observe(client));
        result.add("cartridge",CartridgeActions.observe(client));
        var state = ConsoleClient.INSTANCE.snapshot();
        result.addProperty("status", state.status()); result.addProperty("room", state.room()); result.addProperty("epoch", state.epoch());
        result.addProperty("port", state.port()); result.addProperty("ready", state.ready()); result.addProperty("frames", state.frames()); result.addProperty("crc", state.frameCrc());
        result.addProperty("width", state.width()); result.addProperty("height", state.height()); result.addProperty("serverTick", state.serverTick()); result.addProperty("sequence", state.sequence());
        result.addProperty("workerRunning", state.workerRunning()); result.addProperty("lastError", state.lastError()); result.addProperty("downloadsGranted", state.downloadsGranted()); result.addProperty("downloadsDenied", state.downloadsDenied());
        result.addProperty("normalWorkerExits", state.normalWorkerExits()); result.addProperty("forcedWorkerExits", state.forcedWorkerExits()); result.addProperty("failedWorkerExits", state.failedWorkerExits());
        result.addProperty("restoredStateHash", state.restoredStateHash()); result.addProperty("savedStateHash", state.savedStateHash());
        result.addProperty("restoredStates", state.restoredStates()); result.addProperty("savedStates", state.savedStates());
        result.addProperty("sessionStateHash", sessionContext != null && sessionContext.has("state")
                ? sessionContext.getAsJsonObject("state").get("hash").getAsString() : "");
        JsonArray workers = new JsonArray(); state.workers().forEach(workers::add); result.add("workers", workers);
        if (client.screen instanceof WriterScreen writer) { result.addProperty("confirmed", writer.confirmed()); result.addProperty("fileSelected", writer.selectedFile() != null); }
        if (client.player != null) {
            JsonArray cards = new JsonArray();
            JsonArray inventory = new JsonArray();
            for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
                final int index = slot;
                var stack=client.player.getInventory().getItem(slot);
                if(!stack.isEmpty()){JsonObject row=new JsonObject();row.addProperty("slot",slot);row.addProperty("count",stack.getCount());row.addProperty("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());row.addProperty("model",net.muxigame.console.mc.HardwareCatalog.model(stack));row.addProperty("qaPreserve",stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag().getString("qa_preserve"));inventory.add(row);}
                CardData.read(client.player.getInventory().getItem(slot)).ifPresent(card -> {
                    JsonObject row = new JsonObject(); row.addProperty("slot", index); row.addProperty("instance", card.instanceId().toString()); row.addProperty("hash", card.sha256());row.addProperty("platform",card.platform().id()); cards.add(row);
                });
            }
            result.add("cards", cards); result.addProperty("playerName", client.player.getGameProfile().getName());
            result.add("inventory",inventory);
            result.addProperty("gameMode",client.gameMode.getPlayerMode().getName());
            result.addProperty("heldModel",net.muxigame.console.mc.HardwareCatalog.model(client.player.getMainHandItem()));
            result.addProperty("offhandModel",net.muxigame.console.mc.HardwareCatalog.model(client.player.getOffhandItem()));
            result.addProperty("x",client.player.getX());result.addProperty("y",client.player.getY());result.addProperty("z",client.player.getZ());
            result.addProperty("yaw",client.player.getYRot());result.addProperty("pitch",client.player.getXRot());
            Path cache=client.gameDirectory.toPath().resolve("muxi-console/roms");
            try{if(Files.exists(cache)){try(var entries=Files.list(cache)){result.addProperty("cachedRomFiles",entries.count());}}else result.addProperty("cachedRomFiles",0);}catch(IOException error){result.addProperty("cachedRomFiles",-1);}
        }
        return result;
    }
}
