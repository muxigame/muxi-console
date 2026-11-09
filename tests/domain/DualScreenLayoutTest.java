package net.muxigame.console.tests.domain;

import net.muxigame.console.domain.DualScreenLayout;
import net.muxigame.console.domain.ViewRotation;
import net.muxigame.console.domain.ViewRotation.Point;
import net.muxigame.console.domain.ViewRotation.Rect;

/** Separate screen proportions and asymmetric touch positions, independent of Minecraft and texture sampling. */
public final class DualScreenLayoutTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static boolean near(double a,double b){return Math.abs(a-b)<1e-8;}
    private static void point(Point value,double x,double y,String label){check(near(value.x(),x)&&near(value.y(),y),label);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Invalid layout accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static boolean inside(Rect inner,Rect outer){return inner.x0()>=outer.x0()-1e-8&&inner.y0()>=outer.y0()-1e-8&&inner.x1()<=outer.x1()+1e-8&&inner.y1()<=outer.y1()+1e-8;}

    public static void main(String[] ignored){
        var nds=DualScreenLayout.fit(new Rect(10,20,970,560),4.0/3,4.0/3,8);
        check(near(nds.first().x0(),10)&&near(nds.first().x1(),486)&&near(nds.second().x0(),494)&&near(nds.second().x1(),970),"NDS golden left/right positions use the full width without rotating either screen");
        check(near(nds.first().y0(),111.5)&&near(nds.first().y1(),468.5)&&nds.first().y0()==nds.second().y0(),"NDS golden 357-pixel screen height is centred");
        var tall=DualScreenLayout.fit(new Rect(5,7,1605,207),4.0/3,4.0/3,8);
        check(near(tall.first().height(),200)&&near(tall.content().width(),1600.0/3+8)&&near(tall.content().y0(),7),"wide viewport fits by height instead of stretching screens across it");
        var three=DualScreenLayout.fit(new Rect(0,0,720,480),5.0/3,4.0/3,12);
        check(near(three.first().height(),236)&&near(three.first().width()/three.second().width(),1.25)&&near(three.content().width(),720),"3DS golden layout keeps the upper screen wider at the same height");
        var joined=DualScreenLayout.fit(new Rect(0,0,960,540),4.0/3,4.0/3,0);
        check(near(joined.first().x1(),joined.second().x0()),"zero gap joins screen edges without overlap");

        for(double[] aspect:new double[][]{{4.0/3,4.0/3},{5.0/3,4.0/3}})for(int turn=0;turn<4;turn++){
            var canvas=ViewRotation.canvas(960,540,turn);Rect bounds=new Rect(3,3,canvas.width()-3,canvas.height()-3);
            var layout=DualScreenLayout.fit(bounds,aspect[0],aspect[1],6);
            check(inside(layout.first(),bounds)&&inside(layout.second(),bounds),"both screens fit in the virtual canvas before rotation");
            check(near(layout.first().width()/layout.first().height(),aspect[0])&&near(layout.second().width()/layout.second().height(),aspect[1]),"different original screen proportions survive layout");
            check(near(layout.first().height(),layout.second().height())&&near(layout.second().x0()-layout.first().x1(),6),"the screens have equal heights and a fixed clear gap");
            check(near(layout.content().width(),bounds.width())||near(layout.content().height(),bounds.height()),"the complete pair reaches an available edge");
            check(near((layout.content().x0()+layout.content().x1())/2,canvas.width()/2.0)&&near((layout.content().y0()+layout.content().y1())/2,canvas.height()/2.0),"the pair stays centred in either viewport orientation");
            Rect actualBounds=new Rect(0,0,960,540);
            for(Rect screen:new Rect[]{layout.first(),layout.second()}){
                Rect shown=ViewRotation.forward(screen,canvas.width(),canvas.height(),turn);
                check(inside(shown,actualBounds)&&near(shown.width()/shown.height(),ViewRotation.aspect(screen.width()/screen.height(),turn)),"optional whole-view rotation preserves fitted screen bounds and aspect");
            }
            Rect bottom=layout.second();
            for(double[] uv:new double[][]{{.2,.7},{.83,.11}}){
                Point shown=ViewRotation.forward(bottom.x0()+bottom.width()*uv[0],bottom.y0()+bottom.height()*uv[1],canvas.width(),canvas.height(),turn);
                Point source=ViewRotation.inverse(shown.x(),shown.y(),canvas.width(),canvas.height(),turn);
                check(bottom.contains(source.x(),source.y())&&!layout.first().contains(source.x(),source.y()),"right screen remains the original bottom touch surface");
                point(new Point((source.x()-bottom.x0())/bottom.width(),(source.y()-bottom.y0())/bottom.height()),uv[0],uv[1],"touch inverse keeps the original screen-local coordinates");
            }
            Point gap=ViewRotation.forward(layout.first().x1()+3,bottom.y0()+bottom.height()/2,canvas.width(),canvas.height(),turn);
            Point originalGap=ViewRotation.inverse(gap.x(),gap.y(),canvas.width(),canvas.height(),turn);
            check(!bottom.contains(originalGap.x(),originalGap.y())&&!layout.first().contains(originalGap.x(),originalGap.y()),"the gap cannot become a touch on either screen after rotation");
            Point top=ViewRotation.forward(layout.first().x0()+layout.first().width()*.2,layout.first().y0()+layout.first().height()*.7,canvas.width(),canvas.height(),turn);
            Point originalTop=ViewRotation.inverse(top.x(),top.y(),canvas.width(),canvas.height(),turn);
            check(!bottom.contains(originalTop.x(),originalTop.y()),"a top-screen click stays outside the touch surface");
        }
        Rect bounds=new Rect(0,0,100,100);
        for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}){
            reject(()->DualScreenLayout.fit(bounds,value,4.0/3,6));reject(()->DualScreenLayout.fit(bounds,4.0/3,value,6));
        }
        for(double gap:new double[]{-1,100,101,Double.NaN,Double.POSITIVE_INFINITY})reject(()->DualScreenLayout.fit(bounds,4.0/3,4.0/3,gap));
        reject(()->DualScreenLayout.fit(bounds,Double.MAX_VALUE,Double.MAX_VALUE,6));
        System.out.println("DualScreenLayoutTest: "+checks+" checks passed; dual-screen GUI geometry and touch only");
    }
}
