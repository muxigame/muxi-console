package net.muxigame.console.mc.recipe;

import net.minecraft.world.item.DyeColor;

/** Fixed existing CMDs; unused dyes are deliberately not promises of future hardware support. */
public final class HardwareRecoloring {
    public static int target(boolean handheld,DyeColor dye,boolean lens){
        if(dye==null)return 0;
        if(handheld){
            if(lens)return dye==DyeColor.RED?19:0;
            return switch(dye){
                case WHITE -> 3;case LIME -> 4;case PURPLE -> 5;case BLUE -> 9;
                case ORANGE -> 14;case GRAY -> 15;case YELLOW -> 16;case LIGHT_GRAY -> 17;case PINK -> 18;
                case BLACK -> 23;case CYAN -> 24;
                default -> 0;
            };
        }
        if(lens)return 0;
        return switch(dye){
            case RED -> 1;case LIGHT_GRAY -> 2;case BLACK -> 6;case BLUE -> 7;case GRAY -> 8;case WHITE -> 10;
            case PINK -> 11;case BROWN -> 12;case CYAN -> 13;case YELLOW -> 20;case ORANGE -> 21;case LIME -> 22;
            case LIGHT_BLUE -> 25;
            default -> 0;
        };
    }
    private HardwareRecoloring(){}
}
