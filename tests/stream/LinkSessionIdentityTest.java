package net.muxigame.console.client;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Actual product Session transition, using captured start/closing data and a real committed MXS3 header. */
public final class LinkSessionIdentityTest {
    private static int checks;
    private static void check(boolean valid,String label){checks++;if(!valid)throw new AssertionError(label);}
    private static void reject(LinkPlayClient.Session session,byte[] header)throws Exception{try{session.requirePortableHeader(new DataInputStream(new ByteArrayInputStream(header)));throw new AssertionError("Wrong portable format accepted");}catch(IOException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        JsonObject fixture=JsonParser.parseString(Files.readString(Path.of("tests/stream/fixtures/three-ds-session.json"))).getAsJsonObject(),start=fixture.getAsJsonObject("start"),closing=fixture.getAsJsonObject("closing");byte[] header=HexFormat.of().parseHex(fixture.get("nativeHeaderHex").getAsString());String bridge=start.get("bridge").getAsString(),single=start.get("single").getAsString();
        LinkPlayClient.Session session=new LinkPlayClient.Session(start);
        try{
            session.requirePortableHeader(new DataInputStream(new ByteArrayInputStream(header)));check(true,"actual automatic state header matches actual initial bridge identity");
            session.context=closing.deepCopy();check(!session.context.has("bridge")&&!session.context.has("single"),"actual closing view reproduces missing startup fields");
            check(session.epoch()==closing.get("epoch").getAsLong()&&session.epoch()>start.get("epoch").getAsLong(),"address revocation still uses the new closing epoch");
            check(session.core.bridge().equals(bridge)&&session.core.single().equals(single),"both startup core identities survive actual context replacement");
            session.requirePortableHeader(new DataInputStream(new ByteArrayInputStream(header)));check(true,"final checkpoint validates actual MXS3 without reading mutable closing metadata");
            start.addProperty("bridge","0".repeat(64));start.addProperty("single","1".repeat(64));check(session.core.bridge().equals(bridge)&&session.core.single().equals(single),"later caller mutation cannot alter immutable core identity");
            byte[] wrong=header.clone();wrong[4]^=1;reject(session,wrong);wrong=header.clone();wrong[3]='2';reject(session,wrong);reject(session,Arrays.copyOf(header,35));
            JsonObject malformed=fixture.getAsJsonObject("start").deepCopy();malformed.remove("bridge");try{new LinkPlayClient.Session(malformed);throw new AssertionError("Missing startup identity accepted");}catch(IOException expected){checks++;}
        }finally{session.audio.close();}
        System.out.println("LinkSessionIdentityTest "+checks+" checks passed; actual start -> closing -> MXS3 validation");
    }
}
