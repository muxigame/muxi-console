package net.muxigame.consoleqa;

import com.google.gson.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.ui.GameScreen;
import net.muxigame.console.domain.Hashing;
import org.lwjgl.glfw.GLFW;

/** Real menu/input callbacks and read-only presentation/HUD observations; no synthetic video, pad or chat. */
final class RotationActions {
    private static final Deque<String> messages=new ArrayDeque<>();
    private static GameScreen lastGame;
    private static JsonObject lastInput=new JsonObject();
    static void message(String text){if(text.contains("MUXI-ROTATION-QA-")){messages.addLast(text);while(messages.size()>8)messages.removeFirst();}}
    static void command(Minecraft mc,JsonObject j)throws Exception{
        boolean nativeRelease=j.get("action").getAsString().equals("rotation_native_key")&&!j.get("pressed").getAsBoolean();
        if(mc.player==null||mc.level==null||(mc.screen==null&&!nativeRelease))throw new IllegalStateException("Actual connected input screen required");
        if(mc.screen instanceof GameScreen game)lastGame=game;
        switch(j.get("action").getAsString()){
            case "rotation_layout_button"->{
                GameScreen game=game(mc);var candidates=game.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.visible&&b.active&&b.getMessage().getString().equals("布局")).toList();
                if(candidates.size()!=1)throw new IllegalStateException("One actual visible dual-screen layout button is required");
                Button button=candidates.getFirst();double x=button.getX()+button.getWidth()/2.0,y=button.getY()+button.getHeight()/2.0;
                game.mouseClicked(x,y,0);game.mouseReleased(x,y,0);lastInput=new JsonObject();lastInput.addProperty("action","actual layout button click");
            }
            case "rotation_untouchable"->{
                GameScreen game=game(mc);JsonObject view=game.presentationSnapshot();
                if(view.get("rotation").getAsInt()!=0||!view.get("mode").getAsString().equals("screen")||!view.get("arrangement").getAsString().equals("side_by_side"))throw new IllegalStateException("Original-direction independent side-by-side screens required");
                JsonObject top=null,bottom=null;for(var item:view.getAsJsonArray("screens")){var row=item.getAsJsonObject();if(row.get("id").getAsString().equals("top"))top=row;if(row.get("id").getAsString().equals("bottom"))bottom=row;}
                if(top==null||bottom==null)throw new IllegalStateException("Actual top and bottom screen rectangles required");
                JsonArray a=top.getAsJsonArray("rect"),b=bottom.getAsJsonArray("rect");String target=j.get("target").getAsString();double x,y;
                if(target.equals("top")){x=a.get(0).getAsDouble()+a.get(2).getAsDouble()/2;y=a.get(1).getAsDouble()+a.get(3).getAsDouble()/2;}
                else if(target.equals("gap")){double right=a.get(0).getAsDouble()+a.get(2).getAsDouble(),left=b.get(0).getAsDouble();if(left<=right)throw new IllegalStateException("An actual gap between source screens is required");x=(right+left)/2;y=b.get(1).getAsDouble()+b.get(3).getAsDouble()/2;}
                else throw new IllegalArgumentException("Only actual top-screen and gap probes are supported");
                if(j.get("pressed").getAsBoolean())game.mouseClicked(x,y,0);else game.mouseReleased(x,y,0);
                lastInput=new JsonObject();lastInput.addProperty("target",target);lastInput.addProperty("screenX",x);lastInput.addProperty("screenY",y);lastInput.addProperty("pressed",j.get("pressed").getAsBoolean());
            }
            case "rotation_button"->{
                GameScreen game=game(mc);List<Button> buttons=game.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.visible&&b.active).toList();
                var candidates=buttons.stream().filter(b->b.getMessage().getString().contains("旋转")||b.getMessage().getString().contains("↻")).toList();
                Button button=candidates.size()==1?candidates.getFirst():buttons.size()==1?buttons.getFirst():null;
                if(button==null)throw new IllegalStateException("One actual visible rotation button is required");
                double x=button.getX()+button.getWidth()/2.0,y=button.getY()+button.getHeight()/2.0;
                game.mouseClicked(x,y,0);game.mouseReleased(x,y,0);lastInput=new JsonObject();lastInput.addProperty("action","actual rotation button click");
            }
            case "rotation_pointer"->{
                GameScreen game=game(mc);JsonObject view=game.presentationSnapshot();int q=view.get("rotation").getAsInt();
                double u=j.get("x").getAsDouble(),v=j.get("y").getAsDouble();if(!Double.isFinite(u)||!Double.isFinite(v)||u<0||u>1||v<0||v>1)throw new IllegalArgumentException("Normalized source coordinates required");
                JsonObject surface=null;for(var item:view.getAsJsonArray("screens")){var row=item.getAsJsonObject();if(row.get("id").getAsString().equals(j.get("surface").getAsString())&&row.get("touchable").getAsBoolean())surface=row;}
                if(surface==null)throw new IllegalStateException("Actual touchable rendered surface required");
                JsonArray rect=surface.getAsJsonArray("virtualRect");double x=rect.get(0).getAsDouble()+u*rect.get(2).getAsDouble(),y=rect.get(1).getAsDouble()+v*rect.get(3).getAsDouble();
                double w=view.get("virtualWidth").getAsDouble(),h=view.get("virtualHeight").getAsDouble();
                // Independent closed-form quarter turns; do not call the product transform under test.
                double gx=switch(q){case 0->x;case 1->h-y;case 2->w-x;case 3->y;default->throw new IllegalStateException("Four-way presentation required");};
                double gy=switch(q){case 0->y;case 1->x;case 2->h-y;case 3->w-x;default->throw new IllegalStateException("Four-way presentation required");};
                if(j.get("pressed").getAsBoolean())game.mouseClicked(gx,gy,0);else game.mouseReleased(gx,gy,0);
                lastInput=new JsonObject();lastInput.addProperty("sourceX",u);lastInput.addProperty("sourceY",v);lastInput.addProperty("screenX",gx);lastInput.addProperty("screenY",gy);lastInput.addProperty("rotation",q);lastInput.addProperty("pressed",j.get("pressed").getAsBoolean());
            }
            case "rotation_native_key"->{
                int key=j.get("key").getAsInt();if(key!=GLFW.GLFW_KEY_F3&&key!=GLFW.GLFW_KEY_ESCAPE&&key!=GLFW.GLFW_KEY_ENTER&&!mc.options.keyChat.matches(key,0)&&!mc.options.keyCommand.matches(key,0))throw new IllegalArgumentException("Only actual chat/debug/exit bindings are supported");
                boolean pressed=j.get("pressed").getAsBoolean();String before=mc.screen==null?"":mc.screen.getClass().getName();
                Method callback=net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",long.class,int.class,int.class,int.class,int.class);callback.setAccessible(true);callback.invoke(mc.keyboardHandler,mc.getWindow().getWindow(),key,0,pressed?GLFW.GLFW_PRESS:GLFW.GLFW_RELEASE,0);
                lastInput=new JsonObject();lastInput.addProperty("key",key);lastInput.addProperty("pressed",pressed);lastInput.addProperty("screenBefore",before);lastInput.addProperty("screenAfter",mc.screen==null?"":mc.screen.getClass().getName());lastInput.addProperty("actualKeyboardCallback",true);
            }
            case "rotation_opening_character"->{
                if(!(mc.screen instanceof ChatScreen))throw new IllegalStateException("A genuine native ChatScreen must receive its opening character");
                int codepoint=j.get("codepoint").getAsInt();if(codepoint!='t'&&codepoint!='/')throw new IllegalArgumentException("Only the actual default chat/command opening characters are supported");
                Method callback=net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("charTyped",long.class,int.class,int.class);callback.setAccessible(true);callback.invoke(mc.keyboardHandler,mc.getWindow().getWindow(),codepoint,0);
                lastInput=new JsonObject();lastInput.addProperty("codepoint",codepoint);lastInput.addProperty("actualCharacterCallback",true);
            }
            case "rotation_chat_text"->{
                if(!(mc.screen instanceof ChatScreen chat))throw new IllegalStateException("A genuine native ChatScreen must receive the text");String text=j.get("text").getAsString();
                if(!text.matches("MUXI-ROTATION-QA-[A-Za-z0-9-]{1,40}"))throw new IllegalArgumentException("Only this run's bounded QA marker may be typed");
                for(char c:text.toCharArray())chat.charTyped(c,0);
            }
            default->throw new IllegalArgumentException("Unknown rotation QA callback");
        }
    }
    private static GameScreen game(Minecraft mc){if(!(mc.screen instanceof GameScreen game)||!ConsoleClient.INSTANCE.snapshot().ready())throw new IllegalStateException("Actual ready fullscreen game required");return game;}
    static JsonObject observe(Minecraft mc){
        JsonObject out=new JsonObject();if(mc.screen instanceof GameScreen game)lastGame=game;
        out.addProperty("gameScreen",mc.screen instanceof GameScreen);out.addProperty("nativeChatScreen",mc.screen instanceof ChatScreen);out.addProperty("gameScreenIdentity",lastGame==null?0:System.identityHashCode(lastGame));
        if(lastGame!=null)out.add("presentation",lastGame.presentationSnapshot());out.add("lastInput",lastInput.deepCopy());JsonArray seen=new JsonArray();messages.forEach(seen::add);out.add("chatMarkers",seen);
        out.addProperty("chatBindingKey",mc.options.keyChat.getKey().getValue());out.addProperty("commandBindingKey",mc.options.keyCommand.getKey().getValue());
        if(mc.screen instanceof ChatScreen)try{var field=ChatScreen.class.getDeclaredField("input");field.setAccessible(true);var edit=(net.minecraft.client.gui.components.EditBox)field.get(mc.screen);out.addProperty("chatInput",edit.getValue());}catch(ReflectiveOperationException missing){throw new IllegalStateException("Actual native chat input is required",missing);}
        ConsoleClient.INSTANCE.saveOwner().ifPresent(owner->{Path file=mc.gameDirectory.toPath().resolve("config/muxi-console-display.json").toAbsolutePath().normalize();try{
            if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&file.toRealPath().equals(file)&&Files.size(file)<=262144){byte[] bytes=Files.readAllBytes(file);JsonObject stored=JsonParser.parseString(new String(bytes,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();String key=ConsoleClient.INSTANCE.inputFeedback().platform().id()+"/"+owner.contentSHA();JsonObject prefs=new JsonObject();prefs.addProperty("schema",stored.get("schema").getAsInt());prefs.addProperty("key",key);prefs.addProperty("sha256",Hashing.sha256(bytes));if(stored.getAsJsonObject("games").has(key))prefs.add("savedRotation",stored.getAsJsonObject("games").get(key));JsonObject layouts=stored.has("layouts")?stored.getAsJsonObject("layouts"):null;prefs.addProperty("savedArrangement",layouts!=null&&layouts.has(key)?layouts.get(key).getAsString():"stacked");out.add("preferences",prefs);}
        }catch(Exception error){throw new IllegalStateException("Read-only display preference evidence failed",error);}});
        try{out.add("hud",(JsonObject)Class.forName("net.muxigame.console.client.ConsoleGameHud").getMethod("snapshot").invoke(null));out.addProperty("hudAvailable",true);}catch(ReflectiveOperationException missing){out.addProperty("hudAvailable",false);}
        return out;
    }
    private RotationActions(){}
}
