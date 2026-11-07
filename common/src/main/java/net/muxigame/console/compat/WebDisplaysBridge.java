package net.muxigame.console.compat;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;

/** Optional public-API adapter. No WebDisplays/MCEF classes occur in our linkage graph. */
public final class WebDisplaysBridge {
    private static Api api;private static boolean attempted;
    public record Target(BlockPos origin,String side,Object data){}
    private record Api(Class<?> entity,Class<?> side,Class<?> vector,Class<?> override,Method getScreen,Method rights,Method findOrigin,Method toBlock){}
    private static synchronized Api api(){
        if(attempted)return api;attempted=true;
        try{ClassLoader l=WebDisplaysBridge.class.getClassLoader();Class<?> entity=Class.forName("net.montoyo.wd.entity.ScreenBlockEntity",false,l),side=Class.forName("net.montoyo.wd.utilities.data.BlockSide",false,l),vector=Class.forName("net.montoyo.wd.utilities.math.Vector3i",false,l),override=Class.forName("net.montoyo.wd.utilities.Multiblock$BlockOverride",false,l),multi=Class.forName("net.montoyo.wd.utilities.Multiblock",false,l),data=Class.forName("net.montoyo.wd.entity.ScreenData",false,l);
            api=new Api(entity,side,vector,override,entity.getMethod("getScreen",side),data.getMethod("rightsFor",UUID.class),multi.getMethod("findOrigin",LevelAccessor.class,vector,side,override),vector.getMethod("toBlock"));
        }catch(ReflectiveOperationException|LinkageError unavailable){api=null;}return api;
    }
    public static boolean available(){return api()!=null;}
    private static Object side(Api a,String name)throws ReflectiveOperationException{return a.side.getField(name).get(null);}
    public static Optional<Target> resolve(Level level,BlockPos clicked,Direction face){
        Api a=api();if(a==null||face==null||!level.hasChunkAt(clicked))return Optional.empty();
        try{String sideName=switch(face){case DOWN->"BOTTOM";case UP->"TOP";default->face.name();};Object s=side(a,sideName);Object vector=a.vector.getConstructor(BlockPos.class).newInstance(clicked);Class<?> multi=a.findOrigin.getDeclaringClass();a.findOrigin.invoke(null,level,vector,s,multi.getField("NULL_OVERRIDE").get(null));BlockPos origin=(BlockPos)a.toBlock.invoke(vector);if(clicked.distSqr(origin)>4096)return Optional.empty();return at(level,origin,sideName);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError bad){return Optional.empty();}
    }
    public static Optional<Target> at(Level level,BlockPos origin,String side){
        Api a=api();if(a==null||!level.hasChunkAt(origin))return Optional.empty();
        try{Object entity=level.getBlockEntity(origin);if(!a.entity.isInstance(entity))return Optional.empty();Object data=a.getScreen.invoke(entity,side(a,side));return data==null?Optional.empty():Optional.of(new Target(origin.immutable(),side,data));}
        catch(ReflectiveOperationException|RuntimeException|LinkageError bad){return Optional.empty();}
    }
    public static boolean mayChange(Target target,UUID player){try{return ((int)api().rights.invoke(target.data,player)&1)!=0;}catch(ReflectiveOperationException|RuntimeException|LinkageError bad){return false;}}
    public static String url(Target target)throws ReflectiveOperationException{return (String)target.data.getClass().getField("url").get(target.data);}
    public static void localUrl(Target target,String url)throws ReflectiveOperationException{target.data.getClass().getField("url").set(target.data,url);}
    public static Object browser(Target target)throws ReflectiveOperationException{return target.data.getClass().getField("browser").get(target.data);}
    private static Class<?> browserApi(Object browser)throws ReflectiveOperationException{return Class.forName("org.cef.browser.CefBrowser",false,browser.getClass().getClassLoader());}
    public static String browserUrl(Object browser)throws ReflectiveOperationException{return (String)browserApi(browser).getMethod("getURL").invoke(browser);}
    public static void load(Object browser,String url)throws ReflectiveOperationException{browserApi(browser).getMethod("loadURL",String.class).invoke(browser,url);}
    public static void execute(Object browser,String script)throws ReflectiveOperationException{browserApi(browser).getMethod("executeJavaScript",String.class,String.class,int.class).invoke(browser,script,"",0);}
    private WebDisplaysBridge(){}
}
