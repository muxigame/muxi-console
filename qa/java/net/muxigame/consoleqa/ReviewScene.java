package net.muxigame.consoleqa;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.muxigame.console.compat.BrowserLinkServer;
import net.muxigame.console.compat.WebDisplaysBridge;
import net.muxigame.console.mc.*;

/** Isolated real blocks/entities and public WebDisplays fixture APIs; never creates ROM card data. */
final class ReviewScene {
    static final int DX=32, DY=26;
    static final String ORIGINAL_URL="about:blank#muxi-console-original";
    private final QaOwner owner;
    private final JsonArray stations;
    private boolean galleryBuilt;
    private BlockPos browserOrigin;
    private Object browserSide;
    private boolean browserFaceBuilt;
    private boolean sharedBuilt;
    ReviewScene(QaOwner owner) {
        this.owner=owner;
        try(var in=ReviewScene.class.getResourceAsStream("/diagnostics/model-review-plan.json")) {
            if(in==null)throw new IllegalStateException("Missing build-pinned review plan");
            stations=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("stations");
            if(stations.size()!=22)throw new IllegalStateException("Incomplete model plan");
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    JsonObject command(MinecraftServer server,JsonObject j){
        try{return switch(j.get("action").getAsString()){
            case "model_prepare" -> prepareGallery(server);
            case "model_camera" -> galleryCamera(server,j);
            case "browser_prepare_blocks" -> prepareBrowserBlocks(server);
            case "browser_create_face" -> createBrowserFace(server);
            case "shared_prepare" -> prepareShared(server);
            case "shared_position","shared_camera" -> {if(!sharedBuilt)throw new IllegalStateException("Prepare shared pads first");ServerPlayer p=player(server,j.get("role").getAsString());int x=j.get("platform").getAsString().equals("n64")?8:14;if(j.get("action").getAsString().equals("shared_camera"))teleportEye(p,x+2.2,92.0,25.6,x+.5,90.8,20.5);else p.connection.teleport(x+.5,90,23.5,180,16);yield sharedObserve(server);}
            case "browser_camera" -> {ServerPlayer p=player(server,j.get("role").getAsString());teleportEye(p,12.8,92.2,14.2,13.0,90.8,8.5);yield browserObserve(server);}
            case "hardware_prepare" -> {fresh(server);ServerPlayer p=player(server,"host");if(!server.overworld().getBlockState(new BlockPos(5,90,6)).isAir()||CardData.read(p.getOffhandItem()).isPresent())throw new IllegalStateException("Hardware placement pad/offhand already occupied");p.getInventory().setItem(40,ItemStack.EMPTY);p.getInventory().setItem(4,HardwareCatalog.variant(new ItemStack(ConsoleContent.DESK_ITEM.get()),2));ItemStack gba=HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),5);CompoundTag tag=new CompoundTag();tag.putString("qa_preserve","original-gba-shell");gba.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));p.getInventory().setItem(2,gba);p.getInventory().setItem(6,new ItemStack(Items.IRON_PICKAXE));p.inventoryMenu.broadcastChanges();yield hardwareObserve(server);}
            case "hardware_mode" -> {String mode=j.get("mode").getAsString();if(!mode.equals("survival")&&!mode.equals("creative"))throw new IllegalArgumentException("Fixture game modes only");player(server,"host").setGameMode(mode.equals("survival")?GameType.SURVIVAL:GameType.CREATIVE);yield hardwareObserve(server);}
            case "browser_owner" -> {entity(server).getClass().getMethod("setOwner",browserSide.getClass(),Player.class).invoke(entity(server),browserSide,player(server,j.get("owner").getAsString()));yield browserObserve(server);}
            case "browser_url" -> {String value=j.get("url").getAsString();if(!value.equals(ORIGINAL_URL)&&!value.equals("about:blank#muxi-console-navigated"))throw new IllegalArgumentException("Fixture URLs only");entity(server).getClass().getMethod("setScreenURL",browserSide.getClass(),String.class).invoke(entity(server),browserSide,value);yield browserObserve(server);}
            default -> throw new IllegalArgumentException("Unknown review action");
        };}catch(ReflectiveOperationException|java.io.IOException e){throw new IllegalStateException(e);}
    }
    private ServerPlayer player(MinecraftServer server,String role)throws java.io.IOException {
        ServerPlayer p=server.getPlayerList().getPlayerByName(owner.playerName(role));
        if(p==null)throw new IllegalStateException("Real runner player missing");return p;
    }
    private void fresh(MinecraftServer server)throws java.io.IOException{
        if(server.getPlayerList().getPlayerCount()!=2||server.overworld().getGameTime()>16000||!ConsoleServer.state(server).rooms.isEmpty())throw new IllegalStateException("Fresh isolated two-player world with no active session required");
    }
    private JsonObject prepareGallery(MinecraftServer server)throws java.io.IOException{
        if(galleryBuilt)throw new IllegalStateException("Gallery is already prepared; use this run's existing scene");fresh(server);
        var level=server.overworld();
        for(int x=29;x<=53;x++)for(int z=-6;z<=33;z++){
            level.setBlock(new BlockPos(x,89,z),Blocks.SMOOTH_QUARTZ.defaultBlockState(),3);
            for(int y=90;y<=96;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        for(JsonElement element:stations){JsonObject s=element.getAsJsonObject();int id=s.get("cmd").getAsInt();JsonArray a=s.getAsJsonArray("station");BlockPos pos=new BlockPos(a.get(0).getAsInt()+DX,a.get(1).getAsInt()+DY,a.get(2).getAsInt());
            if(level.getBlockEntity(pos)!=null)throw new IllegalStateException("Refusing to replace an existing gallery block");
            level.setBlock(pos,ConsoleContent.DESK.get().defaultBlockState().setValue(DeviceBlock.FACING,Direction.NORTH).setValue(DeviceBlock.MODEL,id),3);
            HardwareCatalog.Body body=HardwareCatalog.body(id);
            display(server,HardwareCatalog.variant(new ItemStack(body.handheld()?ConsoleContent.HANDHELD.get():ConsoleContent.DESK_ITEM.get()),id),pos.getX()-.75,pos.getY()+.45,pos.getZ()+.5);
            int media=s.getAsJsonObject("media_item").get("custom_model_data").getAsInt();
            display(server,HardwareCatalog.variant(new ItemStack(ConsoleContent.CARTRIDGE.get()),media),pos.getX()+1.55,pos.getY()+.35,pos.getZ()+.5);
            if(s.has("external_screen")&&!s.get("external_screen").isJsonNull())level.setBlock(pos.offset(0,0,2),ConsoleContent.SCREEN.get().defaultBlockState().setValue(DeviceBlock.FACING,Direction.NORTH),3);
        }
        daylight(server);galleryBuilt=true;
        JsonObject out=new JsonObject();out.addProperty("stations",22);out.addProperty("displayPropsHaveNoCardData",true);out.addProperty("planSha256",owner.pin("model-plan.sha256"));out.addProperty("galleryBuilt",true);return out;
    }
    private void display(MinecraftServer server,ItemStack item,double x,double y,double z){
        Display.ItemDisplay display=new Display.ItemDisplay(EntityType.ITEM_DISPLAY,server.overworld());
        CompoundTag data=new CompoundTag();data.put("item",item.save(server.registryAccess()));data.putString("item_display","fixed");display.load(data);display.moveTo(x,y,z,180,0);server.overworld().addFreshEntity(display);
    }
    private JsonObject galleryCamera(MinecraftServer server,JsonObject j)throws java.io.IOException{
        if(!galleryBuilt)throw new IllegalStateException("Prepare gallery first");ServerPlayer p=player(server,"host");int id=j.get("cmd").getAsInt();boolean held=j.has("held")&&j.get("held").getAsBoolean();String hand=j.has("hand")?j.get("hand").getAsString():"main";if(!hand.equals("main")&&!hand.equals("off"))throw new IllegalArgumentException("Main/off hand only");
        if(id==0){teleportEye(p,58,106,-15,40,91,13);}
        else{
            JsonObject s=null;for(JsonElement e:stations)if(e.getAsJsonObject().get("cmd").getAsInt()==id)s=e.getAsJsonObject();if(s==null)throw new IllegalArgumentException("Unknown station");
            JsonArray a=s.getAsJsonObject("camera").getAsJsonArray("position"),b=s.getAsJsonObject("camera").getAsJsonArray("target");
            if(CardData.read(p.getInventory().getItem(40)).isPresent()||held&&CardData.read(p.getInventory().getItem(7)).isPresent())throw new IllegalStateException("Model props cannot replace a real cartridge-bearing handheld");
            if(held){if(!HardwareCatalog.body(id).handheld())throw new IllegalArgumentException("Only portable hardware has a handheld shot");p.getInventory().setItem(7,hand.equals("main")?HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),id):ItemStack.EMPTY);p.getInventory().setItem(40,hand.equals("off")?HardwareCatalog.variant(new ItemStack(ConsoleContent.HANDHELD.get()),id):ItemStack.EMPTY);p.getInventory().selected=hand.equals("main")?7:8;p.inventoryMenu.broadcastChanges();}
            else{p.getInventory().setItem(40,ItemStack.EMPTY);p.getInventory().selected=8;p.inventoryMenu.broadcastChanges();}
            teleportEye(p,a.get(0).getAsDouble()+DX,a.get(1).getAsDouble()+DY,a.get(2).getAsDouble(),b.get(0).getAsDouble()+DX,b.get(1).getAsDouble()+DY,b.get(2).getAsDouble());
        }
        JsonObject out=new JsonObject();out.addProperty("cmd",id);out.addProperty("held",held);out.addProperty("hand",hand);out.addProperty("eyeX",p.getX());out.addProperty("eyeY",p.getEyeY());out.addProperty("eyeZ",p.getZ());out.addProperty("yaw",p.getYRot());out.addProperty("pitch",p.getXRot());return out;
    }
    static void teleportEye(ServerPlayer p,double x,double y,double z,double tx,double ty,double tz){
        double dx=tx-x,dy=ty-y,dz=tz-z;float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz)),pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        p.getAbilities().flying=true;p.onUpdateAbilities();p.setDeltaMovement(0,0,0);p.connection.teleport(x,y-p.getEyeHeight(),z,yaw,pitch);
    }
    private JsonObject prepareBrowserBlocks(MinecraftServer server)throws ReflectiveOperationException,java.io.IOException{
        if(browserOrigin!=null)throw new IllegalStateException("Browser fixture already exists");fresh(server);if(!WebDisplaysBridge.available())throw new IllegalStateException("Actual WebDisplays mod is required");
        var level=server.overworld();var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("webdisplays:screen"));if(block==Blocks.AIR)throw new IllegalStateException("Actual screen block missing");
        BlockPos source=new BlockPos(10,90,8);level.setBlock(source,level.getBlockState(source).setValue(DeviceBlock.MODEL,1),3);
        Class<?> side=Class.forName("net.montoyo.wd.utilities.data.BlockSide"),vector=Class.forName("net.montoyo.wd.utilities.math.Vector2i");browserSide=side.getField("SOUTH").get(null);Object right=side.getField("right").get(browserSide);int rx=right.getClass().getField("x").getInt(right);
        browserOrigin=new BlockPos(rx<0?16:14,90,8);
        for(int x=14;x<=16;x++)for(int y=90;y<=91;y++)level.setBlock(new BlockPos(x,y,8),block.defaultBlockState(),3);
        BooleanProperty hasTe=(BooleanProperty)block.getClass().getField("hasTE").get(null);level.setBlock(browserOrigin,block.defaultBlockState().setValue(hasTe,true),3);
        daylight(server);
        for(String role:new String[]{"host","guest"}){ServerPlayer p=player(server,role);p.getAbilities().flying=false;p.onUpdateAbilities();p.connection.teleport(role.equals("host")?12.5:13.5,90,12,180,16);}
        return browserObserve(server);
    }
    private JsonObject createBrowserFace(MinecraftServer server)throws ReflectiveOperationException,java.io.IOException{
        if(browserFaceBuilt)throw new IllegalStateException("Browser face already created");
        Class<?> side=browserSide.getClass(),vector=Class.forName("net.montoyo.wd.utilities.math.Vector2i");
        Object entity=entity(server);entity.getClass().getMethod("addScreen",side,vector,vector,Player.class,boolean.class).invoke(entity,browserSide,vector.getConstructor(int.class,int.class).newInstance(3,2),vector.getConstructor(int.class,int.class).newInstance(384,256),player(server,"host"),true);
        entity.getClass().getMethod("setRights",ServerPlayer.class,side,int.class,int.class).invoke(entity,player(server,"host"),browserSide,0,0);
        entity.getClass().getMethod("setScreenURL",side,String.class).invoke(entity,browserSide,ORIGINAL_URL);
        browserFaceBuilt=true;
        return browserObserve(server);
    }
    private Object entity(MinecraftServer server){if(browserOrigin==null)throw new IllegalStateException("Prepare browser first");Object result=server.overworld().getBlockEntity(browserOrigin);if(result==null)throw new IllegalStateException("Actual browser entity missing");return result;}
    JsonObject browserObserve(MinecraftServer server){
        JsonObject out=new JsonObject();out.addProperty("bindings",BrowserLinkServer.bindingCount(server));out.addProperty("prepared",browserOrigin!=null);out.addProperty("faceCreated",browserFaceBuilt);
        if(browserOrigin!=null){out.addProperty("position",browserOrigin.asLong());out.addProperty("x",browserOrigin.getX());out.addProperty("y",browserOrigin.getY());out.addProperty("z",browserOrigin.getZ());out.addProperty("side","SOUTH");
            if(browserFaceBuilt)try{var t=WebDisplaysBridge.at(server.overworld(),browserOrigin,"SOUTH").orElseThrow();out.addProperty("url",WebDisplaysBridge.url(t));out.addProperty("hostMayChange",WebDisplaysBridge.mayChange(t,player(server,"host").getUUID()));out.addProperty("guestMayChange",WebDisplaysBridge.mayChange(t,player(server,"guest").getUUID()));}catch(Exception e){out.addProperty("error",e.toString());}}
        return out;
    }
    private void daylight(MinecraftServer server){var level=server.overworld();level.setDayTime(6000);level.setWeatherParameters(120000,0,false,false);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);}
    private JsonObject prepareShared(MinecraftServer server)throws java.io.IOException{
        if(sharedBuilt)throw new IllegalStateException("Shared pads already prepared");fresh(server);var level=server.overworld();for(int x=4;x<=18;x++)for(int z=18;z<=28;z++){level.setBlock(new BlockPos(x,89,z),Blocks.SMOOTH_STONE.defaultBlockState(),3);for(int y=90;y<=94;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);}for(int x:new int[]{8,14})level.setBlock(new BlockPos(x,90,20),ConsoleContent.DESK.get().defaultBlockState().setValue(DeviceBlock.FACING,Direction.SOUTH).setValue(DeviceBlock.MODEL,x==8?7:22),3);level.setBlock(new BlockPos(10,90,20),ConsoleContent.SCREEN.get().defaultBlockState().setValue(DeviceBlock.FACING,Direction.SOUTH),3);ServerPlayer p=player(server,"host");p.getInventory().setItem(0,new ItemStack(ConsoleContent.BLANK.get(),4));p.getInventory().setItem(1,new ItemStack(ConsoleContent.CONTROLLER.get(),4));p.inventoryMenu.broadcastChanges();sharedBuilt=true;daylight(server);return sharedObserve(server);
    }
    JsonObject sharedObserve(MinecraftServer server){JsonObject out=new JsonObject();out.addProperty("prepared",sharedBuilt);JsonArray devices=new JsonArray();if(sharedBuilt)for(int x:new int[]{8,10,14})if(server.overworld().getBlockEntity(new BlockPos(x,90,20)) instanceof DeviceEntity device)devices.add(ConsoleServer.view(device));out.add("devices",devices);return out;}
    JsonObject adopt(MinecraftServer server)throws java.io.IOException{
        for(int x:new int[]{8,10,14})if(!(server.overworld().getBlockEntity(new BlockPos(x,90,20)) instanceof DeviceEntity))throw new IllegalStateException("Copied shared fixture missing");sharedBuilt=true;
        browserOrigin=new BlockPos(14,90,8);try{Class<?> side=Class.forName("net.montoyo.wd.utilities.data.BlockSide");browserSide=side.getField("SOUTH").get(null);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}if(WebDisplaysBridge.at(server.overworld(),browserOrigin,"SOUTH").isEmpty())throw new IllegalStateException("Copied real WebDisplays face missing");browserFaceBuilt=true;
        JsonObject out=new JsonObject();out.addProperty("adoptedCopiedFixture",true);out.addProperty("cartridgesMintedByQa",0);out.addProperty("inventoryReplaced",false);out.add("browser",browserObserve(server));out.add("shared",sharedObserve(server));return out;
    }
    JsonObject hardwareObserve(MinecraftServer server){
        JsonObject out=new JsonObject();BlockPos p=new BlockPos(5,90,6);var state=server.overworld().getBlockState(p);out.addProperty("block",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());out.addProperty("model",state.hasProperty(DeviceBlock.MODEL)?state.getValue(DeviceBlock.MODEL):-1);
        if(server.overworld().getBlockEntity(p) instanceof DeviceEntity device)out.add("device",ConsoleServer.view(device));
        return out;
    }
}
