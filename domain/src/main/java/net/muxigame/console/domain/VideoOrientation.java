package net.muxigame.console.domain;

/** Frontend interpretation of the public libretro counterclockwise quarter-turn convention. */
public final class VideoOrientation {
    public record Image(int width,int height,byte[] rgba){}
    public static Image apply(int width,int height,byte[] rgba,int turns){
        if(width<0||height<0||turns<0||turns>3||(long)width*height*4!=rgba.length)throw new IllegalArgumentException("Invalid RGBA image");
        if(turns==0)return new Image(width,height,rgba);
        int targetWidth=(turns&1)==0?width:height,targetHeight=(turns&1)==0?height:width;byte[] output=new byte[rgba.length];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int tx,ty;if(turns==1){tx=y;ty=width-1-x;}else if(turns==2){tx=width-1-x;ty=height-1-y;}else{tx=height-1-y;ty=x;}
            System.arraycopy(rgba,(y*width+x)*4,output,(ty*targetWidth+tx)*4,4);
        }return new Image(targetWidth,targetHeight,output);
    }
    private VideoOrientation(){}
}
