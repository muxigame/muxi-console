package net.muxigame.console.mc;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.muxigame.console.domain.Platform;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

/** The authored model catalog is the single coordinate contract for bodies and live screens. */
public final class HardwareCatalog {
    public record Plane(float x0,float y0,float x1,float y1,float z){}
    public record Body(int id,Platform platform,String name,String form,int controllerPorts,List<Plane> screens,float[] bounds){
        public Body{screens=List.copyOf(screens);bounds=bounds.clone();}
        public boolean handheld(){return form.equals("handheld")||form.equals("viewer");}
        public int ports(){return controllerPorts;}
        @Override public float[] bounds(){return bounds.clone();}
        public boolean accepts(Platform media){return platform==media||switch(platform){
            case GBC -> media==Platform.GB;
            case GBA -> media==Platform.GB||media==Platform.GBC;
            case NGPC -> media==Platform.NGP;
            case WSC -> media==Platform.WS;
            default -> false;
        };}
    }
    private static final Map<Integer,Body> BODIES=load();
    private static final Plane TV=new Plane(1.5f,3f,14.5f,12.75f,6.90f);
    public static Collection<Body> bodies(){return BODIES.values();}
    public static Body body(int id){return BODIES.get(id);}
    public static int model(ItemStack item){CustomModelData data=item.get(DataComponents.CUSTOM_MODEL_DATA);return data==null?0:data.value();}
    public static ItemStack variant(ItemStack item,int id){if(id<0||id>22||id!=0&&!BODIES.containsKey(id))throw new IllegalArgumentException("Unknown body");if(id==0)item.remove(DataComponents.CUSTOM_MODEL_DATA);else item.set(DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(id));return item;}
    public static boolean accepts(int body,Platform media){Body b=body(body);return body==0||b!=null&&b.accepts(media);}
    public static int ports(int body){Body b=body(body);return b==null?4:b.ports();}
    public static List<Plane> screens(int body){Body b=body(body);return b==null?List.of():b.screens();}
    public static Plane television(){return TV;}
    private static Map<Integer,Body> load(){
        try(var input=HardwareCatalog.class.getResourceAsStream("/assets/muxi_console/model_catalog.json")){
            if(input==null)throw new IOException("Missing authored hardware catalog");
            JsonObject root=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            if(root.get("schema").getAsInt()!=1)throw new IOException("Unknown hardware catalog");
            Map<Integer,Body> result=new TreeMap<>();
            List<JsonElement> entries=new ArrayList<>();root.getAsJsonArray("entries").forEach(entries::add);root.getAsJsonArray("additional_styles").forEach(entries::add);
            for(JsonElement row:entries){
                JsonObject j=row.getAsJsonObject();int id=j.get("cmd").getAsInt();List<Plane> screens=new ArrayList<>();
                if(j.has("screen_planes")){for(JsonElement p:j.getAsJsonArray("screen_planes"))screens.add(plane(p.getAsJsonObject()));}
                else if(j.has("screen_plane")&&!j.get("screen_plane").isJsonNull())screens.add(plane(j.getAsJsonObject("screen_plane")));
                JsonObject box=j.getAsJsonObject("bounds");JsonArray min=box.getAsJsonArray("min"),max=box.getAsJsonArray("max");float[] bounds=new float[6];for(int k=0;k<3;k++){bounds[k]=min.get(k).getAsFloat();bounds[k+3]=max.get(k).getAsFloat();}
                String name=j.has("zh_name")?j.get("zh_name").getAsString():"双人街机";String form=j.has("form_factor")?j.get("form_factor").getAsString():"arcade";
                int ports=j.has("cabinet_seats")?j.get("cabinet_seats").getAsInt():j.get("controller_ports").getAsInt();
                if(result.put(id,new Body(id,Platform.fromId(j.get("platform").getAsString()),name,form,ports,screens,bounds))!=null)throw new IOException("Duplicate body id");
            }
            if(result.size()!=22)throw new IOException("Incomplete hardware catalog");return Collections.unmodifiableMap(result);
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    private static Plane plane(JsonObject j){JsonArray a=j.getAsJsonArray("from"),b=j.getAsJsonArray("to");return new Plane(a.get(0).getAsFloat(),a.get(1).getAsFloat(),b.get(0).getAsFloat(),b.get(1).getAsFloat(),a.get(2).getAsFloat());}
    private HardwareCatalog(){}
}
