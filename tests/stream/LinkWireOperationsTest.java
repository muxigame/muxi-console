package net.muxigame.console.tests.stream;

import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import net.muxigame.console.domain.LinkMessages;
import net.muxigame.console.mc.Wire;

/** Real MC Wire admission for every actual Link client/persistence literal, including cold reads and replies. */
public final class LinkWireOperationsTest {
    public static void main(String[] args)throws Exception{
        Set<String> names=new TreeSet<>();Pattern quoted=Pattern.compile("\"(link_[a-z0-9_]+)\"");
        for(String file:List.of("client/LinkPlayClient.java","mc/LinkDsPersistence.java","mc/Link3dsPersistence.java")){
            String source=Files.readString(Path.of("common/src/main/java/net/muxigame/console").resolve(file));Matcher matcher=quoted.matcher(source);while(matcher.find())names.add(matcher.group(1));
        }
        for(boolean threeDs:List.of(false,true)){names.add(LinkMessages.read(threeDs));names.add(LinkMessages.reply(threeDs));}
        for(String required:List.of("link_threeds_profile_begin","link_threeds_profile_piece","link_threeds_profile_commit","link_threeds_state_begin","link_threeds_state_piece","link_threeds_state_commit","link_threeds_finish","link_threeds_abort","link_threeds_final_check"))if(!names.contains(required))throw new AssertionError("Actual operation missing: "+required);
        int checks=0;for(String name:names){new Wire.Message(name,new JsonObject(),new byte[24576]);checks++;}
        for(String invalid:List.of("link_3ds_read","link_3ds_profile_begin","link_3ds_reply","a".repeat(33))){try{new Wire.Message(invalid,new JsonObject(),new byte[0]);throw new AssertionError("Illegal MC operation accepted: "+invalid);}catch(IllegalArgumentException expected){checks++;}}
        try{new Wire.Message(LinkMessages.read(true),new JsonObject(),new byte[24577]);throw new AssertionError("Chunk bound weakened");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("LinkWireOperationsTest "+checks+" checks passed against actual Wire.Message; read/upload/final/reply namespaces covered");
    }
}
