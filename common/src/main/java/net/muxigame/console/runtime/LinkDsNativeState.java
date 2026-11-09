package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Exact source-built frontend extension; Console state and Java transport state are captured separately. */
final class LinkDsNativeState {
    public interface Api extends Library{
        int muxi_ds_link_abi();long muxi_ds_mp_save(byte[] output,long capacity);int muxi_ds_mp_restore(byte[] state,long size);
    }
    private final Api api;
    LinkDsNativeState(Path library)throws IOException{try{api=Native.load(library.toAbsolutePath().toString(),Api.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));if(api.muxi_ds_link_abi()!=1)throw new IOException("Unsupported DS MP checkpoint ABI");}catch(LinkageError error){throw new IOException("DS frontend lacks the required MP checkpoint extension",error);}}
    byte[] save()throws IOException{long size=api.muxi_ds_mp_save(null,0);if(size<18||size>16*1024*1024)throw new IOException("DS frontend MP queues exceed the checkpoint bound");byte[] bytes=new byte[(int)size];if(api.muxi_ds_mp_save(bytes,size)!=size)throw new IOException("DS MP checkpoint size changed");return bytes;}
    void restore(byte[] bytes)throws IOException{if(bytes==null||bytes.length<18||bytes.length>16*1024*1024||api.muxi_ds_mp_restore(bytes,bytes.length)!=1)throw new IOException("DS frontend rejected the MP queue checkpoint");}
}
