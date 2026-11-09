package net.muxigame.console.runtime;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Pure public-contract regressions, including the actual shared-UUID-prefix nickname collision. */
public final class Link3dsContractTest {
    private static int checks;
    private static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    private static void rejects(Runnable action,String name){try{action.run();throw new AssertionError(name);}catch(IllegalArgumentException|NullPointerException expected){checks++;}}
    @FunctionalInterface private interface IoAction {void run()throws IOException;}
    private static void ioRejects(IoAction action,String name)throws IOException {try{action.run();throw new AssertionError(name);}catch(IOException expected){checks++;}}
    private static Link3dsRuntime.Endpoint endpoint(UUID machine,LaunchConfig original,Path system,Path save){return new Link3dsRuntime.Endpoint(machine,"c".repeat(64),new LaunchConfig(original.javaExecutable(),original.classpath(),original.core(),original.content(),system,save,original.options(),"n3ds"));}
    public static void main(String[] arguments)throws Exception {
        UUID left=new UUID(1,0x4d58334453L),right=new UUID(2,0x4d58334453L);
        check(left.toString().substring(0,12).equals(right.toString().substring(0,12)),"regression identities really share UUID prefix");
        check(!Link3dsIdentity.nickname(left).equals(Link3dsIdentity.nickname(right)),"full UUID determines bounded native nickname");
        check(Link3dsIdentity.nickname(left).matches("[A-Za-z0-9_-]{4,20}"),"upstream nickname bounds");
        check(Link3dsIdentity.consoleId(left).matches("[a-f0-9]{64}")&&!Link3dsIdentity.consoleId(left).equals(Link3dsIdentity.consoleId(right)),"distinct stable Console IDs");
        byte[] mac=Link3dsIdentity.mac(left);check(mac.length==6&&(mac[0]&3)==2,"local unicast MAC");mac[0]=0;
        check((Link3dsIdentity.mac(left)[0]&3)==2&&!Arrays.equals(Link3dsIdentity.mac(left),Link3dsIdentity.mac(right)),"caller cannot mutate machine MAC and distinct machines differ");
        var options=Link3dsIdentity.singleOptions(left);check(options.size()==9&&options.get("citra_enable_touch_touchscreen").equals("enabled")&&options.get("citra_enable_motion").equals("enabled")&&options.get("citra_input_type").equals("frontend")&&options.get("citra_use_libretro_save_path").equals("LibRetro Default")&&options.get("citra_use_cpu_jit").equals("enabled"),"existing input/storage canon plus explicit JIT");
        try{options.put("other","value");throw new AssertionError("mutable canon");}catch(UnsupportedOperationException expected){checks++;}
        List<String> dependencies=new ArrayList<>();for(int i=0;i<6;i++)dependencies.add("worker/dep"+i+".jar="+String.valueOf(i).repeat(64));
        String first=Link3dsIdentity.singleFingerprint("a".repeat(64),dependencies,left);check(first.matches("[a-f0-9]{64}")&&first.equals(Link3dsIdentity.singleFingerprint("a".repeat(64),dependencies,left)),"backend/options identity stable");
        check(!first.equals(Link3dsIdentity.singleFingerprint("b".repeat(64),dependencies,left)),"actual native binary change changes fingerprint");Collections.reverse(dependencies);
        check(!first.equals(Link3dsIdentity.singleFingerprint("a".repeat(64),dependencies,left)),"dependency order is part of ordinary identity");
        rejects(()->Link3dsIdentity.singleFingerprint("a".repeat(64),List.of(),left),"missing dependencies rejected");
        List<String> duplicates=Collections.nCopies(6,"worker/same.jar="+"1".repeat(64));rejects(()->Link3dsIdentity.singleFingerprint("a".repeat(64),duplicates,left),"duplicate dependency file rejected");
        Path folder=Path.of(".").toAbsolutePath();LaunchConfig launch=new LaunchConfig(folder.resolve("java.exe"),List.of(folder.resolve("runtime.jar")),folder.resolve("core.dll"),folder.resolve("game.3dsx"),folder.resolve("system"),folder.resolve("save"),options,"n3ds");
        var endpoint=new Link3dsRuntime.Endpoint(left,"c".repeat(64),launch);rejects(()->new Link3dsRuntime.Config("a".repeat(64),List.of(endpoint,endpoint)),"same machine cannot occupy two CPUs");
        rejects(()->new Link3dsRuntime.Endpoint(right,"wrong",launch),"ROM identity required");check(!Link3dsRuntime.supportsQueueCheckpoint,"queue persistence explicitly unsupported");
        check(Link3dsNative.supportedProfileAbi(1)&&Link3dsNative.supportedProfileAbi(2)&&!Link3dsNative.supportedProfileAbi(0)&&!Link3dsNative.supportedProfileAbi(3),"only explicitly supported native-state versions pass worker control validation");
        Path parent=arguments.length==0?folder.resolve("build"):Path.of(arguments[0]).toAbsolutePath();Files.createDirectories(parent);Path temporary=Files.createTempDirectory(parent,"link3ds-directories-").toRealPath();
        try{
            Path firmware=temporary.resolve("firmware"),a=temporary.resolve("left"),b=temporary.resolve("right");
            var l=endpoint(left,launch,firmware,a);var r=endpoint(right,launch,firmware,b);
            Link3dsRuntime.validateDirectories(List.of(l,r));check(true,"shared read-only firmware with distinct writable machines is valid");
            var same=endpoint(right,launch,firmware,a);ioRejects(()->Link3dsRuntime.validateDirectories(List.of(l,same)),"same writable profile rejected");
            var nested=endpoint(right,launch,firmware,a.resolve("nested"));ioRejects(()->Link3dsRuntime.validateDirectories(List.of(l,nested)),"nested writable profile rejected");
            var crossSystem=endpoint(right,launch,a.resolve("firmware"),b);ioRejects(()->Link3dsRuntime.validateDirectories(List.of(l,crossSystem)),"firmware cannot lie inside partner writable tree");
            var crossParent=endpoint(right,launch,temporary,b);ioRejects(()->Link3dsRuntime.validateDirectories(List.of(l,crossParent)),"shared firmware cannot contain partner writable tree");
            try{Link3dsRuntime.open(new Link3dsRuntime.Config("a".repeat(64),List.of(l,same)));throw new AssertionError("overlap startup accepted");}
            catch(IOException preflight){check(Link3dsRuntime.filesClosedAfterStartupFailure(preflight),"preflight failure records explicit no-worker file closure");preflight.addSuppressed(new IOException("ordinary stderr business diagnostic"));check(Link3dsRuntime.filesClosedAfterStartupFailure(new java.util.concurrent.CompletionException(preflight)),"stderr diagnostics do not revoke actual startup cleanup proof");}
            IOException unknown=new IOException("unknown startup failure");unknown.addSuppressed(new IOException("native files perhaps closed"));check(!Link3dsRuntime.filesClosedAfterStartupFailure(unknown),"suppressed strings never invent startup cleanup proof");
        }finally{try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(path))throw new AssertionError("unexpected test symlink");Files.delete(path);}}}
        System.out.println("Link3dsContractTest "+checks+" checks passed");
    }
}
