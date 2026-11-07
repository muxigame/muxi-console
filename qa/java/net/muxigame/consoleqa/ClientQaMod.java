package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
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
    public ClientQaMod() { NeoForge.EVENT_BUS.addListener(this::tick); }
    private void tick(ClientTickEvent.Post event) {
        if (!listening) {
            var productReceiver = ClientGate.receiver;
            ClientGate.receiver = message -> {
                productReceiver.accept(message);
                if (message.operation().equals("writer_open")) writerContext = message.body().deepCopy();
                if (message.operation().equals("session_start")) sessionContext = message.body().deepCopy();
                if (message.operation().equals("error")) lastCode = message.body().get("code").getAsString();
            };
            listening = true;
        }
        if (probeFuture != null && probeFuture.isDone()) {
            try { probe = probeFuture.join().isPresent() ? "granted" : "denied"; }
            catch (RuntimeException error) { probe = "error:" + error.getMessage(); }
            probeFuture = null;
        }
        owner.poll(this::dispatch);
    }
    private JsonObject dispatch(JsonObject command) {
        Minecraft client = Minecraft.getInstance();
        String action = command.get("action").getAsString();
        try {
            switch (action) {
                case "observe" -> { }
                case "slot" -> {
                    int slot = command.get("slot").getAsInt(); if (slot < 0 || slot > 8) throw new IllegalArgumentException("Hotbar only");
                    client.player.getInventory().selected = slot;
                    client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
                }
                case "use_block" -> {
                    if (client.screen != null) client.screen.onClose();
                    BlockPos pos = new BlockPos(command.get("x").getAsInt(), 90, 8);
                    client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(pos), Direction.SOUTH, pos, false));
                }
                case "drop" -> client.player.drop(true);
                case "close_screen" -> { if (client.screen != null) client.screen.onClose(); }
                case "writer_drop" -> {
                    if (!(client.screen instanceof WriterScreen writer)) throw new IllegalStateException("The real writer screen must be open");
                    writer.onFilesDrop(List.of(diagnostic()));
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
                    probeFuture = ConsoleClient.INSTANCE.probeDownload(owner.pin("rom.sha256"), room, epoch);
                }
                case "toggle_tv" -> {
                    if (!(client.screen instanceof GameScreen screen)) throw new IllegalStateException("Game screen required");
                    screen.keyPressed(GLFW.GLFW_KEY_F, 0, 0); screen.keyReleased(GLFW.GLFW_KEY_F, 0, 0);
                }
                case "input_source" -> {
                    int mask = command.get("buttons").getAsInt();
                    if (mask < 0) ConsoleClient.INSTANCE.setInputSource(null);
                    else { PortInput input = new PortInput(mask); ConsoleClient.INSTANCE.setInputSource(() -> input); }
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

    private JsonObject observe(Minecraft client) {
        JsonObject result = new JsonObject(); result.addProperty("connected", client.player != null && client.getConnection() != null);
        result.addProperty("screen", client.screen == null ? "" : client.screen.getClass().getName());
        result.addProperty("snapshotAdapterReady", true); result.addProperty("lastCode", lastCode); result.addProperty("probe", probe); result.addProperty("screenshot", screenshot);
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
            for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
                final int index = slot;
                CardData.read(client.player.getInventory().getItem(slot)).ifPresent(card -> {
                    JsonObject row = new JsonObject(); row.addProperty("slot", index); row.addProperty("instance", card.instanceId().toString()); row.addProperty("hash", card.sha256()); cards.add(row);
                });
            }
            result.add("cards", cards); result.addProperty("playerName", client.player.getGameProfile().getName());
        }
        return result;
    }
}
