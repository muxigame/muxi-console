package net.muxigame.console.domain;

/**
 * Pixel copy of the player's chosen presentation for world screens and relays:
 * optionally the upper/lower screens left to right, then the whole view turned clockwise.
 * Input is the square-pixel display atlas the game window already shows.
 */
public final class ScreenPresentation {
    public record Region(int x,int y,int width,int height) {
        public Region { if(x<0||y<0||width<1||height<1)throw new IllegalArgumentException("Invalid screen region"); }
    }
    private static final long MAX_PIXELS=4_194_304;

    /** True when the chosen presentation differs from the plain atlas. */
    public static boolean changes(int quarterTurns,boolean sideBySide,Region first,Region second){
        return ViewRotation.normalize(quarterTurns)!=0||sideBySide&&first!=null&&second!=null;
    }

    public static VideoOrientation.Image compose(VideoOrientation.Image atlas,Region first,Region second,boolean sideBySide,int quarterTurns){
        int w=atlas.width(),h=atlas.height();
        if(w<1||h<1||(long)w*h*4!=atlas.rgba().length)throw new IllegalArgumentException("RGBA image");
        VideoOrientation.Image laid=sideBySide&&first!=null&&second!=null?sideBySide(atlas,first,second):atlas;
        return rotate(laid,quarterTurns);
    }

    /** Both screens keep their proportions at the taller screen's height, with a small black gap. */
    static VideoOrientation.Image sideBySide(VideoOrientation.Image atlas,Region first,Region second){
        inside(atlas,first);inside(atlas,second);
        int height=Math.max(first.height(),second.height());
        int firstWidth=scaledWidth(first,height),secondWidth=scaledWidth(second,height),gap=Math.max(1,Math.round(height*.03f));
        int width=Math.addExact(Math.addExact(firstWidth,gap),secondWidth);
        if((long)width*height>MAX_PIXELS)throw new IllegalArgumentException("Presentation too large");
        byte[] out=new byte[width*height*4];
        for(int i=3;i<out.length;i+=4)out[i]=(byte)255;
        copyScaled(atlas,first,out,width,0,firstWidth,height);
        copyScaled(atlas,second,out,width,firstWidth+gap,secondWidth,height);
        return new VideoOrientation.Image(width,height,out);
    }

    /** Clockwise quarter turns, matching the game window's rotation button. */
    static VideoOrientation.Image rotate(VideoOrientation.Image image,int quarterTurns){
        int q=ViewRotation.normalize(quarterTurns);if(q==0)return image;
        int w=image.width(),h=image.height(),ow=(q&1)==0?w:h,oh=(q&1)==0?h:w;
        byte[] in=image.rgba(),out=new byte[in.length];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int nx,ny;
            switch(q){case 1->{nx=h-1-y;ny=x;}case 2->{nx=w-1-x;ny=h-1-y;}default->{nx=y;ny=w-1-x;}}
            System.arraycopy(in,(y*w+x)*4,out,(ny*ow+nx)*4,4);
        }
        return new VideoOrientation.Image(ow,oh,out);
    }

    private static int scaledWidth(Region r,int height){return Math.max(1,(int)Math.round(r.width()*(double)height/r.height()));}
    private static void inside(VideoOrientation.Image atlas,Region r){if((long)r.x()+r.width()>atlas.width()||(long)r.y()+r.height()>atlas.height())throw new IllegalArgumentException("Screen region outside image");}
    private static void copyScaled(VideoOrientation.Image atlas,Region r,byte[] out,int outWidth,int left,int width,int height){
        byte[] in=atlas.rgba();int aw=atlas.width();
        for(int y=0;y<height;y++){int sy=r.y()+Math.min(r.height()-1,(int)((y+.5)*r.height()/height));
            for(int x=0;x<width;x++){int sx=r.x()+Math.min(r.width()-1,(int)((x+.5)*r.width()/width));System.arraycopy(in,(sy*aw+sx)*4,out,(y*outWidth+left+x)*4,4);}}
    }
    private ScreenPresentation(){}
}
