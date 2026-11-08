package net.muxigame.consoleqa;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.muxigame.console.domain.Platform;
import net.muxigame.console.mc.CardData;
import net.muxigame.console.mc.ConsoleContent;
import net.muxigame.console.mc.HardwareCatalog;
import net.muxigame.console.mc.MachineData;

/** QA-only native GUI renders. Preview stacks never enter inventories or receive card/machine identities. */
final class IconReviewScreen extends Screen {
    private record Entry(String label,String description,ItemStack stack){}
    private final String page;
    private final int scale;
    private final List<Entry> entries;
    private long renderSequence;
    private int renderItemCalls;
    private JsonArray renderedEntries=new JsonArray();
    private static final int COLUMNS=7,TOP=38;

    IconReviewScreen(String page,int scale){
        super(Component.literal("Muxi native item icons"));
        if(!page.equals("hardware")&&!page.equals("media"))throw new IllegalArgumentException("Icon page must be hardware or media");
        if(scale!=2&&scale!=3)throw new IllegalArgumentException("Icon scale must be 2 or 3");
        this.page=page;this.scale=scale;entries=List.copyOf(previews(page));
        if(entries.size()>36)throw new IllegalStateException("An icon review page cannot exceed 36 entries");
        for(Entry entry:entries)if(!unissued(entry.stack))throw new IllegalStateException("Only standalone identity-free preview stacks may be rendered");
    }

    private static List<Entry> previews(String page){
        List<Entry> result=new ArrayList<>();
        if(page.equals("hardware")){
            result.add(new Entry("Desk 0","Generic desktop",new ItemStack(ConsoleContent.DESK_ITEM.get())));
            result.add(new Entry("Handheld 0","Generic handheld",new ItemStack(ConsoleContent.HANDHELD.get())));
            for(int id=1;id<=22;id++){
                var body=HardwareCatalog.body(id);if(body==null)throw new IllegalStateException("Missing authored hardware body "+id);
                ItemStack stack=HardwareCatalog.variant(new ItemStack(body.handheld()?ConsoleContent.HANDHELD.get():ConsoleContent.DESK_ITEM.get()),id);
                result.add(new Entry(String.format(Locale.ROOT,"%02d %s",id,body.platform().id().toUpperCase(Locale.ROOT)),body.name()+" / "+body.form(),stack));
            }
            result.add(new Entry("Writer","Cartridge writer",new ItemStack(ConsoleContent.WRITER_ITEM.get())));
            result.add(new Entry("Dock","Handheld dock",new ItemStack(ConsoleContent.DOCK_ITEM.get())));
            result.add(new Entry("Monitor","Computer monitor",new ItemStack(ConsoleContent.SCREEN_ITEM.get())));
            result.add(new Entry("Case","Thin cartridge case",new ItemStack(ConsoleContent.CASE_ITEM.get())));
        }else{
            for(Platform platform:Platform.values()){
                // Visual CMD only: no CardData.write/create, UUID, ROM, title or inventory insertion.
                ItemStack stack=new ItemStack(ConsoleContent.CARTRIDGE.get());
                stack.set(DataComponents.CUSTOM_MODEL_DATA,new CustomModelData(platform.customModelData()));
                result.add(new Entry(platform.id().toUpperCase(Locale.ROOT),"Unissued cartridge style "+platform.id(),stack));
            }
            result.add(new Entry("Blank","Blank cartridge",new ItemStack(ConsoleContent.BLANK.get())));
            result.add(new Entry("Controller","Controller",new ItemStack(ConsoleContent.CONTROLLER.get())));
        }
        return result;
    }

    private static boolean unissued(ItemStack stack){return stack.getCount()==1&&CardData.read(stack).isEmpty()&&MachineData.read(stack).isEmpty()&&!stack.has(DataComponents.CUSTOM_DATA);}
    private int cellHeight(){return 16*scale+20;}
    private int rows(){return (entries.size()+COLUMNS-1)/COLUMNS;}
    @Override protected void init(){
        if((width-24)/COLUMNS<18*scale+6||TOP+rows()*cellHeight()+17>height)
            throw new IllegalStateException("Native icon page does not fit this GUI surface; use scale 2 or a larger window/lower GUI scale");
    }
    @Override public boolean isPauseScreen(){return false;}

    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        graphics.fill(0,0,width,height,0xff202a30);
        graphics.drawCenteredString(font,"Native item icons / "+page+" / "+entries.size()+" entries",width/2,8,0xfff3f1e8);
        graphics.drawCenteredString(font,"16x16 source, "+scale+"x display. Bright frame = native slot boundary.",width/2,22,0xffb9d0cf);
        int cellWidth=(width-24)/COLUMNS,gridLeft=(width-cellWidth*COLUMNS)/2,size=16*scale;
        JsonArray drawn=new JsonArray();renderItemCalls=0;
        for(int i=0;i<entries.size();i++){
            Entry entry=entries.get(i);int x=gridLeft+(i%COLUMNS)*cellWidth+(cellWidth-size)/2,y=TOP+(i/COLUMNS)*cellHeight();
            graphics.pose().pushPose();
            try{
                graphics.pose().translate(x,y,0);graphics.pose().scale(scale,scale,1);
                graphics.fill(-1,-1,17,17,0xff728287);graphics.fill(0,0,16,16,0xff3c484c);
                // Deliberately no scissor or alternate model renderer: overflow must remain visible.
                graphics.renderItem(entry.stack,0,0);renderItemCalls++;
                graphics.pose().translate(0,0,400);graphics.renderOutline(-1,-1,18,18,0xff9acfd4);
            }finally{graphics.pose().popPose();}
            graphics.pose().pushPose();
            try{graphics.pose().translate(0,0,400);graphics.drawCenteredString(font,entry.label,x+size/2,y+size+5,0xfff3f1e8);}
            finally{graphics.pose().popPose();}
            JsonObject row=new JsonObject();row.addProperty("index",i);row.addProperty("label",entry.label);row.addProperty("description",entry.description);row.addProperty("item",BuiltInRegistries.ITEM.getKey(entry.stack.getItem()).toString());row.addProperty("customModelData",HardwareCatalog.model(entry.stack));row.addProperty("unissued",unissued(entry.stack));
            JsonArray bounds=new JsonArray();for(int value:new int[]{x,y,size,size})bounds.add(value);row.add("slotGui",bounds);drawn.add(row);
        }
        graphics.pose().pushPose();
        try{graphics.pose().translate(0,0,400);graphics.drawCenteredString(font,"Esc: close. Preview only; no inventory writes, ROMs or identities.",width/2,height-12,0xffb9d0cf);}
        finally{graphics.pose().popPose();}
        renderedEntries=drawn;renderSequence++;
    }

    JsonObject snapshot(){
        JsonObject out=new JsonObject();out.addProperty("open",true);out.addProperty("page",page);out.addProperty("scale",scale);out.addProperty("surfaceWidth",width);out.addProperty("surfaceHeight",height);out.addProperty("sourceIconWidth",16);out.addProperty("sourceIconHeight",16);out.addProperty("entryCount",entries.size());out.addProperty("renderSequence",renderSequence);out.addProperty("renderItemCalls",renderItemCalls);out.addProperty("inventoryWrites",false);out.addProperty("unissuedPreviewsOnly",entries.stream().allMatch(e->unissued(e.stack)));out.add("entries",renderedEntries.deepCopy());return out;
    }
}
