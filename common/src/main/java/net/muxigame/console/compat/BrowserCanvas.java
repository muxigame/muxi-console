package net.muxigame.console.compat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** First-party, inert, memory-only browser document. No HTTP, WebSocket, files, ROM or input bridge. */
public final class BrowserCanvas {
    private final String marker="mx"+UUID.randomUUID().toString().replace("-","");
    private final String url;
    public BrowserCanvas(){
        String html="<!doctype html><meta charset=utf-8><meta http-equiv=Content-Security-Policy content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'\"><title>PlayStation</title><style>html,body{margin:0;width:100%;height:100%;background:#091b20;overflow:hidden}canvas{display:block;width:100%;height:100%;object-fit:contain;image-rendering:pixelated}</style><canvas id="+marker+" width=320 height=240></canvas>";
        url="data:text/html;base64,"+Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
    }
    public String url(){return url;}
    public String script(long sequence,byte[] png){
        if(sequence<0||png==null||png.length>BrowserFrameCodec.MAX_BYTES)throw new IllegalArgumentException("Invalid browser frame");
        String payload=Base64.getEncoder().encodeToString(png);
        // Everything interpolated is generated here: a hex identifier, an unsigned sequence and Base64.
        return "(()=>{const c=document.getElementById('"+marker+"');if(!c||location.href!=='"+url+"'||(c.dataset.frame&&BigInt(c.dataset.frame)>=BigInt('"+sequence+"')))return;const i=new Image();i.onload=()=>{if(location.href!=='"+url+"'||(c.dataset.frame&&BigInt(c.dataset.frame)>=BigInt('"+sequence+"')))return;c.width=i.width;c.height=i.height;c.getContext('2d',{alpha:false}).drawImage(i,0,0);c.dataset.frame='"+sequence+"';};i.src='data:image/png;base64,"+payload+"';})()";
    }
}
