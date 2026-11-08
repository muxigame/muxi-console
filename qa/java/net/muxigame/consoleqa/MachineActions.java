package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import net.muxigame.console.client.ConsoleClient;

/** Current session ownership is read from the actual coordinator, independently of the carrier card. */
final class MachineActions {
    static JsonObject observe(){JsonObject out=new JsonObject();ConsoleClient.INSTANCE.saveOwner().ifPresent(key->{out.addProperty("machine",key.machineUUID().toString());out.addProperty("content",key.contentSHA());});return out;}
}
