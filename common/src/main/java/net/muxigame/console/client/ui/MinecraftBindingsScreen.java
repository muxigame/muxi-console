package net.muxigame.console.client.ui;

import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.muxigame.console.client.input.NativeControls;

/** The ordinary Minecraft list, with only Console's own mappings moved to its platform page. */
public final class MinecraftBindingsScreen extends KeyBindsScreen {
    private KeyMapping[] visibleMappings;
    private Button resetVisible;
    public MinecraftBindingsScreen(Screen parent,Options options){super(parent,options);}
    @Override protected void addContents(){
        KeyMapping[] all=options.keyMappings;
        visibleMappings=Arrays.stream(all).filter(key->!NativeControls.isConsoleBinding(key)).toArray(KeyMapping[]::new);
        // The native list constructor only reads this array. Restore it before any event, input or save can run.
        try{options.keyMappings=visibleMappings;super.addContents();}
        finally{options.keyMappings=all;}
    }
    @Override protected void addFooter(){
        var footer=layout.addToFooter(LinearLayout.horizontal().spacing(8));
        resetVisible=footer.addChild(Button.builder(Component.translatable("controls.resetAll"),button->{
            for(KeyMapping mapping:visibleMappings)mapping.setKeyModifierAndCode(mapping.getDefaultKeyModifier(),mapping.getDefaultKey());
            KeyMapping.resetMapping();
            for(var child:children())if(child instanceof KeyBindsList list)list.refreshEntries();
        }).width(150).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE,button->onClose()).width(150).build());
    }
    @Override public void render(GuiGraphics graphics,int x,int y,float partial){
        if(resetVisible!=null)resetVisible.active=Arrays.stream(visibleMappings).anyMatch(mapping->!mapping.isDefault());
        // KeyBindsScreen.render also updates its private reset-all button; this page owns the visible-only button.
        for(var renderable:renderables)renderable.render(graphics,x,y,partial);
    }
}
