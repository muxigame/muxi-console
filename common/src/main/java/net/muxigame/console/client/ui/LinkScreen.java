package net.muxigame.console.client.ui;

import com.google.gson.*;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.muxigame.console.client.LinkPlayClient;

/** Explicit invitation consent for two physical handhelds. */
public final class LinkScreen extends Screen {
    private final LinkPlayClient link=LinkPlayClient.INSTANCE;
    public LinkScreen(){super(Component.literal("游戏机连线"));}
    @Override protected void init(){
        int x=width/2-145,y=58;JsonObject invitation=link.invitation();
        if(invitation!=null){
            boolean host=invitation.get("host").getAsBoolean();
            if(!host)addRenderableWidget(Button.builder(Component.literal("接受连线"),button->link.accept()).bounds(x,y,140,20).build());
            addRenderableWidget(Button.builder(Component.literal(host?"取消邀请":"拒绝"),button->link.decline()).bounds(x+150,y,140,20).build());
        }else if(link.active()||link.preparing()){
            if(link.active())addRenderableWidget(Button.builder(Component.literal("返回自己的游戏机"),button->link.show()).bounds(x,y,290,20).build());
            addRenderableWidget(Button.builder(Component.literal("保存并结束连线"),button->link.leave()).bounds(x,y+26,290,20).build());
        }else{
            int shown=0;
            for(JsonElement element:link.players()){
                if(y+shown*25>height-66)break;JsonObject peer=element.getAsJsonObject();String name=peer.get("name").getAsString(),platform=peer.get("platform").getAsString().toUpperCase(java.util.Locale.ROOT);boolean busy=peer.get("busy").getAsBoolean();
                Button button=Button.builder(Component.literal(name+" · "+platform+(busy?" · 忙碌":" · 邀请")),clicked->link.invite(UUID.fromString(peer.get("player").getAsString()))).bounds(x,y+shown*25,290,20).build();button.active=link.compatible()&&!busy;addRenderableWidget(button);shown++;
            }
            addRenderableWidget(Button.builder(Component.literal("刷新在线玩家"),button->link.open()).bounds(x,height-58,140,20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("返回"),button->onClose()).bounds(width/2+5,height-32,140,20).build());
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        renderBackground(graphics,mouseX,mouseY,partial);super.render(graphics,mouseX,mouseY,partial);graphics.drawCenteredString(font,title,width/2,16,0xffffffff);
        graphics.drawCenteredString(font,Component.literal("GB / GBC / GBA 串口 · NDS / 3DS 无线连接"),width/2,32,0xffbecbd0);
        graphics.drawCenteredString(font,Component.literal(link.status()),width/2,height-80,0xffd4e6e9);
        JsonObject invitation=link.invitation();if(invitation!=null){JsonArray endpoints=invitation.getAsJsonArray("endpoints");String names=endpoints.get(0).getAsJsonObject().get("name").getAsString()+" ↔ "+endpoints.get(1).getAsJsonObject().get("name").getAsString();graphics.drawCenteredString(font,Component.literal(names),width/2,88,0xffffffff);graphics.drawCenteredString(font,Component.literal("接受后会先保存双方单机进度，宿主临时读取两张卡带"),width/2,108,0xffbecbd0);}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){if(minecraft!=null)minecraft.setScreen(null);}
}
