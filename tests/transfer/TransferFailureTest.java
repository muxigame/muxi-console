package net.muxigame.console.tests.transfer;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.util.concurrent.CompletionException;
import net.muxigame.console.client.transfer.TransferFailure;

/** Real FileSystemException's combined message must not swallow its separate OS reason. */
public final class TransferFailureTest {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    public static void main(String[] args){
        String reason="另一个程序正在使用此文件，进程无法访问。";
        FileSystemException sharing=new FileSystemException("C:\\Users\\private-player\\machine-profile\\WiiSD.raw",null,reason);
        StackTraceElement[] stack=new StackTraceElement[12];
        for(int i=0;i<10;i++)stack[i]=new StackTraceElement("sun.nio.fs.WindowsChannelFactory","newFileChannel","WindowsChannelFactory.java",i+1);
        stack[10]=new StackTraceElement("net.muxigame.console.client.transfer.NativeProfileArchive","pack","NativeProfileArchive.java",47);
        stack[11]=new StackTraceElement("net.muxigame.console.client.ConsoleClient","prepareCheckpoint","ConsoleClient.java",293);
        sharing.setStackTrace(stack);
        String detail=TransferFailure.diagnostic(new CompletionException(sharing));
        check(detail.contains("filesystemReason="+reason),"separate Windows sharing reason survives full path redaction");
        check(!detail.contains("private-player")&&!detail.contains("WiiSD.raw")&&!detail.contains("C:\\"),"actual private filename and username omitted");
        check(detail.contains("NativeProfileArchive.java:47")&&detail.contains("ConsoleClient.java:293"),"bounded product stack includes checkpoint failure line beyond Java I/O frames");
        check(detail.split(" \\| ").length==8,"four immediate and two product frames only");
        check(TransferFailure.filesystemReason(new IOException("wrapper",sharing)).equals(reason),"wrapped filesystem reason available independently");
        String player=TransferFailure.playerMessage(sharing,"saving");
        check(player.contains(reason)&&player.contains("saving_filesystemexception")&&!player.contains("WiiSD.raw"),"player sees OS reason without hidden local file");
        String secret=TransferFailure.diagnostic(new IOException("token=secret1 password:secret2 https://example.invalid/object?Signature=secret3 Credential=secret4"));
        check(!secret.contains("secret1")&&!secret.contains("secret2")&&!secret.contains("secret3")&&!secret.contains("secret4"),"URL and bare credential fields are redacted");
        check(TransferFailure.filesystemReason(new FileSystemException("private-file")).isEmpty(),"absent reason remains absent");
        String injection=TransferFailure.diagnostic(new FileSystemException("private-file",null,"locked\r\nforged-log\tentry"));
        check(!injection.contains("\r")&&!injection.contains("\n")&&!injection.contains("\t"),"OS reason cannot inject log records");
        check(TransferFailure.diagnostic(new FileSystemException("private-file",null,"x".repeat(20000))).length()<=4096,"diagnostic output stays bounded");
        System.out.println("TransferFailure checks="+checks+"; separate filesystem reason, bounded product stack and secret-free diagnostics");
    }
}
