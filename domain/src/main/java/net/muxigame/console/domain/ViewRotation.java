package net.muxigame.console.domain;

/** Presentation-only clockwise quarter turns in GUI coordinates, where Y increases downwards. */
public final class ViewRotation {
    public record Point(double x,double y) {
        public Point { finite(x);finite(y); }
    }
    public record Rect(double x0,double y0,double x1,double y1) {
        public Rect {
            finite(x0);finite(y0);finite(x1);finite(y1);
            if(x1<=x0||y1<=y0)throw new IllegalArgumentException("Empty presentation rectangle");
        }
        public double width(){return x1-x0;}
        public double height(){return y1-y0;}
        public boolean contains(double x,double y){return x>=x0&&x<=x1&&y>=y0&&y<=y1;}
    }
    public record Canvas(int width,int height) {
        public Canvas { if(width<=0||height<=0)throw new IllegalArgumentException("Empty presentation canvas"); }
    }

    public static int normalize(int quarterTurns){return Math.floorMod(quarterTurns,4);}
    public static int degrees(int quarterTurns){return normalize(quarterTurns)*90;}
    public static boolean swapsAxes(int quarterTurns){return (normalize(quarterTurns)&1)!=0;}

    /** The existing renderer can lay out this canvas before rotating the whole result into the actual GUI. */
    public static Canvas canvas(int actualWidth,int actualHeight,int quarterTurns){
        return swapsAxes(quarterTurns)?new Canvas(actualHeight,actualWidth):new Canvas(actualWidth,actualHeight);
    }

    public static double aspect(double sourceAspect,int quarterTurns){
        if(!Double.isFinite(sourceAspect)||sourceAspect<=0)throw new IllegalArgumentException("Invalid presentation aspect");
        double value=swapsAxes(quarterTurns)?1/sourceAspect:sourceAspect;
        if(!Double.isFinite(value)||value<=0)throw new IllegalArgumentException("Invalid rotated aspect");
        return value;
    }

    /** The complete rotated image touches one side of bounds and keeps its original proportions. */
    public static Rect fit(Rect bounds,double sourceAspect,int quarterTurns){
        double ratio=aspect(sourceAspect,quarterTurns),w=bounds.width(),h=bounds.height();
        if(ratio>w/h){h=w/ratio;}else{w=h*ratio;}
        double x=bounds.x0()+(bounds.width()-w)/2,y=bounds.y0()+(bounds.height()-h)/2;
        return new Rect(x,y,x+w,y+h);
    }

    /** Maps an unrotated virtual-canvas point to actual GUI coordinates. */
    public static Point forward(double x,double y,double virtualWidth,double virtualHeight,int quarterTurns){
        dimensions(virtualWidth,virtualHeight);finite(x);finite(y);
        return switch(normalize(quarterTurns)){
            case 1->new Point(virtualHeight-y,x);
            case 2->new Point(virtualWidth-x,virtualHeight-y);
            case 3->new Point(y,virtualWidth-x);
            default->new Point(x,y);
        };
    }

    /** Maps actual mouse coordinates back before testing a source screen or normalizing touch coordinates. */
    public static Point inverse(double x,double y,double virtualWidth,double virtualHeight,int quarterTurns){
        dimensions(virtualWidth,virtualHeight);finite(x);finite(y);
        return switch(normalize(quarterTurns)){
            case 1->new Point(y,virtualHeight-x);
            case 2->new Point(virtualWidth-x,virtualHeight-y);
            case 3->new Point(virtualWidth-y,x);
            default->new Point(x,y);
        };
    }

    /** Axis-aligned GUI bounds of an entire source screen after a quarter turn. */
    public static Rect forward(Rect rectangle,double virtualWidth,double virtualHeight,int quarterTurns){
        Point a=forward(rectangle.x0(),rectangle.y0(),virtualWidth,virtualHeight,quarterTurns);
        Point b=forward(rectangle.x1(),rectangle.y1(),virtualWidth,virtualHeight,quarterTurns);
        return ordered(a,b);
    }

    public static Rect inverse(Rect rectangle,double virtualWidth,double virtualHeight,int quarterTurns){
        Point a=inverse(rectangle.x0(),rectangle.y0(),virtualWidth,virtualHeight,quarterTurns);
        Point b=inverse(rectangle.x1(),rectangle.y1(),virtualWidth,virtualHeight,quarterTurns);
        return ordered(a,b);
    }

    private static Rect ordered(Point a,Point b){return new Rect(Math.min(a.x(),b.x()),Math.min(a.y(),b.y()),Math.max(a.x(),b.x()),Math.max(a.y(),b.y()));}
    private static void dimensions(double width,double height){finite(width);finite(height);if(width<=0||height<=0)throw new IllegalArgumentException("Empty presentation canvas");}
    private static void finite(double value){if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite presentation coordinate");}
    private ViewRotation(){}
}
