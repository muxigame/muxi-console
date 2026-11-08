package net.muxigame.console.tests.hardware;

import java.util.*;
import net.muxigame.console.mc.HardwareCatalog;
import net.muxigame.console.client.InsertedMediaRenderer;

/** Real media proportions, exposure clipping and fit bounds, independent of running sessions. */
public final class InsertedMediaGeometryTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(var body:HardwareCatalog.bodies()){
            int media=body.platform().customModelData();var placement=HardwareCatalog.mediaPose(body.id(),media).orElseThrow();float[] b=placement.visibleBounds();
            for(int axis=0;axis<3;axis++)check(Float.isFinite(b[axis])&&Float.isFinite(b[axis+3])&&b[axis]>=-.001&&b[axis+3]<=16.001&&b[axis]<b[axis+3],"Native media stays a modest real exposed part inside one device envelope");
            var slot=placement.slot();check(slot.above()?b[slot.clipAxis()]>=slot.clipValue()-.001:b[slot.clipAxis()+3]<=slot.clipValue()+.001,"Buried geometry is clipped at the actual insertion mouth");
            float[] copy=placement.visibleBounds();copy[0]=-999;check(placement.visibleBounds()[0]>-999,"Presentation bounds do not expose mutable metadata");
        }
        float[] gba=HardwareCatalog.mediaPose(5,5).orElseThrow().visibleBounds(),gb=HardwareCatalog.mediaPose(5,3).orElseThrow().visibleBounds(),gbc=HardwareCatalog.mediaPose(5,4).orElseThrow().visibleBounds();
        check(gb[4]>gba[4]+2&&gbc[4]>gba[4]+2,"Earlier tall cards visibly protrude farther from the same GBA rear slot");
        for(int id:new int[]{1,11,20}){var pose=HardwareCatalog.mediaPose(id,id).orElseThrow();check(pose.slot().clipAxis()==2&&!pose.slot().above(),"Front loaders expose horizontal card/disk noses, not giant vertical cartridges");}
        for(int id:new int[]{1,11,20}){
            var pose=HardwareCatalog.mediaPose(id,id).orElseThrow();float[] source=HardwareCatalog.body(id).mediaBounds();float cx=(source[0]+source[3])/32,cz=(source[2]+source[5])/32;
            var bottom=pose.transform().transformPosition(new org.joml.Vector3f(cx,source[1]/16,cz));var top=pose.transform().transformPosition(new org.joml.Vector3f(cx,source[4]/16,cz));
            check(bottom.z>top.z,"Front-loaded connector/read end points inside the machine; upper end is exposed");
            check(pose.transform().transformDirection(new org.joml.Vector3f(0,0,-1)).y>0,"Actual media label faces up in a horizontal slot");
        }
        check(HardwareCatalog.mediaPose(21,21).orElseThrow().slot().above(),"Arcade program module appears in the rear service slot");
        var polygon=List.of(new InsertedMediaRenderer.Vertex(0,0,0,0,0),new InsertedMediaRenderer.Vertex(1,0,0,1,0),new InsertedMediaRenderer.Vertex(1,1,0,1,1),new InsertedMediaRenderer.Vertex(0,1,0,0,1));
        var exposed=InsertedMediaRenderer.clip(polygon,0,.25f,true);check(exposed.size()==4,"Clipping preserves an actual four-sided exposed portion");
        for(var v:exposed){check(v.x()>=.25f,"Clipped card has no vertex inside the case");check(Math.abs(v.u()-v.x())<.0001&&Math.abs(v.v()-v.y())<.0001,"Texture coordinates interpolate with real clipped geometry");}
        check(InsertedMediaRenderer.clip(polygon,1,2,true).isEmpty(),"Fully buried faces emit nothing");
        check(InsertedMediaRenderer.clip(polygon,1,-1,true).equals(polygon),"Fully exposed source faces retain their vertices and UVs");
        System.out.println("InsertedMediaGeometryTest: "+checks+" checks passed");
    }
}
