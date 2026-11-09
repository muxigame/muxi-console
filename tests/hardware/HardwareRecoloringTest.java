package net.muxigame.console.tests.hardware;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.item.DyeColor;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.mc.HardwareCatalog;
import net.muxigame.console.mc.recipe.HardwareRecoloring;

/** The dye route must preserve the shipped model/compatibility contract, not invent another platform ID. */
public final class HardwareRecoloringTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        Set<Integer> models=new HashSet<>();int palms=0,desktops=0;
        for(DyeColor dye:DyeColor.values())for(boolean handheld:new boolean[]{true,false})for(boolean lens:new boolean[]{true,false}){
            int model=HardwareRecoloring.target(handheld,dye,lens);
            if(model==0)continue;
            check(models.add(model),"Each existing body has exactly one unambiguous dye recipe");
            var body=HardwareCatalog.body(model);check(body!=null&&body.handheld()==handheld,"Recipe result stays in its original item family");
            check(body.accepts(body.platform()),"Recipe body accepts its own actual platform media");
            if(handheld&&!lens)palms++;if(!handheld)desktops++;
            if(lens)check(model==19&&dye==DyeColor.RED&&body.form().equals("viewer"),"VB remains the existing separate lens viewer");
        }
        check(palms==11&&desktops==13,"Nine original handhelds plus two dual-screen shells, and thirteen desktop/cabinet shells");
        check(models.size()==25&&models.containsAll(java.util.stream.IntStream.rangeClosed(1,25).boxed().toList()),"Old CMD1..22 remain craftable; new models only use CMD23..25");
        check(HardwareRecoloring.target(true,DyeColor.WHITE,false)==3,"White handheld core is GB CMD3");
        check(HardwareRecoloring.target(true,DyeColor.PURPLE,false)==5,"Purple handheld core is GBA CMD5");
        check(HardwareRecoloring.target(false,DyeColor.RED,false)==1,"Red desktop core is NES CMD1");
        check(HardwareRecoloring.target(false,DyeColor.LIME,false)==22,"Lime desktop core preserves the two-player cabinet CMD22");
        check(HardwareRecoloring.target(true,DyeColor.BLACK,false)==23&&HardwareRecoloring.target(true,DyeColor.CYAN,false)==24&&HardwareRecoloring.target(false,DyeColor.LIGHT_BLUE,false)==25,"Three new dyes use the explicit stable NDS/3DS/Wii IDs");
        for(DyeColor dye:new DyeColor[]{DyeColor.MAGENTA,DyeColor.LIGHT_BLUE,DyeColor.GREEN,DyeColor.BROWN})
            check(HardwareRecoloring.target(true,dye,false)==0,"Unused handheld dyes do not advertise unsupported future systems");
        check(HardwareRecoloring.target(true,DyeColor.RED,false)==0,"A red dye alone does not silently create the viewer");
        check(HardwareRecoloring.target(false,DyeColor.RED,true)==0,"Viewer lens cannot change the source item family");
        check(HardwareRecoloring.target(true,null,false)==0,"Missing dye cannot reshape hardware");
        int gb=HardwareRecoloring.target(true,DyeColor.WHITE,false),gbc=HardwareRecoloring.target(true,DyeColor.LIME,false),gba=HardwareRecoloring.target(true,DyeColor.PURPLE,false);
        check(HardwareCatalog.accepts(gba,Platform.GB)&&HardwareCatalog.accepts(gba,Platform.GBC)&&HardwareCatalog.accepts(gba,Platform.GBA),"GBA reshape can retain each real backward-compatible card");
        check(HardwareCatalog.accepts(gbc,Platform.GB)&&!HardwareCatalog.accepts(gb,Platform.GBC)&&!HardwareCatalog.accepts(gbc,Platform.GBA),"Reshape cannot force later cards into earlier hardware");
        check(!HardwareCatalog.accepts(gba,Platform.NES),"An unrelated installed game is never accepted by a handheld reshape");
        System.out.println("HardwareRecoloringTest: "+checks+" checks passed; real ItemStack/menu transfer still requires native QA");
    }
}
