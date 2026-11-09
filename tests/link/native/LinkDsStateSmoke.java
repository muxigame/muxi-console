package net.muxigame.console.tests.link;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.muxigame.console.runtime.*;

/** Actual 19 MiB MXS1 in both directions between the source-built DS link frontend and original single core. */
public final class LinkDsStateSmoke {
    private static int checks;
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    private static String sha(Path file)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(file)){byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))>=0;)if(n>0)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}
    private static int counter(AvFrame frame){byte[] rgba=frame.rgba();int value=0;for(int bit=0;bit<8;bit++){int at=(36*256+36+bit*8)*4;if((rgba[at+1]&255)>200&&(rgba[at]&255)<32)value|=1<<bit;}return value;}
    private static LinkRuntime.Frame press(LinkDsRuntime runtime)throws Exception{LinkRuntime.Frame frame=null;for(int i=0;i<3;i++)frame=runtime.step(new PortInput(256),PortInput.IDLE);for(int i=0;i<3;i++)frame=runtime.step(PortInput.IDLE,PortInput.IDLE);return frame;}
    public static void main(String[] args)throws Exception{
        if(args.length!=8)throw new IllegalArgumentException("java bridge bridgeSHA base baseSHA originalLeft originalRight output");Path java=Path.of(args[0]),bridge=Path.of(args[1]),base=Path.of(args[3]),left=Path.of(args[5]),right=Path.of(args[6]),output=Path.of(args[7]);Files.createDirectories(output);check(sha(bridge).equals(args[2])&&sha(base).equals(args[4]),"actual bridge and separate original single-core binaries match their declared identities");List<Path> cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();List<LinkDsRuntime.Endpoint> endpoints=new ArrayList<>();
        Map<String,String> options=Map.of("melonds_render_mode","software","melonds_threaded_renderer","disabled","melonds_boot_mode","direct","melonds_screen_layout1","top-bottom","melonds_number_of_screen_layouts","1","melonds_screen_gap","0");
        for(int i=0;i<2;i++){Path rom=i==0?left:right,save=Files.createDirectories(output.resolve("machine-"+i));endpoints.add(new LinkDsRuntime.Endpoint(new UUID(0,i+1),sha(rom),new LaunchConfig(java,cp,bridge,rom,Files.createDirectories(save.resolve("system")),save,options,"nds")));}
        var config=new LinkDsRuntime.Config(args[2],endpoints);Path single=output.resolve("from-link.mxs1"),reverse=output.resolve("from-original-single.mxs1");LinkDsRuntime link=LinkDsRuntime.open(config);
        try{for(int i=0;i<30;i++)link.step(PortInput.IDLE,PortInput.IDLE);var frame=press(link);check(counter(frame.left())==1&&counter(frame.right())==0,"real CPU input edge belongs only to its own DS machine");link.exportEndpointTo(0,single);check(Files.size(single)>16*1024*1024,"standard MXS1 is the full DS Console state, not a link group");}finally{link.close();check(link.naturalExit(),"first two native workers exit naturally");}
        Path save=Files.createDirectories(output.resolve("original-single"));Map<String,String> singleOptions=new HashMap<>(options);singleOptions.put("melonds_mac_address_mode",LinkDsIdentity.machineMac(new UUID(0,1)));var ordinary=ProcessConsoleRuntime.open(new LaunchConfig(java,cp,base,left,Files.createDirectories(save.resolve("system")),save,singleOptions,"nds"));
        try{ordinary.loadStateFrom(single);AvFrame frame=null;for(int i=0;i<6;i++)frame=ordinary.step(InputFrame.idle());check(counter(frame)==1,"original single DLL accepts bridge MXS1 with actual machine progress");for(int i=0;i<3;i++)frame=ordinary.step(new InputFrame(List.of(new PortInput(256))));for(int i=0;i<3;i++)frame=ordinary.step(InputFrame.idle());check(counter(frame)==2,"original single CPU continues from imported progress");ordinary.saveStateTo(reverse);}
        finally{ordinary.close();check(ordinary.exitCode().orElse(-1)==0&&!ordinary.wasForcedShutdown()&&ordinary.nativeTemporaryFilesCleaned(),"original single worker exits zero with native cleanup");}
        LinkDsRuntime imported=LinkDsRuntime.open(config);try{imported.restoreEndpointFrom(0,reverse);LinkRuntime.Frame frame=null;for(int i=0;i<10;i++)frame=imported.step(PortInput.IDLE,PortInput.IDLE);check(counter(frame.left())==2&&counter(frame.right())==0,"bridge imports original single MXS1 into its matching machine only");frame=press(imported);check(counter(frame.left())==3&&counter(frame.right())==0,"reverse-imported CPU continues native input independently");}
        finally{imported.close();check(imported.naturalExit(),"reverse-import pair closes naturally");}
        String result="{\"checks\":"+checks+",\"actualBridgeSHA256\":\""+args[2]+"\",\"baseSingleFormatSHA256\":\""+args[4]+"\",\"bidirectionalSingleStateCompatibility\":true,\"linkExportBytes\":"+Files.size(single)+",\"singleExportBytes\":"+Files.size(reverse)+",\"linkExportSHA256\":\""+sha(single)+"\",\"singleExportSHA256\":\""+sha(reverse)+"\",\"actualInputCounters\":[1,2,3],\"naturalWorkers\":5}";Files.writeString(output.resolve("result.json"),result);System.out.println(result);
    }
}
