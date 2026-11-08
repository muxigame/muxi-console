package net.muxigame.console.mc;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import net.muxigame.console.domain.Platform;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

/** The authored model catalog is the single coordinate contract for bodies and live screens. */
public final class HardwareCatalog {
    public record Plane(float x0,float y0,float x1,float y1,float z){}
    public record ControlSurface(int button,int port,String facing,float x0,float y0,float z0,float x1,float y1,float z1){}
    public record ControlRegion(int button,int port,float x0,float y0,float z0,float x1,float y1,float z1){}
    public record MediaSlot(float width,float baseX,float baseY,float baseZ,float rotationX,float rotationY,float rotationZ,float depthScale,boolean top,int clipAxis,boolean above,float clipValue){}
    public record MediaPose(Matrix4f transform,float[] visibleBounds,MediaSlot slot){
        public MediaPose{transform=new Matrix4f(transform);visibleBounds=visibleBounds.clone();}
        @Override public Matrix4f transform(){return new Matrix4f(transform);}
        @Override public float[] visibleBounds(){return visibleBounds.clone();}
    }
    public record Body(int id,Platform platform,String name,String form,int controllerPorts,List<Plane> screens,float[] bounds,List<ControlSurface> controls,List<ControlRegion> regions,float[] mediaBounds,MediaSlot mediaSlot){
        public Body{screens=List.copyOf(screens);bounds=bounds.clone();controls=List.copyOf(controls);regions=List.copyOf(regions);mediaBounds=mediaBounds==null?null:mediaBounds.clone();}
        public boolean handheld(){return form.equals("handheld")||form.equals("viewer");}
        public int ports(){return controllerPorts;}
        @Override public float[] bounds(){return bounds.clone();}
        @Override public float[] mediaBounds(){return mediaBounds==null?null:mediaBounds.clone();}
        public boolean accepts(Platform media){return platform==media||switch(platform){
            case GBC -> media==Platform.GB;
            case GBA -> media==Platform.GB||media==Platform.GBC;
            case NGPC -> media==Platform.NGP;
            case WSC -> media==Platform.WS;
            default -> false;
        };}
    }
    private record Catalog(Map<Integer,Body> bodies,List<ControlSurface> generic,List<ControlSurface> controller,List<ControlRegion> genericRegions,List<ControlRegion> controllerRegions,float[] genericMediaBounds,MediaSlot genericMediaSlot,MediaSlot genericDesktopMediaSlot){}
    private static final Catalog CATALOG=load();
    private static final Map<Integer,Body> BODIES=CATALOG.bodies();
    private static final Plane TV=new Plane(1.5f,3f,14.5f,12.75f,6.90f);
    public static Collection<Body> bodies(){return BODIES.values();}
    public static Body body(int id){return BODIES.get(id);}
    public static int model(ItemStack item){CustomModelData data=item.get(DataComponents.CUSTOM_MODEL_DATA);return data==null?0:data.value();}
    public static ItemStack variant(ItemStack item,int id){if(id<0||id>22||id!=0&&!BODIES.containsKey(id))throw new IllegalArgumentException("Unknown body");if(id==0)item.remove(DataComponents.CUSTOM_MODEL_DATA);else item.set(DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(id));return item;}
    public static boolean accepts(int body,Platform media){Body b=body(body);return body==0||b!=null&&b.accepts(media);}
    public static int ports(int body){Body b=body(body);return b==null?4:b.ports();}
    public static List<Plane> screens(int body){Body b=body(body);return b==null?List.of():b.screens();}
    public static List<ControlSurface> controls(int body){Body b=body(body);return body==0?CATALOG.generic():b==null?List.of():b.controls();}
    public static List<ControlSurface> controllerControls(){return CATALOG.controller();}
    public static List<ControlRegion> regions(int body){Body b=body(body);return body==0?CATALOG.genericRegions():b==null?List.of():b.regions();}
    public static List<ControlRegion> controllerRegions(){return CATALOG.controllerRegions();}
    public static Plane television(){return TV;}
    public static float[] presentationBounds(ItemStack item){
        int model=model(item);Body body=body(model);float[] bounds=body==null?new float[]{0,3,5.8f,16,12.4f,8.5f}:body.bounds();
        if(item.is(net.muxigame.console.mc.ConsoleContent.HANDHELD.get()))mediaPose(model,CardData.inserted(item)).ifPresent(pose->{float[] extra=pose.visibleBounds();for(int i=0;i<3;i++){bounds[i]=Math.min(bounds[i],extra[i]);bounds[i+3]=Math.max(bounds[i+3],extra[i+3]);}});
        return bounds;
    }
    public static Optional<MediaPose> mediaPose(int body,ItemStack card){
        if(card.isEmpty()||!card.is(ConsoleContent.CARTRIDGE.get())||CardData.read(card).isEmpty())return Optional.empty();
        return mediaPose(body,model(card));
    }
    public static Optional<MediaPose> desktopMediaPose(int body,ItemStack card){
        if(card.isEmpty()||!card.is(ConsoleContent.CARTRIDGE.get())||CardData.read(card).isEmpty())return Optional.empty();
        return body==0?makeMediaPose(CATALOG.genericDesktopMediaSlot(),model(card)):mediaPose(body,model(card));
    }
    /** Pure geometry query; callers render only an actual inserted ItemStack. */
    public static Optional<MediaPose> mediaPose(int body,int mediaModel){
        Body device=body(body),media=body(mediaModel);MediaSlot slot=device==null?CATALOG.genericMediaSlot():device.mediaSlot();
        return makeMediaPose(slot,mediaModel);
    }
    private static Optional<MediaPose> makeMediaPose(MediaSlot slot,int mediaModel){
        Body media=body(mediaModel);if(slot==null)return Optional.empty();float[] source=media==null||media.mediaBounds()==null?CATALOG.genericMediaBounds().clone():media.mediaBounds();
        float scale=slot.width()/(source[3]-source[0]),sx=scale,sy=scale,sz=scale*slot.depthScale();
        Matrix4f rotation=new Matrix4f().rotateX((float)Math.toRadians(slot.rotationX())).rotateY((float)Math.toRadians(slot.rotationY())).rotateZ((float)Math.toRadians(slot.rotationZ()));
        float cx=(source[0]+source[3])/32,cy=(source[1]+source[4])/32,cz=(source[2]+source[5])/32;
        Matrix4f centered=new Matrix4f(rotation).scale(sx,sy,sz).translate(-cx,-cy,-cz);float[] rotated=transformBounds(centered,source);
        float y=slot.baseY()/16-(slot.top()?rotated[4]:rotated[1])/16;
        Matrix4f transform=new Matrix4f().translation(slot.baseX()/16,y,slot.baseZ()/16).mul(centered);float[] visible=transformBounds(transform,source);
        if(slot.above())visible[slot.clipAxis()]=Math.max(visible[slot.clipAxis()],slot.clipValue());else visible[slot.clipAxis()+3]=Math.min(visible[slot.clipAxis()+3],slot.clipValue());
        for(int i=0;i<3;i++)if(visible[i]>=visible[i+3])return Optional.empty();
        return Optional.of(new MediaPose(transform,visible,slot));
    }
    private static float[] transformBounds(Matrix4f transform,float[] bounds){
        float[] result={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(int corner=0;corner<8;corner++){
            Vector3f p=new Vector3f(bounds[(corner&1)==0?0:3]/16,bounds[(corner&2)==0?1:4]/16,bounds[(corner&4)==0?2:5]/16);transform.transformPosition(p);
            for(int axis=0;axis<3;axis++){float value=p.get(axis)*16;result[axis]=Math.min(result[axis],value);result[axis+3]=Math.max(result[axis+3],value);}
        }
        return result;
    }
    private static Catalog load(){
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
                if(result.put(id,new Body(id,Platform.fromId(j.get("platform").getAsString()),name,form,ports,screens,bounds,controls(j,"control_surfaces"),regions(j,"button_regions"),j.has("media_bounds")?box(j.getAsJsonObject("media_bounds")):null,slot(j.getAsJsonObject("media_slot"))))!=null)throw new IOException("Duplicate body id");
            }
            if(result.size()!=22)throw new IOException("Incomplete hardware catalog");return new Catalog(Collections.unmodifiableMap(result),controls(root,"generic_control_surfaces"),controls(root,"controller_control_surfaces"),regions(root,"generic_button_regions"),regions(root,"controller_button_regions"),box(root.getAsJsonObject("generic_media_bounds")),slot(root.getAsJsonObject("generic_media_slot")),slot(root.getAsJsonObject("generic_desktop_media_slot")));
        }catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    private static float[] box(JsonObject object)throws IOException{
        JsonArray lo=object.getAsJsonArray("min"),hi=object.getAsJsonArray("max");float[] bounds=new float[6];
        for(int i=0;i<3;i++){bounds[i]=lo.get(i).getAsFloat();bounds[i+3]=hi.get(i).getAsFloat();if(!Float.isFinite(bounds[i])||!Float.isFinite(bounds[i+3])||bounds[i]>=bounds[i+3])throw new IOException("Invalid media model bounds");}
        return bounds;
    }
    private static MediaSlot slot(JsonObject object)throws IOException{
        if(object==null)return null;JsonArray base=object.getAsJsonArray("base"),rotation=object.getAsJsonArray("rotation");JsonObject clip=object.getAsJsonObject("clip");
        String axis=clip.get("axis").getAsString(),keep=clip.get("keep").getAsString();int index=axis.equals("x")?0:axis.equals("y")?1:axis.equals("z")?2:-1;
        MediaSlot slot=new MediaSlot(object.get("width").getAsFloat(),base.get(0).getAsFloat(),base.get(1).getAsFloat(),base.get(2).getAsFloat(),rotation.get(0).getAsFloat(),rotation.get(1).getAsFloat(),rotation.get(2).getAsFloat(),object.get("depth_scale").getAsFloat(),object.get("align").getAsString().equals("top"),index,keep.equals("above"),clip.get("value").getAsFloat());
        for(float value:new float[]{slot.width,slot.baseX,slot.baseY,slot.baseZ,slot.rotationX,slot.rotationY,slot.rotationZ,slot.depthScale,slot.clipValue})if(!Float.isFinite(value))throw new IOException("Invalid media slot coordinates");
        if(index<0||!Set.of("above","below").contains(keep)||slot.width<=0||slot.width>16||slot.depthScale<=0||slot.depthScale>2)throw new IOException("Invalid media slot");return slot;
    }
    private static Plane plane(JsonObject j){JsonArray a=j.getAsJsonArray("from"),b=j.getAsJsonArray("to");return new Plane(a.get(0).getAsFloat(),a.get(1).getAsFloat(),b.get(0).getAsFloat(),b.get(1).getAsFloat(),a.get(2).getAsFloat());}
    private static List<ControlRegion> regions(JsonObject object,String key)throws IOException{
        if(!object.has(key))return List.of();List<ControlRegion> result=new ArrayList<>();
        for(JsonElement row:object.getAsJsonArray(key)){
            JsonObject j=row.getAsJsonObject();int button=j.get("button").getAsInt(),port=j.has("port")?j.get("port").getAsInt():0;
            JsonArray a=j.getAsJsonArray("from"),b=j.getAsJsonArray("to");float[] f=new float[6];
            for(int i=0;i<3;i++){f[i]=a.get(i).getAsFloat();f[i+3]=b.get(i).getAsFloat();if(!Float.isFinite(f[i])||!Float.isFinite(f[i+3])||f[i]<0||f[i+3]>16||f[i]>=f[i+3])throw new IOException("Invalid button volume");}
            if(button<0||button>15||port<0||port>3)throw new IOException("Invalid button assignment");
            result.add(new ControlRegion(button,port,f[0],f[1],f[2],f[3],f[4],f[5]));
        }
        return List.copyOf(result);
    }
    private static List<ControlSurface> controls(JsonObject object,String key)throws IOException{
        if(!object.has(key))return List.of();List<ControlSurface> result=new ArrayList<>();
        for(JsonElement row:object.getAsJsonArray(key)){
            JsonObject j=row.getAsJsonObject();int button=j.get("button").getAsInt(),port=j.has("port")?j.get("port").getAsInt():0;String facing=j.has("facing")?j.get("facing").getAsString():"north";
            JsonArray a=j.getAsJsonArray("from"),b=j.getAsJsonArray("to");float[] f=new float[6];for(int i=0;i<3;i++){f[i]=a.get(i).getAsFloat();f[i+3]=b.get(i).getAsFloat();if(!Float.isFinite(f[i])||!Float.isFinite(f[i+3])||f[i]<0||f[i+3]>16||f[i]>f[i+3])throw new IOException("Invalid control bounds");}
            if(button<0||button>15||port<0||port>3||!(facing.equals("north")&&f[2]==f[5]&&f[0]<f[3]&&f[1]<f[4]||facing.equals("up")&&f[1]==f[4]&&f[0]<f[3]&&f[2]<f[5]))throw new IOException("Invalid control face");
            result.add(new ControlSurface(button,port,facing,f[0],f[1],f[2],f[3],f[4],f[5]));
        }
        return List.copyOf(result);
    }
    private HardwareCatalog(){}
}
