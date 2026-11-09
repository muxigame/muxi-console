package net.muxigame.consoleqa;

import com.google.gson.*;
import java.lang.reflect.*;
import java.util.*;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.gui.recipes.IRecipeLayoutWithButtons;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.gui.recipes.lookups.ILookupState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.muxigame.console.mc.HardwareCatalog;
import org.lwjgl.glfw.GLFW;

/** Ordinary real input callbacks plus read-only inspection of the pinned JEI's currently open GUI. */
final class RecipeActions {
    private static JsonObject lastInput=new JsonObject();
    static void command(Minecraft mc,JsonObject j)throws Exception{
        if(mc.player==null||mc.level==null||RecipeProbePlugin.runtime==null)throw new IllegalStateException("Live JEI runtime and connected player required");
        switch(j.get("action").getAsString()){
            case "recipe_search"->{
                if(!(mc.screen instanceof InventoryScreen))throw new IllegalStateException("Search only on the real inventory overlay");
                String text=j.get("text").getAsString();if(!text.matches("[a-z0-9 ]{0,32}"))throw new IllegalArgumentException("Bounded search text required");
                RecipeProbePlugin.runtime.getIngredientFilter().setFilterText(text);
            }
            case "recipe_key"->{
                if(mc.screen==null)throw new IllegalStateException("Actual inventory/recipe screen required");
                int key=j.get("key").getAsInt();if(key!=GLFW.GLFW_KEY_U&&key!=GLFW.GLFW_KEY_R&&key!=GLFW.GLFW_KEY_ESCAPE)throw new IllegalArgumentException("Only U, R and Escape are supported");
                boolean pressed=j.get("pressed").getAsBoolean();String before=mc.screen.getClass().getName();
                // This is the actual GLFW frontend callback, including NeoForge's screen pre/post
                // listeners. Calling Screen.keyPressed alone would bypass JEI's global U/R handler.
                Method callback=net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("keyPress",long.class,int.class,int.class,int.class,int.class);callback.setAccessible(true);
                callback.invoke(mc.keyboardHandler,mc.getWindow().getWindow(),key,0,pressed?GLFW.GLFW_PRESS:GLFW.GLFW_RELEASE,0);
                lastInput=new JsonObject();lastInput.addProperty("key",key);lastInput.addProperty("pressed",pressed);lastInput.addProperty("screenBefore",before);lastInput.addProperty("screenAfter",mc.screen==null?"":mc.screen.getClass().getName());lastInput.addProperty("actualKeyboardCallback",true);
            }
            case "recipe_transfer"->{
                if(!(mc.screen instanceof RecipesGui))throw new IllegalStateException("Actual JEI recipe page required");
                int target=j.get("model").getAsInt();if(target<1||target>25)throw new IllegalArgumentException("Existing model required");
                IRecipeLayoutDrawable<?> chosen=null;
                for(Object row:layouts(mc)){
                    var layout=((IRecipeLayoutWithButtons<?>)row).getRecipeLayout();
                    if(layout.getRecipe() instanceof RecipeHolder<?> holder&&holder.value() instanceof CraftingRecipe recipe&&HardwareCatalog.model(recipe.getResultItem(mc.level.registryAccess()))==target){chosen=layout;break;}
                }
                if(chosen==null)throw new IllegalStateException("Target recipe is not on the actual visible page");
                var rect=chosen.getRecipeTransferButtonArea();var origin=chosen.getRect();double x=origin.getX()+rect.getX()+rect.getWidth()/2.0,y=origin.getY()+rect.getY()+rect.getHeight()/2.0;
                click(mc,x,y);
                lastInput=new JsonObject();lastInput.addProperty("transferModel",target);lastInput.addProperty("x",x);lastInput.addProperty("y",y);lastInput.addProperty("actualMouseCallback",true);
            }
            case "recipe_next"->{
                if(!(mc.screen instanceof RecipesGui))throw new IllegalStateException("Actual JEI recipe page required");
                var button=(mezz.jei.gui.elements.IconButton)field(mc.screen,"nextPage");
                click(mc,button.getX()+button.getWidth()/2.0,button.getY()+button.getHeight()/2.0);
            }
            default->throw new IllegalArgumentException("Unknown recipe frontend action");
        }
    }
    private static void click(Minecraft mc,double x,double y)throws Exception{
        move(mc,x,y);Method press=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onPress",long.class,int.class,int.class,int.class);press.setAccessible(true);
        press.invoke(mc.mouseHandler,mc.getWindow().getWindow(),0,GLFW.GLFW_PRESS,0);press.invoke(mc.mouseHandler,mc.getWindow().getWindow(),0,GLFW.GLFW_RELEASE,0);
    }
    private static void move(Minecraft mc,double x,double y)throws Exception{
        Method callback=net.minecraft.client.MouseHandler.class.getDeclaredMethod("onMove",long.class,double.class,double.class);callback.setAccessible(true);
        callback.invoke(mc.mouseHandler,mc.getWindow().getWindow(),x*mc.getWindow().getScreenWidth()/mc.screen.width,y*mc.getWindow().getScreenHeight()/mc.screen.height);
    }
    private static Object field(Object source,String name)throws Exception{Field f=source.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(source);}
    private static List<?> layouts(Minecraft mc)throws Exception{return (List<?>)field(field(mc.screen,"layouts"),"recipeLayoutsWithButtons");}
    private static JsonObject stack(ItemStack stack){JsonObject row=new JsonObject();row.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());row.addProperty("model",HardwareCatalog.model(stack));row.addProperty("count",stack.getCount());row.addProperty("name",stack.getHoverName().getString());return row;}
    private static JsonObject recipe(Minecraft mc,Object value){
        JsonObject out=new JsonObject();if(!(value instanceof RecipeHolder<?> holder)||!(holder.value() instanceof CraftingRecipe recipe))return out;
        out.addProperty("id",holder.id().toString());out.add("output",stack(recipe.getResultItem(mc.level.registryAccess())));
        JsonArray ingredients=new JsonArray();for(var ingredient:recipe.getIngredients()){JsonArray alternatives=new JsonArray();for(var item:ingredient.getItems())alternatives.add(stack(item));ingredients.add(alternatives);}out.add("ingredients",ingredients);
        // Evaluate the actual displayed predicate on the original stack; no mutation or substitute stack.
        if(mc.player!=null){ItemStack source=mc.player.getInventory().getItem(2);out.addProperty("firstIngredientAcceptsRealSlot2",!recipe.getIngredients().isEmpty()&&recipe.getIngredients().getFirst().test(source));}
        return out;
    }
    static JsonObject observe(Minecraft mc)throws Exception{
        JsonObject out=new JsonObject();var runtime=RecipeProbePlugin.runtime;out.addProperty("runtimeAvailable",runtime!=null);out.add("lastInput",lastInput.deepCopy());
        if(runtime==null||mc.level==null)return out;
        out.addProperty("filter",runtime.getIngredientFilter().getFilterText());out.addProperty("overlayDisplayed",runtime.getIngredientListOverlay().isListDisplayed());
        JsonArray filtered=new JsonArray();for(ItemStack item:runtime.getIngredientFilter().getFilteredIngredients(VanillaTypes.ITEM_STACK))if(BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace().equals("muxi_console"))filtered.add(stack(item));out.add("filteredHardware",filtered);
        JsonArray visible=new JsonArray();for(ItemStack item:runtime.getIngredientListOverlay().getVisibleIngredients(VanillaTypes.ITEM_STACK))if(BuiltInRegistries.ITEM.getKey(item.getItem()).getNamespace().equals("muxi_console"))visible.add(stack(item));out.add("visibleHardware",visible);
        out.addProperty("recipesOpen",mc.screen instanceof RecipesGui);
        if(mc.screen instanceof RecipesGui){
            ILookupState state=(ILookupState)field(field(mc.screen,"logic"),"state");JsonArray recipes=new JsonArray();
            for(Object value:state.getFocusedRecipes().getRecipes()){JsonObject r=recipe(mc,value);if(r.has("id"))recipes.add(r);}out.add("focusedRecipes",recipes);
            out.addProperty("pageCount",state.pageCount());JsonArray rendered=new JsonArray();for(Object value:layouts(mc)){var layout=((IRecipeLayoutWithButtons<?>)value).getRecipeLayout();JsonObject row=recipe(mc,layout.getRecipe());var rect=layout.getRect();JsonArray bounds=new JsonArray();for(int n:new int[]{rect.getX(),rect.getY(),rect.getWidth(),rect.getHeight()})bounds.add(n);row.add("bounds",bounds);var button=layout.getRecipeTransferButtonArea();JsonArray b=new JsonArray();for(int n:new int[]{button.getX(),button.getY(),button.getWidth(),button.getHeight()})b.add(n);row.add("transferBounds",b);rendered.add(row);}out.add("visibleRecipes",rendered);
        }
        return out;
    }
    private RecipeActions(){}
}
