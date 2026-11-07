package net.muxigame.console.domain;

/** Converts non-square hardware pixels to their declared display shape without cropping the source image. */
public final class VideoLayout {
    private static final long MAX_PIXELS=4_194_304;
    public static VideoOrientation.Image display(VideoOrientation.Image image,double aspect){
        if(image.width()<0||image.height()<0||(long)image.width()*image.height()*4!=image.rgba().length)throw new IllegalArgumentException("RGBA image");
        if(image.width()==0||image.height()==0)return image;
        if(!Double.isFinite(aspect)||aspect<=0||aspect>64)aspect=image.width()/(double)image.height();
        int height=image.height(),width=Math.max(1,(int)Math.round(height*aspect));
        double scale=Math.min(1,Math.min(4096.0/Math.max(width,height),Math.sqrt(MAX_PIXELS/((double)width*height))));
        width=Math.max(1,(int)Math.floor(width*scale));height=Math.max(1,(int)Math.floor(height*scale));
        if(width==image.width()&&height==image.height())return image;
        byte[] input=image.rgba(),output=new byte[Math.multiplyExact(Math.multiplyExact(width,height),4)];
        for(int y=0;y<height;y++){int sy=Math.min(image.height()-1,(int)(((y+.5)*image.height())/height));for(int x=0;x<width;x++){int sx=Math.min(image.width()-1,(int)(((x+.5)*image.width())/width));System.arraycopy(input,(sy*image.width()+sx)*4,output,(y*width+x)*4,4);}}
        return new VideoOrientation.Image(width,height,output);
    }
    /** Centers the complete image in the authored screen aperture; the remainder is letterboxed. */
    public static float[] fit(float x0,float y0,float x1,float y1,double aspect){
        if(!Double.isFinite(aspect)||aspect<=0)throw new IllegalArgumentException("Display aspect");
        float width=x1-x0,height=y1-y0;if(width<=0||height<=0)throw new IllegalArgumentException("Screen aperture");
        if(aspect>width/(double)height){float fitted=(float)(width/aspect);y0+=(height-fitted)/2;y1=y0+fitted;}else{float fitted=(float)(height*aspect);x0+=(width-fitted)/2;x1=x0+fitted;}return new float[]{x0,y0,x1,y1};
    }
    private VideoLayout(){}
}
