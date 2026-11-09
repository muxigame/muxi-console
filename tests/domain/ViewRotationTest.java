package net.muxigame.console.tests.domain;

import net.muxigame.console.domain.ViewRotation;
import net.muxigame.console.domain.ViewRotation.Point;
import net.muxigame.console.domain.ViewRotation.Rect;

/** Asymmetric physical screen and touch examples; no Minecraft, core or image-buffer replacement. */
public final class ViewRotationTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static boolean near(double a,double b){return Math.abs(a-b)<1e-8;}
    private static void point(Point actual,double x,double y,String label){check(near(actual.x(),x)&&near(actual.y(),y),label);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Invalid geometry accepted");}catch(IllegalArgumentException expected){checks++;}}

    public static void main(String[] ignored){
        // Distinct dimensions and an off-centre point detect wrong direction, mirroring and mistaken dimension swaps.
        point(ViewRotation.forward(30,80,320,200,0),30,80,"zero keeps the physical point");
        point(ViewRotation.forward(30,80,320,200,1),120,30,"clockwise quarter turn");
        point(ViewRotation.forward(30,80,320,200,2),290,120,"upside-down view");
        point(ViewRotation.forward(30,80,320,200,3),80,290,"counterclockwise quarter turn");
        check(ViewRotation.normalize(-1)==3&&ViewRotation.normalize(5)==1&&ViewRotation.degrees(-1)==270,"quarter turns wrap consistently");

        for(int turn=0;turn<4;turn++){
            var canvas=ViewRotation.canvas(960,540,turn);
            check(canvas.width()==((turn&1)==0?960:540)&&canvas.height()==((turn&1)==0?540:960),"virtual canvas uses the complete actual viewport");
            Rect full=new Rect(0,0,canvas.width(),canvas.height());
            Rect displayed=ViewRotation.forward(full,canvas.width(),canvas.height(),turn);
            check(near(displayed.x0(),0)&&near(displayed.y0(),0)&&near(displayed.x1(),960)&&near(displayed.y1(),540),"rotated canvas maps exactly to the actual GUI");

            for(double[] sample:new double[][]{{0,0},{.5,.5},{123.25,77.5},{canvas.width(),canvas.height()},{-3,canvas.height()+8}}){
                Point screen=ViewRotation.forward(sample[0],sample[1],canvas.width(),canvas.height(),turn);
                point(ViewRotation.inverse(screen.x(),screen.y(),canvas.width(),canvas.height(),turn),sample[0],sample[1],"mouse and rendered pixel use opposite transforms");
            }

            // DS atlas: the touch screen remains the bottom source screen even when it appears left or right.
            Rect atlas=ViewRotation.fit(full,256.0/384,0);
            Rect bottom=new Rect(atlas.x0(),atlas.y0()+atlas.height()/2,atlas.x1(),atlas.y1());
            Rect top=new Rect(atlas.x0(),atlas.y0(),atlas.x1(),atlas.y0()+atlas.height()/2);
            for(double[] uv:new double[][]{{0,0},{1,1},{.2,.7},{.83,.11}}){
                double x=bottom.x0()+bottom.width()*uv[0],y=bottom.y0()+bottom.height()*uv[1];
                Point actual=ViewRotation.forward(x,y,canvas.width(),canvas.height(),turn);
                Point source=ViewRotation.inverse(actual.x(),actual.y(),canvas.width(),canvas.height(),turn);
                check(bottom.contains(source.x(),source.y()),"rotated touch returns to the original bottom screen");
                point(new Point((source.x()-bottom.x0())/bottom.width(),(source.y()-bottom.y0())/bottom.height()),uv[0],uv[1],"touch coordinates retain both axes and direction");
            }
            Point onTop=ViewRotation.forward(top.x0()+top.width()*.2,top.y0()+top.height()*.7,canvas.width(),canvas.height(),turn);
            Point originalTop=ViewRotation.inverse(onTop.x(),onTop.y(),canvas.width(),canvas.height(),turn);
            check(!bottom.contains(originalTop.x(),originalTop.y()),"non-touch upper screen cannot become a bottom-screen press");
            Point outside=ViewRotation.forward(bottom.x0()-5,bottom.y0()+5,canvas.width(),canvas.height(),turn);
            Point restoredOutside=ViewRotation.inverse(outside.x(),outside.y(),canvas.width(),canvas.height(),turn);
            check(!bottom.contains(restoredOutside.x(),restoredOutside.y()),"letterbox and shell area remain outside touch");

            // 3DS has an inset lower screen rather than two equal rectangles.
            Rect inset=new Rect(40,240,360,480);Point p=ViewRotation.forward(40+320*.2,240+240*.7,400,480,turn);
            Point original=ViewRotation.inverse(p.x(),p.y(),400,480,turn);
            point(new Point((original.x()-inset.x0())/inset.width(),(original.y()-inset.y0())/inset.height()),.2,.7,"inset 3DS lower screen retains its local touch origin");
            Rect inverse=ViewRotation.inverse(ViewRotation.forward(inset,400,480,turn),400,480,turn);
            check(inverse.equals(inset),"surface rectangle bounds round-trip through every quarter turn");

            Rect fitted=ViewRotation.fit(new Rect(11,17,971,557),256.0/384,turn);
            check(near(fitted.width()/fitted.height(),ViewRotation.aspect(256.0/384,turn)),"rotated image never stretches");
            check(near(fitted.width(),960)||near(fitted.height(),540),"complete image fills one viewport edge");
            check(near((fitted.x0()+fitted.x1())/2,491)&&near((fitted.y0()+fitted.y1())/2,287),"unused border is centred");
        }
        for(double invalid:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})reject(()->ViewRotation.fit(new Rect(0,0,10,20),invalid,1));
        reject(()->ViewRotation.canvas(0,100,1));reject(()->ViewRotation.forward(0,0,-2,3,0));reject(()->new Rect(0,0,0,3));
        System.out.println("ViewRotationTest: "+checks+" checks passed; GUI geometry and touch only");
    }
}
