package net.muxigame.console.tests.stream;

import com.google.gson.*;
import java.io.IOException;
import java.util.List;
import net.muxigame.console.compat.SurfaceMetadata;
import net.muxigame.console.runtime.AvFrame.Surface;

public final class SurfaceMetadataTests {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void denied(JsonObject body,int width,int height)throws Exception{try{SurfaceMetadata.read(body,width,height);throw new AssertionError("Invalid atlas was accepted");}catch(IOException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        List<Surface> surfaces=List.of(new Surface("top",0,0,400,240,400,240,false),new Surface("bottom",40,240,320,240,320,240,true));
        var scaled=SurfaceMetadata.scale(surfaces,400,480,200,240);check(scaled.get(0).width()==200&&scaled.get(0).height()==120,"top rectangle follows encoded size");check(scaled.get(1).x()==20&&scaled.get(1).y()==120&&scaled.get(1).width()==160,"bottom rectangle retains its position");check(scaled.get(0).nativeWidth()==400&&scaled.get(1).nativeWidth()==320,"native aspects survive downscaling");check(scaled.get(1).touchable()&&!scaled.get(0).touchable(),"touch applies only to lower screen");
        JsonObject packet=new JsonObject();SurfaceMetadata.write(packet,scaled);check(SurfaceMetadata.read(packet,200,240).equals(scaled),"network metadata retains each independent LCD");
        JsonObject invalid=packet.deepCopy();invalid.getAsJsonArray("surfaces").get(1).getAsJsonObject().addProperty("id","top");denied(invalid,200,240);
        invalid=packet.deepCopy();invalid.getAsJsonArray("surfaces").get(0).getAsJsonObject().addProperty("width",201);denied(invalid,200,240);
        invalid=packet.deepCopy();invalid.getAsJsonArray("surfaces").get(0).getAsJsonObject().addProperty("x",-1);denied(invalid,200,240);
        invalid=packet.deepCopy();invalid.getAsJsonArray("surfaces").get(0).getAsJsonObject().addProperty("width",1.5);denied(invalid,200,240);
        check(SurfaceMetadata.read(new JsonObject(),160,144).getFirst().id().equals("primary"),"existing one-screen streams retain their display");
        System.out.println("SurfaceMetadataTests: "+checks+" checks passed");
    }
}
