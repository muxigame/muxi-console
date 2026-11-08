package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.muxigame.console.client.ConsoleClient;

/** QA-only screen navigation; ordinary screenshot capture remains with the existing owner/driver. */
final class IconActions {
    static JsonObject command(Minecraft client,JsonObject command){
        if(client.player==null||client.level==null)throw new IllegalStateException("A real connected client is required");
        switch(command.get("action").getAsString()){
            case "icon_open"->{
                var state=ConsoleClient.INSTANCE.snapshot();
                if(!state.room().isEmpty()||!state.workers().isEmpty())throw new IllegalStateException("Normally save and leave before reviewing standalone icons");
                if(client.screen!=null&&!(client.screen instanceof IconReviewScreen))throw new IllegalStateException("Return to the ordinary world before opening the icon page");
                String page=command.has("page")?command.get("page").getAsString():"hardware";
                int scale=command.has("scale")?command.get("scale").getAsInt():2;
                client.setScreen(new IconReviewScreen(page,scale));
            }
            case "icon_close"->{if(!(client.screen instanceof IconReviewScreen))throw new IllegalStateException("Actual icon review screen required");client.screen.onClose();}
            default->throw new IllegalArgumentException("Unknown icon review action");
        }
        return observe(client);
    }
    static JsonObject observe(Minecraft client){
        if(client.screen instanceof IconReviewScreen screen)return screen.snapshot();
        JsonObject out=new JsonObject();out.addProperty("open",false);return out;
    }
    private IconActions(){}
}
