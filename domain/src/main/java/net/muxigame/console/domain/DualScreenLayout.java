package net.muxigame.console.domain;

import java.util.Objects;
import net.muxigame.console.domain.ViewRotation.Rect;

/** Two upright source screens arranged left to right, before the optional whole-view rotation. */
public final class DualScreenLayout {
    public record Pair(Rect first,Rect second,Rect content) {}

    /** Both screens share a height, retain their individual aspect ratios and fill one available edge. */
    public static Pair fit(Rect bounds,double firstAspect,double secondAspect,double gap){
        Objects.requireNonNull(bounds);
        if(!positive(firstAspect)||!positive(secondAspect)||!Double.isFinite(gap)||gap<0||
                !positive(bounds.width())||!positive(bounds.height())||bounds.width()<=gap)
            throw new IllegalArgumentException("Invalid dual-screen layout");
        double combinedAspect=firstAspect+secondAspect;
        if(!positive(combinedAspect))throw new IllegalArgumentException("Invalid combined screen aspect");
        double height=Math.min(bounds.height(),(bounds.width()-gap)/combinedAspect);
        double firstWidth=height*firstAspect,secondWidth=height*secondAspect;
        double width=firstWidth+gap+secondWidth;
        if(!positive(height)||!positive(firstWidth)||!positive(secondWidth)||!positive(width))
            throw new IllegalArgumentException("Unrepresentable dual-screen layout");
        double x=bounds.x0()+(bounds.width()-width)/2,y=bounds.y0()+(bounds.height()-height)/2;
        Rect first=new Rect(x,y,x+firstWidth,y+height);
        Rect second=new Rect(first.x1()+gap,y,first.x1()+gap+secondWidth,y+height);
        return new Pair(first,second,new Rect(first.x0(),y,second.x1(),y+height));
    }

    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    private DualScreenLayout(){}
}
