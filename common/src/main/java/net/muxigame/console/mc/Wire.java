package net.muxigame.console.mc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.muxigame.console.MuxiConsole;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Our bounded command/event transport, versioned independently of emulator worker IPC. */
public final class Wire {
    public static final Gson JSON=new Gson();
    public record Message(String operation, JsonObject body, byte[] data) {
        public Message {
            if (operation==null || !operation.matches("[a-z_]{1,32}") || body==null || data==null || data.length>24576)
                throw new IllegalArgumentException("Invalid message bounds");
            int metadata=JSON.toJson(body).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(metadata>16384||metadata+data.length>31744)throw new IllegalArgumentException("Message exceeds wire byte budget");
        }
    }
    public record ToServer(Message message) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MuxiConsole.ID,"command"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ToServer> CODEC=StreamCodec.of((b,p)->write(b,p.message),b->new ToServer(read(b)));
        public Type<ToServer> type(){return TYPE;}
    }
    public record ToClient(Message message) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MuxiConsole.ID,"event"));
        public static final StreamCodec<RegistryFriendlyByteBuf,ToClient> CODEC=StreamCodec.of((b,p)->write(b,p.message),b->new ToClient(read(b)));
        public Type<ToClient> type(){return TYPE;}
    }
    private static void write(RegistryFriendlyByteBuf b, Message m){b.writeUtf(m.operation,32);b.writeUtf(JSON.toJson(m.body),16384);b.writeByteArray(m.data);}
    private static Message read(RegistryFriendlyByteBuf b){return new Message(b.readUtf(32),JSON.fromJson(b.readUtf(16384),JsonObject.class),b.readByteArray(24576));}
    public static void register(RegisterPayloadHandlersEvent event){
        var r=event.registrar("mx-console-5");
        r.playToServer(ToServer.TYPE,ToServer.CODEC,(p,c)->c.enqueueWork(()->{if(c.player() instanceof ServerPlayer s)ConsoleServer.receive(s,p.message);}));
        r.playToClient(ToClient.TYPE,ToClient.CODEC,(p,c)->c.enqueueWork(()->ClientGate.receiver.accept(p.message)));
    }
    public static void send(ServerPlayer p,String op,JsonObject body,byte[] bytes){PacketDistributor.sendToPlayer(p,new ToClient(new Message(op,body,bytes)));}
    public static void send(ServerPlayer p,String op,JsonObject body){send(p,op,body,new byte[0]);}
    public static void command(String op,JsonObject body,byte[] bytes){PacketDistributor.sendToServer(new ToServer(new Message(op,body,bytes)));}
    public static void command(String op,JsonObject body){command(op,body,new byte[0]);}
    private Wire(){}
}
