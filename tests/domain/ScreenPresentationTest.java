package net.muxigame.console.tests.domain;

import net.muxigame.console.domain.ScreenPresentation;
import net.muxigame.console.domain.ScreenPresentation.Region;
import net.muxigame.console.domain.VideoOrientation.Image;
import net.muxigame.console.domain.ViewRotation;

/** World screens and relays receive exactly the orientation and dual-screen arrangement of the game window. */
public final class ScreenPresentationTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static Image image(int w,int h){byte[] p=new byte[w*h*4];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int i=(y*w+x)*4;p[i]=(byte)x;p[i+1]=(byte)y;p[i+2]=7;p[i+3]=(byte)255;}return new Image(w,h,p);}
    private static int[] at(Image m,int x,int y){int i=(y*m.width()+x)*4;return new int[]{m.rgba()[i]&255,m.rgba()[i+1]&255,m.rgba()[i+2]&255,m.rgba()[i+3]&255};}

    public static void main(String[] ignored){
        Image src=image(4,3);
        check(ScreenPresentation.compose(src,null,null,false,0)==src,"default presentation keeps the original atlas");
        check(!ScreenPresentation.changes(0,true,null,null)&&ScreenPresentation.changes(1,false,null,null),"single-screen games only change when rotated");
        for(int turn=0;turn<4;turn++){
            Image out=ScreenPresentation.compose(src,null,null,false,turn);
            check(out.width()==(turn%2==0?4:3)&&out.height()==(turn%2==0?3:4),"quarter turns swap dimensions");
            for(int y=0;y<3;y++)for(int x=0;x<4;x++){
                var p=ViewRotation.forward(x+.5,y+.5,4,3,turn);int[] v=at(out,(int)Math.floor(p.x()),(int)Math.floor(p.y()));
                check(v[0]==x&&v[1]==y,"every pixel lands where the game window's clockwise rotation draws it");
            }
        }
        // NDS-style stacked atlas: upper 8x6 above lower 8x6.
        Image ds=image(8,12);Region top=new Region(0,0,8,6),bottom=new Region(0,6,8,6);
        check(ScreenPresentation.changes(0,true,top,bottom),"two screens side by side is a change");
        Image wide=ScreenPresentation.compose(ds,top,bottom,true,0);
        check(wide.height()==6&&wide.width()==17,"left/right pair keeps each screen's size with a one-pixel gap");
        check(at(wide,0,0)[1]==0&&at(wide,9,0)[1]==6&&at(wide,16,5)[1]==11,"upper screen on the left, lower screen on the right");
        check(at(wide,8,3)[0]==0&&at(wide,8,3)[1]==0&&at(wide,8,3)[2]==0&&at(wide,8,3)[3]==255,"gap is opaque black");
        Image turned=ScreenPresentation.compose(ds,top,bottom,true,1);
        check(turned.width()==6&&turned.height()==17&&at(turned,5,0)[1]==0,"layout happens before the whole-view rotation");
        // 3DS: different widths share one height.
        Image n3ds=image(10,12);Image three=ScreenPresentation.compose(n3ds,new Region(0,0,10,6),new Region(1,6,8,6),true,0);
        check(three.width()==10+1+8&&three.height()==6,"different screen widths keep their proportions");
        try{ScreenPresentation.compose(ds,new Region(0,0,9,6),bottom,true,0);check(false,"outside region accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("ScreenPresentationTest checks="+checks);
    }
}
