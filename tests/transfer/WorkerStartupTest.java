package net.muxigame.console.tests.transfer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import javax.tools.ToolProvider;
import net.muxigame.console.runtime.*;

/** A real Java child dies before its socket handshake. No emulator, Minecraft or native code. */
public final class WorkerStartupTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path base=Path.of(args[0]).resolve("worker-startup");Files.createDirectories(base);Path root=Files.createTempDirectory(base,"case-").toAbsolutePath();Path classes=root.resolve("fixture"),source=root.resolve("src/net/muxigame/console/runtime/WorkerMain.java");Files.createDirectories(source.getParent());Files.createDirectories(classes);
        String fixture="""
            package net.muxigame.console.runtime;
            public final class WorkerMain {
                public static void main(String[] args) {
                    System.err.println("FIXTURE_PRE_CONNECT_FAILURE");
                    System.err.println("C:/Users/private-player/private-rom.gba");
                    System.err.println("https://example.invalid/object?Signature=private-signature");
                    System.err.println(args[1]);
                    for(int i=0;i<256;i++)System.err.print("x".repeat(4096));
                    System.err.flush();System.exit(23);
                }
            }
            """;
        Files.writeString(source,fixture,StandardCharsets.UTF_8);
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-encoding","UTF-8","-d",classes.toString(),source.toString())==0,"compile an isolated real child fixture");
        Path core=root.resolve("fixture.dll");Files.write(core,new byte[]{0});String javaName=System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java";Path java=Path.of(System.getProperty("java.home"),"bin",javaName);
        long started=System.nanoTime();String message;
        try{ProcessConsoleRuntime.open(new LaunchConfig(java,List.of(classes),core,null,root.resolve("system"),root.resolve("save")));throw new AssertionError("Early child exit was accepted");}catch(IOException expected){message=expected.getMessage();}
        check((System.nanoTime()-started)<10_000_000_000L,"early exit is detected before the old 15 second accept timeout");
        check(message.contains("exit=23")&&message.contains("FIXTURE_PRE_CONNECT_FAILURE"),"actual process exit and original failure are retained");
        check(message.length()<12000,"one MiB stderr is drained with only bounded retained diagnostics");
        check(!message.contains("private-player")&&!message.contains("private-rom.gba")&&!message.contains("private-signature"),"ROM path and signed URL are redacted");
        check(!message.matches("(?s).*[a-f0-9]{64}.*"),"private worker authentication token never reaches the error");
        try(var entries=Files.list(root)){check(entries.noneMatch(p->p.getFileName().toString().startsWith("muxi-console-worker-")),"failed startup still cleans its owned native temporary directory");}
        System.out.println("WorkerStartup checks="+checks+"; actual pre-connect exit23, bounded stderr, no token/path leak or native leftovers");
    }
}
