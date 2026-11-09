package net.muxigame.console.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.client.input.InputProfiles;
import net.muxigame.console.client.input.NativeControls;
import net.muxigame.console.domain.Platform;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/** Edits the registered native mappings, grouped by hardware; no second keyboard configuration. */
public final class ConsoleBindingsScreen extends Screen {
    private enum Family { HOME, HANDHELD, ARCADE }
    private enum Section { DIRECTION, BUTTONS, SYSTEM, ANALOG, SENSORS }
    private static final Platform[][] FAMILIES={
        {Platform.NES,Platform.SNES,Platform.MD,Platform.N64,Platform.SMS,Platform.SG1000,Platform.PCE,Platform.A2600,Platform.A7800,Platform.FDS,Platform.WII},
        {Platform.GB,Platform.GBC,Platform.GBA,Platform.GG,Platform.LYNX,Platform.NGP,Platform.NGPC,Platform.WS,Platform.WSC,Platform.VB,Platform.NDS,Platform.N3DS},
        {Platform.ARCADE}
    };
    private static final int[][] GROUPS={{4,5,6,7},{0,8,1,9,10,11},{2,3,12,13,14,15},{16,17,18,19,20,21,22,23},{24,25,26,27,28,29,30}};
    private static final String[] PAD_NAMES={"A / Cross","B / Circle","X / Square","Y / Triangle","L1","R1","Back","Start","Guide","L3","R3","D-pad Up","D-pad Right","D-pad Down","D-pad Left","L2","R2"};
    private static Platform remembered=Platform.NES;
    private final Screen parent;
    private Platform platform=remembered;
    private Family family=family(platform);
    private Section section=Section.BUTTONS;
    private boolean gamepad;
    private int left,panelWidth,page,rows;
    private KeyMapping capturing;
    private InputConstants.Key modifierCandidate;
    private Component notice=Component.empty();
    private final List<Row> visibleRows=new ArrayList<>();
    private record Row(int index,int x,int y,int labelWidth){}
    public ConsoleBindingsScreen(Screen parent){super(Component.translatable("ui.muxi_console.bindings"));this.parent=parent;}
    private static Family family(Platform platform){for(Family family:Family.values())if(Arrays.asList(FAMILIES[family.ordinal()]).contains(platform))return family;throw new IllegalArgumentException("Platform");}
    private static Component text(String name){return Component.translatable("ui.muxi_console.bindings."+name);}
    private Component label(int index){if(index==31)return Component.translatable("key.muxi_console.link");return index<16?Component.literal(InputProfiles.buttonName(platform,index)):index<24?Component.translatable("key.muxi_console."+platform.id()+".axis."+(index-16)):Component.translatable("key.muxi_console."+platform.id()+".extra."+NativeControls.Extra.values()[index-24].name().toLowerCase(java.util.Locale.ROOT));}
    private KeyMapping mapping(int index){if(index==31)return net.muxigame.console.client.LinkKeyEvents.mapping();return index<16?NativeControls.key(platform,index):index<24?NativeControls.axis(platform,index-16):NativeControls.extra(platform,NativeControls.Extra.values()[index-24]);}
    private Button button(int x,int y,int w,Component title,Button.OnPress action){return addRenderableWidget(Button.builder(title,action).bounds(x,y,w,20).build());}
    private void change(){capturing=null;modifierCandidate=null;page=0;rebuildWidgets();}
    @Override protected void init(){
        NativeControls.clear();KeyMapping.releaseAll();visibleRows.clear();
        panelWidth=Math.min(440,width-20);left=(width-panelWidth)/2;
        for(Family f:Family.values()){
            var button=button(left+f.ordinal()*(panelWidth+3)/3,34,(panelWidth-6)/3,text("family."+f.name().toLowerCase(java.util.Locale.ROOT)),b->{family=f;platform=FAMILIES[f.ordinal()][0];remembered=platform;change();});
            button.active=f!=family;
        }
        int platformWidth=panelWidth/2;
        addRenderableWidget(CycleButton.<Platform>builder(value->Component.translatable("platform.muxi_console."+value.id()))
                .withValues(FAMILIES[family.ordinal()]).withInitialValue(platform)
                .create(left,60,platformWidth,20,Component.translatable("ui.muxi_console.platform"),(b,value)->{platform=value;remembered=value;change();}));
        int tabWidth=(panelWidth-platformWidth-8)/2;
        var keyboard=button(left+platformWidth+4,60,tabWidth,Component.translatable("ui.muxi_console.keyboard"),b->{gamepad=false;change();});keyboard.active=gamepad;
        var pad=button(left+platformWidth+8+tabWidth,60,tabWidth,Component.translatable("ui.muxi_console.gamepad"),b->{gamepad=true;change();});pad.active=!gamepad;
        int contentTop;
        if(gamepad){
            var profile=InputProfiles.get().profile(platform);var devices=InputProfiles.availableGamepads();
            if(profile.enabled()&&profile.gamepadGuid().isEmpty()){profile.setGamepad("*",-1);savePad();}
            List<String> choices=new ArrayList<>(List.of("","*"));for(var device:devices)if(!choices.contains(device.guid()))choices.add(device.guid());
            String selected=!profile.enabled()?"":profile.gamepadGuid().isEmpty()?"*":profile.gamepadGuid();if(!choices.contains(selected))choices.add(selected);
            addRenderableWidget(CycleButton.<String>builder(value->{if(value.isEmpty())return Component.translatable("options.off");if(value.equals("*"))return Component.translatable("options.muxi_console.automatic");return devices.stream().filter(device->device.guid().equals(value)).findFirst().map(device->Component.literal(device.name())).orElseGet(()->Component.translatable("options.muxi_console.disconnected"));})
                    .withValues(choices).withInitialValue(selected).create(left,86,panelWidth,20,Component.translatable("options.muxi_console.gamepad"),(b,value)->{InputProfiles.get().setEnabled(platform,!value.isEmpty());profile.setGamepad(value,-1);savePad();rebuildWidgets();}));
            contentTop=136;
        }else contentTop=112;
        Section[] sections=gamepad?new Section[]{Section.DIRECTION,Section.BUTTONS,Section.SYSTEM}:NativeControls.hasExtras(platform)?Section.values():new Section[]{Section.DIRECTION,Section.BUTTONS,Section.SYSTEM,Section.ANALOG};
        if(!Arrays.asList(sections).contains(section))section=Section.BUTTONS;
        int sectionY=gamepad?110:86;
        if(platform==Platform.WII){
            var profile=InputProfiles.get().profile(platform);
            addRenderableWidget(CycleButton.<Integer>builder(value->text("device."+value)).withValues(List.of(1,513,769,1025,1793,2305)).withInitialValue(profile.controllerDevice())
                    .create(left,sectionY,panelWidth,20,text("device"),(button,value)->{profile.setControllerDevice(value);savePad();}));
            sectionY+=24;contentTop+=24;
        }
        for(int i=0;i<sections.length;i++){
            Section value=sections[i];var button=button(left+i*(panelWidth+3)/sections.length,sectionY,(panelWidth-3*(sections.length-1))/sections.length,text("section."+value.name().toLowerCase(java.util.Locale.ROOT)),b->{section=value;change();});button.active=value!=section;
        }
        rows=Math.max(1,(height-contentTop-65)/22);int[] indices=GROUPS[section.ordinal()];if(!gamepad&&section==Section.SYSTEM){indices=Arrays.copyOf(indices,indices.length+1);indices[indices.length-1]=31;}int perPage=rows*2;int pages=(indices.length+perPage-1)/perPage;page=Math.min(page,pages-1);
        int columnWidth=(panelWidth-8)/2;
        for(int i=page*perPage;i<Math.min(indices.length,(page+1)*perPage);i++){
            int position=i-page*perPage,index=indices[i],x=left+(position%2)*(columnWidth+8),y=contentTop+(position/2)*22;
            int bindingX=x+columnWidth-112;visibleRows.add(new Row(index,x,y,columnWidth-116));
            if(gamepad){
                var profile=InputProfiles.get().profile(platform);List<Integer> values=new ArrayList<>();for(int value=-1;value<=16;value++)values.add(value);
                addRenderableWidget(CycleButton.<Integer>builder(value->value<0?Component.translatable("key.keyboard.unknown"):Component.literal(PAD_NAMES[value]))
                        .withValues(values).withInitialValue(profile.gamepadButton(index)).displayOnlyValue()
                        .create(bindingX,y,112,20,label(index),(b,value)->{InputProfiles.get().bindGamepad(platform,index,value);savePad();rebuildWidgets();})).setTooltip(Tooltip.create(label(index)));
            }else{
                KeyMapping mapping=mapping(index);
                Component key=capturing==mapping?Component.literal("> ").append(mapping.getTranslatedKeyMessage()).append(" <").withStyle(ChatFormatting.YELLOW):mapping.getTranslatedKeyMessage();
                if(capturing!=mapping&&conflict(mapping))key=key.copy().withStyle(ChatFormatting.RED);
                button(bindingX,y,76,key,b->{capturing=mapping;modifierCandidate=null;notice=text("capture");rebuildWidgets();}).setTooltip(Tooltip.create(label(index)));
                var reset=button(bindingX+80,y,32,Component.translatable("controls.reset"),b->{mapping.setKeyModifierAndCode(mapping.getDefaultKeyModifier(),mapping.getDefaultKey());saveKeys();rebuildWidgets();});reset.active=!mapping.isDefault();
            }
        }
        int footer=height-28;
        if(pages>1){button(left,footer,32,Component.literal("<"),b->{page=(page+pages-1)%pages;rebuildWidgets();});button(left+36,footer,32,Component.literal(">"),b->{page=(page+1)%pages;rebuildWidgets();});}
        button(left+panelWidth-150,footer,150,CommonComponents.GUI_DONE,b->onClose());
    }
    private static boolean shares(KeyMapping a,KeyMapping b){return net.muxigame.console.client.LinkKeyEvents.owns(a)||net.muxigame.console.client.LinkKeyEvents.owns(b)?a.getKey().equals(b.getKey())&&a.getKeyModifier()==b.getKeyModifier():a.same(b);}
    private boolean conflict(KeyMapping mapping){if(mapping.isUnbound())return false;for(int i=0;i<NativeControls.bindingCount(platform);i++){KeyMapping other=mapping(i);if(other!=mapping&&shares(mapping,other))return true;}KeyMapping link=net.muxigame.console.client.LinkKeyEvents.mapping();return mapping!=link&&shares(mapping,link);}
    private void saveKeys(){KeyMapping.resetMapping();minecraft.options.save();NativeControls.clear();}
    private void savePad(){try{InputProfiles.get().save();notice=Component.empty();}catch(IOException error){notice=text("save_failed");MuxiConsole.LOG.warn("Could not save console controller bindings",error);}}
    private void bind(InputConstants.Key key,KeyModifier modifier){
        if(key.getType()==InputConstants.Type.MOUSE&&key.getValue()==GLFW.GLFW_MOUSE_BUTTON_RIGHT){notice=text("reserved");return;}
        if(key.getType()==InputConstants.Type.MOUSE&&key.getValue()==GLFW.GLFW_MOUSE_BUTTON_LEFT&&NativeControls.hasExtras(platform)){notice=text("pointer_reserved");return;}
        capturing.setKeyModifierAndCode(modifier,key);capturing=null;modifierCandidate=null;saveKeys();notice=Component.empty();rebuildWidgets();
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(capturing==null)return super.keyPressed(key,scan,modifiers);
        if(key==GLFW.GLFW_KEY_ESCAPE){capturing=null;modifierCandidate=null;notice=Component.empty();rebuildWidgets();return true;}
        if(key==GLFW.GLFW_KEY_DELETE||key==GLFW.GLFW_KEY_BACKSPACE){bind(InputConstants.UNKNOWN,KeyModifier.NONE);return true;}
        InputConstants.Key input=InputConstants.getKey(key,scan);
        if(KeyModifier.isKeyCodeModifier(input)){modifierCandidate=input;return true;}
        KeyModifier modifier=(modifiers&GLFW.GLFW_MOD_CONTROL)!=0?KeyModifier.CONTROL:(modifiers&GLFW.GLFW_MOD_SHIFT)!=0?KeyModifier.SHIFT:(modifiers&GLFW.GLFW_MOD_ALT)!=0?KeyModifier.ALT:KeyModifier.NONE;
        bind(input,modifier);return true;
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){
        if(capturing!=null){var input=InputConstants.getKey(key,scan);if(input.equals(modifierCandidate))bind(input,KeyModifier.NONE);return true;}
        return super.keyReleased(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(capturing!=null){bind(InputConstants.Type.MOUSE.getOrCreate(button),hasControlDown()?KeyModifier.CONTROL:hasShiftDown()?KeyModifier.SHIFT:hasAltDown()?KeyModifier.ALT:KeyModifier.NONE);return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public void onClose(){capturing=null;saveKeys();savePad();minecraft.setScreen(parent);}
    @Override public void removed(){NativeControls.clear();KeyMapping.releaseAll();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        renderBackground(graphics,mouseX,mouseY,partial);
        // Finish the vanilla widget/background pass before queuing our unblurred labels.
        super.render(graphics,mouseX,mouseY,partial);graphics.flush();
        graphics.drawCenteredString(font,title,width/2,13,0xffffff);
        for(Row row:visibleRows)graphics.drawString(font,font.plainSubstrByWidth(label(row.index()).getString(),Math.max(1,row.labelWidth())),row.x(),row.y()+6,0xffffff,false);
        Component status=notice;if(gamepad&&status.getString().isBlank())status=text("gamepad_hint");
        if(!gamepad&&section==Section.SENSORS&&status.getString().isBlank())status=text("virtual_hint");
        if(!status.getString().isBlank())graphics.drawString(font,font.plainSubstrByWidth(status.getString(),panelWidth),left,height-43,0xe0e0e0,false);
        graphics.flush();
    }
}
