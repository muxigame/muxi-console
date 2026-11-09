package net.muxigame.consoleqa;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.client.ui.WriterScreen;
import net.muxigame.console.domain.*;
import net.muxigame.console.mc.Wire;

/** Normal callbacks and read-only actual AV/native-file evidence. Never fabricates a pad, card or save. */
final class ExpansionActions {
    private static JsonObject lastSession=new JsonObject();
    private static long profileReadBytes,profileChunks;
    static void received(Wire.Message message){
        if(message.operation().equals("session_start")){
            JsonObject next=new JsonObject();for(String key:List.of("room","epoch","machine","content","hash","instance","platform","extension","nativeFiles","frameFlow","state","profile"))if(message.body().has(key))next.add(key,message.body().get(key).deepCopy());lastSession=next;
        }
        if(message.operation().equals("profile_chunk")){profileReadBytes+=message.data().length;profileChunks++;}
    }
    static void command(Minecraft mc,JsonObject j)throws Exception{
        if(mc.player==null||mc.gameMode==null)throw new IllegalStateException("Actual connected player required");
        String action=j.get("action").getAsString();
        if(action.equals("expansion_drop")){
            if(!(mc.screen instanceof WriterScreen writer))throw new IllegalStateException("Actual writer menu required");
            writer.onFilesDrop(List.of(diagnostic(mc,j.get("platform").getAsString())));return;
        }
        if(action.equals("expansion_place")){
            if(mc.screen!=null)throw new IllegalStateException("Normal world placement required");BlockPos floor=new BlockPos(5,89,6);
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(floor).add(0,.5,0),Direction.UP,floor,false));return;
        }
        if(action.equals("expansion_observe"))return;
        if(!(mc.screen instanceof GameScreen screen)||!ConsoleClient.INSTANCE.snapshot().ready())throw new IllegalStateException("Focused ready original game required");
        Platform platform=ConsoleClient.INSTANCE.inputFeedback().platform();
        switch(action){
            case "expansion_source"->ConsoleClient.INSTANCE.setInputSource(()->mc.screen instanceof GameScreen?NativeControls.sample(platform):net.muxigame.console.runtime.PortInput.IDLE);
            case "expansion_key"->{KeyMapping mapping=j.has("extra")?NativeControls.extra(platform,NativeControls.Extra.valueOf(j.get("extra").getAsString())):NativeControls.key(platform,j.get("bit").getAsInt());if(mapping.isUnbound()||mapping.getKey().getType()!=InputConstants.Type.KEYSYM||mapping.getKeyModifier()!=net.neoforged.neoforge.client.settings.KeyModifier.NONE)throw new IllegalStateException("Use the actual unmodified keyboard binding");int key=mapping.getKey().getValue();if(j.get("pressed").getAsBoolean())screen.keyPressed(key,0,0);else screen.keyReleased(key,0,0);}
            case "expansion_pointer"->{String id=j.get("surface").getAsString();double x=j.get("x").getAsDouble(),y=j.get("y").getAsDouble();if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)throw new IllegalArgumentException("Normalized actual surface position required");JsonObject area=null;for(var value:screen.presentationSnapshot().getAsJsonArray("screens")){var candidate=value.getAsJsonObject();if(candidate.get("id").getAsString().equals(id)&&candidate.get("touchable").getAsBoolean())area=candidate;}if(area==null)throw new IllegalStateException("Actual rendered touchable surface required");var rect=area.getAsJsonArray("rect");double gx=rect.get(0).getAsDouble()+x*rect.get(2).getAsDouble(),gy=rect.get(1).getAsDouble()+y*rect.get(3).getAsDouble();if(j.get("pressed").getAsBoolean())screen.mouseClicked(gx,gy,0);else screen.mouseReleased(gx,gy,0);}
            default->throw new IllegalArgumentException("Unknown expansion frontend action");
        }
    }
    private static Path diagnostic(Minecraft mc,String platform)throws Exception{
        String suffix=switch(platform){case "nds"->"nds";case "n3ds"->"3dsx";case "wii"->"dol";default->throw new IllegalArgumentException("Only our three original diagnostics");};
        String name="original-expansion."+suffix;Properties pins=new Properties();try(var in=ExpansionActions.class.getResourceAsStream("/console-qa-pins.properties")){if(in==null)throw new IOException("Pinned QA metadata missing");pins.load(in);}
        String expected=pins.getProperty("expansion-"+platform+"-rom.sha256");if(expected==null||!expected.matches("[a-f0-9]{64}"))throw new IOException("Build this QA with explicit expansion diagnostics");
        Path directory=mc.gameDirectory.toPath().resolve("console-qa-diagnostics").toAbsolutePath().normalize();Files.createDirectories(directory);if(!directory.toRealPath().equals(directory))throw new IOException("Ordinary QA diagnostic directory required");Path file=directory.resolve(name);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))try(var in=ExpansionActions.class.getResourceAsStream("/diagnostics/"+name)){if(in==null)throw new IOException("Original diagnostic resource missing");try(var out=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){byte[] block=new byte[32768];int total=0;for(int n;(n=in.read(block))!=-1;){total+=n;if(total>4*1024*1024)throw new IOException("Original diagnostic exceeds fixture budget");out.write(block,0,n);}}}
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!Hashing.sha256(file).equals(expected))throw new IOException("Original diagnostic SHA differs");return file;
    }
    static JsonObject observe(Minecraft mc){
        JsonObject out=new JsonObject();out.add("lastSession",lastSession.deepCopy());out.addProperty("profileReadBytes",profileReadBytes);out.addProperty("profileChunks",profileChunks);out.add("surfaces",new Gson().toJsonTree(ConsoleClient.INSTANCE.surfaces()));
        var feedback=ConsoleClient.INSTANCE.inputFeedback();out.add("input",new Gson().toJsonTree(feedback.input()));
        ConsoleClient.INSTANCE.frameFlowMetrics().ifPresent(flow->out.add("frameFlow",new Gson().toJsonTree(flow)));
        var picture=ConsoleClient.INSTANCE.picture();if(picture!=null&&mc.getTextureManager().getTexture(picture)instanceof DynamicTexture dynamic&&dynamic.getPixels()!=null){var image=dynamic.getPixels();JsonObject samples=new JsonObject();for(var surface:ConsoleClient.INSTANCE.surfaces()){int pixel=image.getPixelRGBA(surface.x()+surface.width()/2,surface.y()+surface.height()/2);JsonArray rgb=new JsonArray();rgb.add(pixel&255);rgb.add(pixel>>>8&255);rgb.add(pixel>>>16&255);samples.add(surface.id(),rgb);}out.add("centreRGB",samples);}
        if(lastSession.has("machine")&&lastSession.has("platform"))try{
            String platform=lastSession.get("platform").getAsString();UUID machine=UUID.fromString(lastSession.get("machine").getAsString());String relative=switch(platform){case "n3ds"->"Azahar/sdmc/muxi-console-original-save.dat";case "wii"->"User/Wii/shared2/muxi-console-original-save.dat";default->"";};
            if(!relative.isEmpty()){Path root=mc.gameDirectory.toPath().resolve("muxi-console/machine-profiles").toAbsolutePath().normalize();Path file=root.resolve(machine.toString()).resolve(platform).resolve(relative);if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&file.toRealPath().equals(file)&&Files.size(file)<=64){byte[] bytes=Files.readAllBytes(file);JsonObject nativeFile=new JsonObject();nativeFile.addProperty("relative",relative);nativeFile.addProperty("bytes",bytes.length);nativeFile.addProperty("sha256",Hashing.sha256(bytes));var buffer=ByteBuffer.wrap(bytes).order(platform.equals("wii")?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN);JsonArray words=new JsonArray();while(buffer.remaining()>=4)words.add(buffer.getInt());nativeFile.add("words",words);out.add("nativeDiagnosticFile",nativeFile);}}
        }catch(Exception error){out.addProperty("nativeFileReadError",error.getClass().getSimpleName());}
        return out;
    }
}
