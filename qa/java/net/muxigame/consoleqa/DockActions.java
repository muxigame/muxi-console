package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.muxigame.console.client.ConsoleClient;
import net.muxigame.console.client.ui.GameScreen;
import org.lwjgl.glfw.GLFW;

/** Calls ordinary gameplay/UI entry points; no writes to emulator or server product state. */
final class DockActions {
    private BlockPos destroying;
    void tick(Minecraft client){if(destroying==null||client.level==null||client.gameMode==null)return;if(client.level.getBlockState(destroying).isAir()){client.gameMode.stopDestroyBlock();destroying=null;}else client.gameMode.continueDestroyBlock(destroying,Direction.UP);}
    private static BlockPos position(JsonObject j){BlockPos p=new BlockPos(j.get("x").getAsInt(),j.get("y").getAsInt(),j.get("z").getAsInt());if(p.getX()<3||p.getX()>25||p.getY()<89||p.getY()>94||p.getZ()<4||p.getZ()>25)throw new IllegalArgumentException("Product interaction outside isolated platform");return p;}
    void command(Minecraft client,JsonObject j){
        if(client.player==null||client.gameMode==null)throw new IllegalStateException("Real joined player required");String action=j.get("action").getAsString();
        switch(action){
            case "dock_place","dock_use_block","dock_sneak_block"->{if(client.screen!=null)throw new IllegalStateException("Release the actual UI before a world use");BlockPos p=position(j);Direction face=Direction.valueOf(j.has("face")?j.get("face").getAsString():"UP");boolean sneak=action.equals("dock_sneak_block");if(sneak)shift(client,true);client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(p).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.5)),face,p,false));if(sneak)shift(client,false);}
            case "dock_break"->{if(client.screen!=null)throw new IllegalStateException("Normal world break requires released UI");destroying=position(j);client.gameMode.startDestroyBlock(destroying,Direction.UP);}
            case "dock_use_item"->{if(client.screen!=null)throw new IllegalStateException("Ordinary held item use requires the world");client.gameMode.useItem(client.player,InteractionHand.MAIN_HAND);}
            case "dock_f"->{if(client.screen instanceof GameScreen screen){screen.keyPressed(GLFW.GLFW_KEY_F,0,0);screen.keyReleased(GLFW.GLFW_KEY_F,0,0);}else ConsoleClient.INSTANCE.openHeldControls();}
            case "dock_esc"->{if(client.screen instanceof GameScreen screen)screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);else ConsoleClient.INSTANCE.leaveHeldWorld();}
            case "dock_right_click"->{if(!(client.screen instanceof net.muxigame.console.client.ui.HeldGameScreen screen))throw new IllegalStateException("Actual held control screen required");screen.mouseClicked(0,0,1);}
            case "dock_release"->{ConsoleClient.INSTANCE.releaseControls();client.setScreen(null);}
            case "dock_view"->{client.options.fov().set(45);client.options.bobView().set(false);client.options.hideGui=j.has("hideGui")&&j.get("hideGui").getAsBoolean();client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);}
            default->throw new IllegalArgumentException("Unsupported dock client action");
        }
    }
    private static void shift(Minecraft c,boolean pressed){c.player.setShiftKeyDown(pressed);c.getConnection().send(new ServerboundPlayerCommandPacket(c.player,pressed?ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY:ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));}
    static JsonObject observe(Minecraft client){JsonObject out=new JsonObject();out.addProperty("handheldSession",ConsoleClient.INSTANCE.handheldSession());out.addProperty("worldPlaying",ConsoleClient.INSTANCE.heldWorldPlaying());out.addProperty("controlsReleased",ConsoleClient.INSTANCE.heldControlsReleased());if(client.screen instanceof GameScreen s)out.addProperty("worldView",s.worldView());return out;}
}
