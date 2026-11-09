package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** No static JEI references: the same QA artifact must also boot when JEI is absent. */
final class RecipeBridge {
    private static final boolean ENABLED=RecipeBridge.class.getResource("/diagnostics/recipe-review.enabled")!=null;
    static boolean enabled(){return ENABLED;}
    private static Object invoke(String name,Class<?>[] types,Object...args){
        if(!ENABLED||!ModList.get().isLoaded("jei"))throw new IllegalStateException("This recipe QA action requires the explicitly installed JEI");
        try{return Class.forName("net.muxigame.consoleqa.RecipeActions").getDeclaredMethod(name,types).invoke(null,args);}
        catch(InvocationTargetException e){throw new IllegalStateException(e.getCause());}
        catch(ReflectiveOperationException e){throw new IllegalStateException("Pinned recipe QA bridge missing",e);}
    }
    static void command(Minecraft mc,JsonObject j){invoke("command",new Class<?>[]{Minecraft.class,JsonObject.class},mc,j);}
    static JsonObject observe(Minecraft mc){
        JsonObject out=new JsonObject();
        for(String id:new String[]{"muxi_console","muxi_console_runtime","jei"}){
            JsonObject row=new JsonObject();boolean loaded=ModList.get().isLoaded(id);row.addProperty("loaded",loaded);
            if(loaded)row.addProperty("version",ModList.get().getModContainerById(id).orElseThrow().getModInfo().getVersion().toString());
            out.add(id,row);
        }
        out.addProperty("physicalOSInput",false);
        if(ModList.get().isLoaded("jei"))out.add("ui",(JsonObject)invoke("observe",new Class<?>[]{Minecraft.class},mc));
        return out;
    }
    private RecipeBridge(){}
}
