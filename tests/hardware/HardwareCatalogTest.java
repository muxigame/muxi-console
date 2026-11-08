package net.muxigame.console.tests.hardware;

import net.muxigame.console.domain.Platform;
import net.muxigame.console.mc.HardwareCatalog;

/** Admission and renderer coordinates must use the actual authored resource, including the second cabinet. */
public final class HardwareCatalogTest {
    private static int checks;
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] ignored){
        check(HardwareCatalog.bodies().size()==22,"21 systems plus two-player cabinet present");
        for(var body:HardwareCatalog.bodies()){
            check(body.id()>=1&&body.id()<=22,"stable ids");check(body.ports()>=1&&body.ports()<=4,"physical port count");
            check(HardwareCatalog.accepts(body.id(),body.platform()),"native media accepted");
            float[] bounds=body.bounds();for(int axis=0;axis<3;axis++)check(bounds[axis]>=0&&bounds[axis]<bounds[axis+3]&&bounds[axis+3]<=16,"authored body bounded");
            bounds[0]=-999;check(body.bounds()[0]>=0,"bounds immutable");
            if(body.handheld()||body.form().equals("arcade"))check(!body.screens().isEmpty(),"physical screen present");
            for(var screen:body.screens())check(screen.x0()<screen.x1()&&screen.y0()<screen.y1()&&screen.z()>=0&&screen.z()<=16,"nonempty screen coordinate contract");
            if(body.form().equals("handheld")||body.form().equals("arcade"))check(!body.controls().isEmpty(),"playable physical controls have feedback surfaces");
            for(var control:body.controls())check(control.port()<body.ports(),"feedback belongs to an existing control seat");
            if(!body.handheld()&&!body.form().equals("arcade"))check(body.controls().isEmpty(),"external-controller consoles do not invent game buttons");
        }
        check(HardwareCatalog.accepts(0,Platform.NES),"legacy generic body preserved");
        check(HardwareCatalog.accepts(5,Platform.GB)&&HardwareCatalog.accepts(5,Platform.GBC),"GBA body accepts earlier cartridges");
        check(!HardwareCatalog.accepts(3,Platform.GBC)&&!HardwareCatalog.accepts(4,Platform.GBA),"incompatible handheld media refused");
        check(HardwareCatalog.accepts(16,Platform.NGP)&&!HardwareCatalog.accepts(15,Platform.NGPC),"NGP generation boundary");
        check(HardwareCatalog.accepts(18,Platform.WS)&&!HardwareCatalog.accepts(17,Platform.WSC),"WonderSwan generation boundary");
        check(HardwareCatalog.ports(19)==1&&HardwareCatalog.screens(19).size()==2,"VB is single-player two-view body");
        check(HardwareCatalog.ports(21)==1&&HardwareCatalog.ports(22)==2,"cabinet control seats match geometry");
        check(!HardwareCatalog.accepts(99,Platform.NES),"unknown typed hardware rejected");
        check(!HardwareCatalog.controls(0).isEmpty()&&!HardwareCatalog.controllerControls().isEmpty(),"generic handheld and physical controller have feedback");
        check(HardwareCatalog.controls(19).isEmpty(),"VB visor does not invent controller buttons");
        for(int id:new int[]{17,18}){
            var buttons=HardwareCatalog.controls(id).stream().map(HardwareCatalog.ControlSurface::button).collect(java.util.stream.Collectors.toSet());
            check(buttons.containsAll(java.util.Set.of(4,5,6,7,10,11,12,13)),"WonderSwan both direction clusters follow the core input ABI");
            check(!buttons.contains(2),"WonderSwan rotate-screen shortcut is not a physical Select button");
        }
        var ports=HardwareCatalog.controls(22).stream().map(HardwareCatalog.ControlSurface::port).collect(java.util.stream.Collectors.toSet());
        check(ports.equals(java.util.Set.of(0,1)),"two-player cabinet feedback distinguishes both actual control decks");
        System.out.println("Hardware catalog: "+checks+" checks passed");
    }
}
