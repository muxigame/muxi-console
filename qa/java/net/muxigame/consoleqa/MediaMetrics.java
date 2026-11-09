package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.muxigame.console.mc.Wire;

/** QA-only ingress counters. No bodies, signed tickets or ROM bytes are retained. */
final class MediaMetrics {
    private static final int MAX_OPERATIONS=64;
    private static final class Count {
        long packets,dataBytes,metadataBytes,wireBytes,nonzeroPcmSamples;
    }
    private final long started=System.nanoTime();
    private final Map<String,Count> operations=new LinkedHashMap<>();

    void accept(Wire.Message message){
        String op=message.operation();
        if(!operations.containsKey(op)&&operations.size()>=MAX_OPERATIONS)op="other";
        Count count=operations.computeIfAbsent(op,ignored->new Count());
        int operationBytes=message.operation().getBytes(StandardCharsets.UTF_8).length;
        int metadataBytes=Wire.JSON.toJson(message.body()).getBytes(StandardCharsets.UTF_8).length;
        int dataBytes=message.data().length;
        count.packets++;count.dataBytes+=dataBytes;count.metadataBytes+=metadataBytes;
        if(message.operation().endsWith("_audio")&&message.body().has("rate")&&message.body().get("rate").getAsInt()==48000)
            for(int i=0;i+1<dataBytes;i+=2)if(message.data()[i]!=0||message.data()[i+1]!=0)count.nonzeroPcmSamples++;
        count.wireBytes+=(long)varIntBytes(operationBytes)+operationBytes
                +varIntBytes(metadataBytes)+metadataBytes+varIntBytes(dataBytes)+dataBytes;
    }

    JsonObject snapshot(){
        JsonObject result=new JsonObject(),rows=new JsonObject();
        result.addProperty("measuredAtNanos",System.nanoTime());
        result.addProperty("startedAtNanos",started);
        result.addProperty("scope","received custom payload before Minecraft framing, compression, encryption and TCP/IP");
        operations.forEach((op,count)->{
            JsonObject row=new JsonObject();row.addProperty("packets",count.packets);
            row.addProperty("dataBytes",count.dataBytes);row.addProperty("metadataBytes",count.metadataBytes);
            row.addProperty("wireBytes",count.wireBytes);rows.add(op,row);
            row.addProperty("nonzeroPcmSamples",count.nonzeroPcmSamples);
        });
        result.add("operations",rows);return result;
    }

    private static int varIntBytes(int value){int bytes=1;while((value&~0x7f)!=0){bytes++;value>>>=7;}return bytes;}
}
