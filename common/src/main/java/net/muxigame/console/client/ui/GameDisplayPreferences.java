package net.muxigame.console.client.ui;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import net.minecraft.client.Minecraft;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.domain.ViewRotation;

/** Local presentation choices belong to the game content, independent of the physical card or save. */
final class GameDisplayPreferences {
    private static final int LIMIT=1024;
    private static final LinkedHashMap<String,Integer> rotations=new LinkedHashMap<>();
    private static final LinkedHashSet<String> sideBySideGames=new LinkedHashSet<>();
    private static boolean loaded;
    private static Path path(){return Minecraft.getInstance().gameDirectory.toPath().resolve("config/muxi-console-display.json");}
    static String key(ConsoleClient client){
        var platform=client.inputFeedback().platform();
        return platform==null?null:client.saveOwner().map(owner->platform.id()+"/"+owner.contentSHA()).orElse(null);
    }
    private static boolean valid(String key){return key!=null&&key.matches("[a-z0-9]+/[0-9a-f]{64}");}
    private static void load(){
        if(loaded)return;loaded=true;Path file=path();
        if(!Files.exists(file))return;
        try{
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>262144)throw new IOException("Invalid display settings file");
            JsonObject root=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.get("schema").getAsInt()!=1)throw new IOException("Unknown display settings version");
            for(var entry:root.getAsJsonObject("games").entrySet())if(valid(entry.getKey())&&rotations.size()<LIMIT){
                int q=entry.getValue().getAsInt();if(q>=0&&q<4)rotations.put(entry.getKey(),q);
            }
            if(root.has("layouts"))for(var entry:root.getAsJsonObject("layouts").entrySet())
                if(valid(entry.getKey())&&sideBySideGames.size()<LIMIT&&entry.getValue().isJsonPrimitive()&&"side_by_side".equals(entry.getValue().getAsString()))sideBySideGames.add(entry.getKey());
        }catch(IOException|RuntimeException failure){rotations.clear();sideBySideGames.clear();MuxiConsole.LOG.warn("Console display settings could not be read; using original presentation");}
    }
    static int rotation(String key){if(!valid(key))return 0;load();return rotations.getOrDefault(key,0);}
    static boolean sideBySide(String key){if(!valid(key))return false;load();return sideBySideGames.contains(key);}
    static void remember(String key,int quarterTurns){
        if(!valid(key))return;load();rotations.remove(key);rotations.put(key,ViewRotation.normalize(quarterTurns));
        while(rotations.size()>LIMIT)rotations.remove(rotations.keySet().iterator().next());
        save();
    }
    static void rememberLayout(String key,boolean sideBySide){
        if(!valid(key))return;load();sideBySideGames.remove(key);if(sideBySide)sideBySideGames.add(key);
        while(sideBySideGames.size()>LIMIT)sideBySideGames.removeFirst();
        save();
    }
    private static void save(){
        Path file=path(),temp=null;
        try{
            Files.createDirectories(file.getParent());
            if(Files.isSymbolicLink(file))throw new IOException("Display settings path is a link");
            JsonObject root=new JsonObject(),games=new JsonObject(),layouts=new JsonObject();root.addProperty("schema",1);
            rotations.forEach(games::addProperty);root.add("games",games);
            sideBySideGames.forEach(key->layouts.addProperty(key,"side_by_side"));root.add("layouts",layouts);
            String json=new GsonBuilder().setPrettyPrinting().create().toJson(root);
            if(json.getBytes(StandardCharsets.UTF_8).length>262144)throw new IOException("Display settings exceed size limit");
            temp=Files.createTempFile(file.getParent(),"muxi-console-display-",".tmp");
            Files.writeString(temp,json,StandardCharsets.UTF_8);
            try{Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException failure){MuxiConsole.LOG.warn("Console display settings could not be saved");}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    private GameDisplayPreferences(){}
}
