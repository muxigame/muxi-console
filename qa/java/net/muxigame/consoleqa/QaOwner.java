package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.function.Function;
import net.neoforged.fml.ModList;

/** A new, run-scoped QA channel. It has no production command registration or product package classes. */
public final class QaOwner {
    private final String modId;
    private final String expectedRole;
    private final Properties pins = new Properties();
    private long previousId = -1;
    private int throttle;

    public QaOwner(String modId, String expectedRole) {
        this.modId = modId;
        this.expectedRole = expectedRole;
        try (InputStream input = QaOwner.class.getResourceAsStream("/console-qa-pins.properties")) {
            if (input == null) throw new IOException("Missing QA build pins");
            pins.load(input);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    public void poll(Function<JsonObject, JsonObject> execute) {
        if (++throttle % 4 != 0) return;
        Path root;
        JsonObject command;
        JsonObject result = new JsonObject();
        try {
            root = validatedRoot();
            String role = System.getProperty("qa.local.role", "");
            Path input = root.resolve("console-qa/command-" + role + ".json");
            if (!Files.isRegularFile(input)) return;
            if (Files.size(input) > 32768) throw new IOException("Oversize QA command");
            command = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
            long id = command.get("id").getAsLong();
            if (id <= previousId) return;
            if (!command.get("runId").getAsString().equals(System.getProperty("qa.local.runId"))) return;
            if (command.get("pid").getAsLong() != ProcessHandle.current().pid()) return;
            String product = modHash("muxi_console"), qa = modHash(modId);
            if (!product.equals(pins.getProperty("product.sha256")) || !product.equals(command.get("productSha256").getAsString())
                    || !qa.equals(command.get("qaSha256").getAsString())) throw new IOException("Actual loaded product/QA hash differs from the build pins");
            previousId = id;
            result.addProperty("id", id); result.addProperty("runId", System.getProperty("qa.local.runId"));
            result.addProperty("role", role); result.addProperty("pid", ProcessHandle.current().pid());
            result.addProperty("productSha256", product); result.addProperty("qaSha256", qa);
            result.addProperty("physicalOSInput", false);
            try {
                result.add("result", execute.apply(command));
                result.addProperty("ok", true);
            } catch (Throwable failure) {
                result.addProperty("ok", false); result.addProperty("error", failure.toString());
            }
            Path directory = root.resolve("console-qa"); Files.createDirectories(directory);
            Path temporary = directory.resolve("reply-" + role + ".tmp");
            Files.writeString(temporary, result.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve("reply-" + role + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            // An unowned or malformed command has no authority to write outside its validated run.
            System.err.println("Muxi Console QA refused: " + error.getMessage());
        }
    }

    public Path validatedRoot() throws IOException {
        String supplied = System.getProperty("qa.local.root", "");
        if (supplied.isBlank()) throw new IOException("QA requires the unified runner");
        Path absolute = Path.of(supplied).toAbsolutePath().normalize(), real = absolute.toRealPath();
        if (!absolute.equals(real)) throw new IOException("QA run links are forbidden");
        Path runParent = real.getParent(), slot = runParent == null ? null : runParent.getParent();
        if (slot == null || !runParent.getFileName().toString().equals("runs") || !slot.getFileName().toString().matches("client-[1-9][0-9]*")
                || !slot.getParent().getFileName().toString().equals("test-environment") || !real.getFileName().toString().matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
            throw new IOException("QA is restricted to numbered unified runs");
        JsonObject marker = JsonParser.parseString(Files.readString(real.resolve("local-mc-owner.json"))).getAsJsonObject();
        String runId = System.getProperty("qa.local.runId", ""), role = System.getProperty("qa.local.role", "");
        if (!runId.matches("[a-f0-9]{32}") || !runId.equals(marker.get("runId").getAsString())
                || !marker.get("instanceRoot").getAsString().equals(real.toString()) || !marker.get("offlineLoopback").getAsBoolean()
                || marker.get("productionMutation").getAsBoolean()) throw new IOException("Runner identity mismatch");
        if (expectedRole.equals("server") ? !role.equals("server") : !role.matches("host|guest|client[0-9]+")) throw new IOException("Unexpected role");
        JsonObject process = marker.getAsJsonObject("processes").getAsJsonObject(role);
        if (process == null || process.get("pid").getAsLong() != ProcessHandle.current().pid()
                || !Path.of(process.get("root").getAsString()).toRealPath().equals(real.resolve(role))) throw new IOException("Actual QA PID/root mismatch");
        double created = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() / 1000.0;
        if (Math.abs(created - process.get("processCreateTime").getAsDouble()) > 0.1) throw new IOException("QA PID generation mismatch");
        JsonObject lease = JsonParser.parseString(Files.readString(slot.resolve("lease.json"))).getAsJsonObject();
        if (!lease.get("instanceRoot").getAsString().equals(real.toString())) throw new IOException("Numbered slot lease differs");
        return real;
    }

    public String pin(String name) { return pins.getProperty(name); }

    public String playerName(String role) throws IOException {
        if (!role.equals("host") && !role.equals("guest")) throw new IllegalArgumentException("Expected a real client role");
        JsonObject marker = JsonParser.parseString(Files.readString(validatedRoot().resolve("local-mc-owner.json"))).getAsJsonObject();
        return marker.getAsJsonObject("clientNames").get(role).getAsString();
    }

    private static String modHash(String id) throws IOException {
        Path path = ModList.get().getModFileById(id).getFile().getFilePath();
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
            byte[] block = new byte[32768]; for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
