package net.muxigame.console.client.transfer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.nio.file.FileSystemException;
import net.muxigame.console.domain.DomainException;

/** Stable player-facing stages and bounded, path-free diagnostics for asynchronous failures. */
public final class TransferFailure {
    private TransferFailure(){}
    public static Throwable unwrap(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        while((failure instanceof ExecutionException||failure instanceof CompletionException)&&failure.getCause()!=null&&seen.add(failure))failure=failure.getCause();
        return failure;
    }
    private static boolean timeout(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&seen.add(current);current=current.getCause())if(current instanceof TimeoutException)return true;
        return false;
    }
    public static String code(Throwable failure,String stage){Throwable cause=unwrap(failure);return timeout(failure)?"rom_timeout":stage+"_"+(cause instanceof DomainException domain?domain.code():cause.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT));}
    public static String playerMessage(Throwable failure,String stage){
        Throwable cause=unwrap(failure);String text=cause.getMessage();
        if(timeout(failure))return "等待服务器传输超时；已保留已下载部分，重新拿起可继续 [rom_timeout]";
        if(cause instanceof FileSystemException&&stage.equals("saving")){String reason=filesystemReason(cause);return "存档文件无法访问"+(reason.isEmpty()?"":"："+reason)+" ["+code(failure,stage)+"]";}
        if(text!=null&&text.codePoints().anyMatch(c->c>=0x3400&&c<=0x9fff))return safe(text);
        String action=switch(stage){case "downloading"->"游戏文件传输中断，重新拿起可继续";case "cache_check","verifying"->"游戏文件校验失败，请重新获取原文件";case "starting"->"游戏核心启动失败，请核对所选机型与游戏文件";case "restoring"->"读取存档失败，原存档保持不变";default->"游戏运行失败，请重新拿起或联系服主";};
        return action+" ["+code(failure,stage)+"]";
    }
    private static String safe(String text){
        String scrubbed=text.replaceAll("[\\r\\n\\t\\p{Cntrl}]"," ").replaceAll("(?i)https?://[^\\s]+","<remote>")
                .replaceAll("(?i)(token|secret|password|authorization|signature|credential)(\\s*[=:]\\s*)[^\\s,;]+","$1$2<redacted>")
                .replaceAll("[A-Za-z]:[\\\\/][^\\r\\n;]*","<local-file>")
                .replaceAll("(?<![A-Za-z0-9])/(?:[^\\s/:]+/)+[^\\s:]*","<local-file>");
        return scrubbed.substring(0,Math.min(scrubbed.length(),512));
    }
    /** Read the reason separately: FileSystemException#getMessage also embeds private paths. */
    public static String filesystemReason(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&seen.add(current);current=current.getCause())
            if(current instanceof FileSystemException filesystem&&filesystem.getReason()!=null)return safe(filesystem.getReason());
        return "";
    }
    public static String diagnostic(Throwable failure){
        Throwable cause=unwrap(failure);StringBuilder result=new StringBuilder(cause.getClass().getName()).append(": ").append(safe(String.valueOf(cause.getMessage())));
        String reason=filesystemReason(cause);if(!reason.isEmpty())result.append(" | filesystemReason=").append(reason);
        StackTraceElement[] stack=cause.getStackTrace();for(int i=0;i<Math.min(4,stack.length);i++)result.append(" | ").append(safe(stack[i].toString()));
        int added=0;for(int i=4;i<stack.length&&added<2;i++)if(stack[i].getClassName().startsWith("net.muxigame.console.")){result.append(" | ").append(safe(stack[i].toString()));added++;}
        return result.substring(0,Math.min(result.length(),4096));
    }
}
