package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import java.lang.reflect.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.muxigame.console.mc.Wire;

/** Optional QA seam: non-latency builds do not load or compile any new product API consumer. */
final class LatencyBridge {
    private static final boolean ENABLED=LatencyBridge.class.getResource("/diagnostics/original-realtime.gba")!=null;
    static boolean enabled(){return ENABLED;}
    private static Object invoke(String name,Class<?>[] types,Object...arguments){
        if(!ENABLED)throw new IllegalStateException("This exact QA build did not opt in to latency review");
        try{return Class.forName("net.muxigame.consoleqa.LatencyActions").getDeclaredMethod(name,types).invoke(null,arguments);}
        catch(InvocationTargetException error){throw new IllegalStateException(error.getCause());}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Pinned latency QA implementation is absent",error);}
    }
    static boolean delay(Wire.Message message,Consumer<Wire.Message> receiver){return ENABLED&&(boolean)invoke("delay",new Class<?>[]{Wire.Message.class,Consumer.class},message,receiver);}
    static void command(Minecraft client,JsonObject body,QaOwner owner){invoke("command",new Class<?>[]{Minecraft.class,JsonObject.class,QaOwner.class},client,body,owner);}
    static JsonObject observe(Minecraft client){return (JsonObject)invoke("observe",new Class<?>[]{Minecraft.class},client);}
    private LatencyBridge(){}
}
