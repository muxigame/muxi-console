package net.muxigame.console.runtime;

import java.util.List;

/** Packed RGBA8, top row first; PCM16 stereo pairs. Display aspect precedes the requested quarter-turn rotation. */
public record AvFrame(long number, int width, int height, int rotation, byte[] rgba,
                      short[] stereoPcm, double sampleRate, boolean shutdown, double displayAspect,List<Surface> surfaces) {
    public record Surface(String id,int x,int y,int width,int height,int nativeWidth,int nativeHeight,boolean touchable){
        public Surface{if(!List.of("primary","top","bottom").contains(id)||x<0||y<0||width<1||height<1||nativeWidth<1||nativeHeight<1||width>8192||height>8192||nativeWidth>8192||nativeHeight>8192)throw new IllegalArgumentException("Invalid video surface");}
    }
    public AvFrame {
        if (width < 0 || height < 0 || rotation < 0 || rotation > 3 ||
                (long) width * height * 4 != rgba.length || (stereoPcm.length & 1) != 0)
            throw new IllegalArgumentException("Invalid audio/video frame");
        displayAspect = resolveAspect(displayAspect, width, height);
        surfaces=List.copyOf(surfaces);if(surfaces.size()>3||surfaces.stream().map(Surface::id).distinct().count()!=surfaces.size())throw new IllegalArgumentException("Invalid surface set");
        for(Surface surface:surfaces)if((long)surface.x()+surface.width()>width||(long)surface.y()+surface.height()>height)throw new IllegalArgumentException("Surface outside frame");
    }
    public AvFrame(long number,int width,int height,int rotation,byte[] rgba,short[] stereoPcm,double sampleRate,boolean shutdown,double displayAspect){this(number,width,height,rotation,rgba,stereoPcm,sampleRate,shutdown,displayAspect,width>0&&height>0?List.of(new Surface("primary",0,0,width,height,width,height,false)):List.of());}
    /** Source/binary compatibility for callers whose images have square pixels. */
    public AvFrame(long number, int width, int height, int rotation, byte[] rgba,
                   short[] stereoPcm, double sampleRate, boolean shutdown) {
        this(number, width, height, rotation, rgba, stereoPcm, sampleRate, shutdown, 0);
    }
    static double resolveAspect(double aspect, int width, int height) {
        if (!Double.isFinite(aspect)) throw new IllegalArgumentException("Invalid display aspect");
        return aspect > 0 ? aspect : width > 0 && height > 0 ? (double) width / height : 1.0;
    }
}
