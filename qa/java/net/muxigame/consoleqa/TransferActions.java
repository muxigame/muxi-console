package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import net.muxigame.console.mc.Wire;

/** QA-only bounded delivery latency for real ROM replies; never fabricates bytes or loading progress. */
final class TransferActions {
    private record Reply(Wire.Message message,Consumer<Wire.Message> receiver,long due){ }
    private static final ArrayDeque<Reply> waiting=new ArrayDeque<>();
    private static String hash="";private static int delayMillis;private static long delivered;
    static void command(JsonObject command){
        if(!command.get("action").getAsString().equals("transfer_delay"))throw new IllegalArgumentException("Unknown transfer fixture action");
        String selected=command.get("hash").getAsString();int delay=command.get("millis").getAsInt();
        if(!selected.matches("[a-f0-9]{64}")||delay<0||delay>250||!waiting.isEmpty())throw new IllegalArgumentException("Use one original ROM and an idle bounded delay fixture");
        hash=selected;delayMillis=delay;
    }
    static boolean delay(Wire.Message message,Consumer<Wire.Message> receiver){
        if(delayMillis==0||!message.operation().equals("rom_chunk")||!message.body().has("hash")||!hash.equals(message.body().get("hash").getAsString()))return false;
        if(waiting.size()>=4)throw new IllegalStateException("ROM delay fixture queue exceeded its bound");
        waiting.addLast(new Reply(message,receiver,System.nanoTime()+delayMillis*1_000_000L));return true;
    }
    static void tick(){while(!waiting.isEmpty()&&waiting.peekFirst().due<=System.nanoTime()){Reply reply=waiting.removeFirst();reply.receiver.accept(reply.message);delivered++;}}
    static JsonObject observe(){JsonObject value=new JsonObject();value.addProperty("hash",hash);value.addProperty("delayMillis",delayMillis);value.addProperty("queued",waiting.size());value.addProperty("delivered",delivered);value.addProperty("scope","Real received ROM replies delayed before product delivery; no synthetic network data or progress");return value;}
}
