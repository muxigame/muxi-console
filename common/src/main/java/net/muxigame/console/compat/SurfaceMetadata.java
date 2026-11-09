package net.muxigame.console.compat;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;
import net.muxigame.console.runtime.AvFrame.Surface;

/** Surface rectangles travel with the encoded atlas; native aspect never follows transport downscaling. */
public final class SurfaceMetadata {
    private SurfaceMetadata(){}
    public static List<Surface> read(JsonObject body,int width,int height)throws IOException{
        if(!body.has("surfaces"))return List.of(new Surface("primary",0,0,width,height,width,height,false));
        try{
            JsonArray entries=body.getAsJsonArray("surfaces");if(entries.size()<1||entries.size()>3)throw new IllegalArgumentException();List<Surface> result=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(JsonElement entry:entries){JsonObject s=entry.getAsJsonObject();Surface surface=new Surface(s.get("id").getAsString(),integer(s,"x"),integer(s,"y"),integer(s,"width"),integer(s,"height"),integer(s,"nativeWidth"),integer(s,"nativeHeight"),s.get("touchable").getAsBoolean());
                if(!ids.add(surface.id())||(long)surface.x()+surface.width()>width||(long)surface.y()+surface.height()>height)throw new IllegalArgumentException();result.add(surface);}
            return List.copyOf(result);
        }catch(RuntimeException invalid){throw new IOException("Invalid video surface metadata",invalid);}
    }
    private static int integer(JsonObject value,String name){String text=value.get(name).getAsString();if(!text.matches("[0-9]{1,9}"))throw new IllegalArgumentException();return Integer.parseInt(text);}
    public static void write(JsonObject body,List<Surface> surfaces){
        JsonArray entries=new JsonArray();for(Surface s:surfaces){JsonObject entry=new JsonObject();entry.addProperty("id",s.id());entry.addProperty("x",s.x());entry.addProperty("y",s.y());entry.addProperty("width",s.width());entry.addProperty("height",s.height());entry.addProperty("nativeWidth",s.nativeWidth());entry.addProperty("nativeHeight",s.nativeHeight());entry.addProperty("touchable",s.touchable());entries.add(entry);}body.add("surfaces",entries);
    }
    public static List<Surface> scale(List<Surface> surfaces,int oldWidth,int oldHeight,int width,int height){
        if(oldWidth<1||oldHeight<1||width<1||height<1)throw new IllegalArgumentException("Invalid atlas size");List<Surface> result=new ArrayList<>();
        if(surfaces.isEmpty())return List.of(new Surface("primary",0,0,width,height,oldWidth,oldHeight,false));
        for(Surface s:surfaces){if((long)s.x()+s.width()>oldWidth||(long)s.y()+s.height()>oldHeight)throw new IllegalArgumentException("Source surface outside atlas");int x=(int)((long)s.x()*width/oldWidth),y=(int)((long)s.y()*height/oldHeight),right=(int)((long)(s.x()+s.width())*width/oldWidth),bottom=(int)((long)(s.y()+s.height())*height/oldHeight);result.add(new Surface(s.id(),x,y,Math.max(1,right-x),Math.max(1,bottom-y),s.nativeWidth(),s.nativeHeight(),s.touchable()));}
        return List.copyOf(result);
    }
}
