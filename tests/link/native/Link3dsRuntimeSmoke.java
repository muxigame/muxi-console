package net.muxigame.console.runtime;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Real product-runtime Room/ordinary export and original input-ROM checks; no MC or state injection. */
public final class Link3dsRuntimeSmoke {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final JsonArray checks=new JsonArray(),processes=new JsonArray();
    private static void check(String text,boolean pass){JsonObject row=new JsonObject();row.addProperty("name",text);row.addProperty("pass",pass);checks.add(row);System.out.println((pass?"PASS ":"FAIL ")+text);System.out.flush();if(!pass)throw new AssertionError(text);}
    private static int[] evidence(Path profile)throws IOException {
        try(var files=Files.walk(profile)){List<Path> candidates=files.filter(file->file.getFileName().toString().equals("muxi-original-uds-evidence.bin")).toList();if(candidates.size()!=1)return null;byte[] raw=Files.readAllBytes(candidates.get(0));if(raw.length<48)return null;if(raw.length!=48)throw new IOException("Guest evidence size differs");int[] words=new int[12];ByteBuffer input=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<words.length;i++)words[i]=input.getInt();return words;}
    }
    private static int[] data(Path profile,String name)throws IOException {
        try(var files=Files.walk(profile)){List<Path> candidates=files.filter(file->file.getFileName().toString().equals(name)).toList();if(candidates.size()!=1)return null;byte[] bytes=Files.readAllBytes(candidates.get(0));if(bytes.length<8||bytes.length>256)return null;int[] words=new int[bytes.length/4];ByteBuffer input=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<words.length;i++)words[i]=input.getInt();return words;}
    }
    private static void image(AvFrame frame,Path target)throws IOException {
        BufferedImage image=new BufferedImage(frame.width(),frame.height(),BufferedImage.TYPE_INT_ARGB);byte[] rgba=frame.rgba();for(int i=0;i<frame.width()*frame.height();i++)image.setRGB(i%frame.width(),i/frame.width(),((rgba[i*4+3]&255)<<24)|((rgba[i*4]&255)<<16)|((rgba[i*4+1]&255)<<8)|(rgba[i*4+2]&255));ImageIO.write(image,"png",target.toFile());
    }
    private static int[] pixel(AvFrame frame,int x,int y){int at=(y*frame.width()+x)*4;byte[] rgba=frame.rgba();return new int[]{rgba[at]&255,rgba[at+1]&255,rgba[at+2]&255};}
    private static Link3dsRuntime.Config config(Path java,List<Path> classpath,Path core,String hash,Path left,Path right,Path root)throws IOException {
        List<Link3dsRuntime.Endpoint> endpoints=new ArrayList<>();for(int port=0;port<2;port++){UUID machine=new UUID(port+1,0x4d58334453L);Path profile=root.resolve(port==0?"left":"right");Files.createDirectories(profile);Path rom=port==0?left:right;LaunchConfig launch=new LaunchConfig(java,classpath,core,rom,root.resolve("shared-firmware"),profile.resolve("native-profile"),Link3dsIdentity.singleOptions(machine),"n3ds");endpoints.add(new Link3dsRuntime.Endpoint(machine,Link3dsWorker.hash(rom),launch));}return new Link3dsRuntime.Config(hash,endpoints);
    }
    private static void close(Link3dsRuntime runtime,List<Long> pids,List<Path> nativePaths)throws IOException {
        try{runtime.close();}finally{JsonObject row=new JsonObject();row.add("pids",JSON.toJsonTree(pids));row.addProperty("naturalExit",runtime.naturalExit());row.addProperty("alive",runtime.isAlive());row.addProperty("nativeDirectoriesRemoved",nativePaths.stream().noneMatch(Files::exists));processes.add(row);}
        check("product runtime workers signaled natural exit and removed exact-owned native directories",!runtime.isAlive()&&runtime.naturalExit()&&nativePaths.stream().noneMatch(Files::exists));
    }
    public static void main(String[] arguments)throws Exception {
        Path java=Path.of(arguments[0]),core=Path.of(arguments[1]),left=Path.of(arguments[3]),right=Path.of(arguments[4]),inputRom=Path.of(arguments[5]),out=Path.of(arguments[6]);String coreSHA=arguments[2];
        boolean ordinaryOnly=arguments.length>7&&arguments[7].equals("ordinary-only");
        Files.createDirectories(out);List<Path> classpath=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();
        JsonObject result=new JsonObject();result.addProperty("complete",false);result.addProperty("coreSHA256",coreSHA);result.add("checks",checks);result.add("processes",processes);
        result.addProperty("scope",ordinaryOnly?"ordinary export/restore and real input; active UDS context restore remains unsupported":"active UDS context restore plus ordinary/input");
        Link3dsRuntime runtime=null;ProcessConsoleRuntime ordinary=null;List<Long> pids=List.of();List<Path> nativePaths=List.of();
        try {
            check("requested exact native DLL",Link3dsWorker.hash(core).equals(coreSHA));check("no queue checkpoint is advertised",!Link3dsRuntime.supportsQueueCheckpoint);
            var config=config(java,classpath,core,coreSHA,left,right,out.resolve("uds-profiles"));runtime=Link3dsRuntime.open(config);pids=runtime.workerPids();nativePaths=runtime.workerNativeDirectories();
            check("two independently owned product worker processes",pids.size()==2&&!pids.get(0).equals(pids.get(1)));check("native profiles do not alias",!Files.isSameFile(runtime.profileDirectory(0),runtime.profileDirectory(1)));
            check("stable local-unicast machine MAC and Console ID are distinct",!Arrays.equals(Link3dsIdentity.mac(config.endpoints().get(0).machine()),Link3dsIdentity.mac(config.endpoints().get(1).machine()))&&!Link3dsIdentity.consoleId(config.endpoints().get(0).machine()).equals(Link3dsIdentity.consoleId(config.endpoints().get(1).machine()))&&(Link3dsIdentity.mac(config.endpoints().get(0).machine())[0]&3)==2);
            int[][] observed=null;LinkRuntime.Frame frame=null;
            for(int i=0;i<600;i++){frame=runtime.step(PortInput.IDLE,PortInput.IDLE);observed=new int[][]{evidence(runtime.profileDirectory(0)),evidence(runtime.profileDirectory(1))};if(observed[0]!=null&&observed[1]!=null&&observed[0][5]>0&&observed[1][5]>0)break;}
            check("actual original guest UDS PullPacket exchanges both challenges through product workers",observed!=null&&observed[0]!=null&&observed[1]!=null&&observed[0][5]>0&&observed[1][5]>0&&observed[0][8]==0xb6502fe4&&observed[1][8]==0x319ac76d);
            for(int i=0;i<30;i++)frame=runtime.step(PortInput.IDLE,PortInput.IDLE);
            check("both product frames preserve actual double-screen surfaces",frame.left().surfaces().size()==2&&frame.right().surfaces().size()==2&&frame.left().surfaces().get(1).touchable()&&frame.left().surfaces().get(1).x()==40&&frame.left().width()==400&&frame.left().height()==480);
            image(frame.left(),out.resolve("runtime-uds-left.png"));image(frame.right(),out.resolve("runtime-uds-right.png"));
            check("independent received-challenge images",Arrays.equals(pixel(frame.left(),160,360),new int[]{24,224,32})&&Arrays.equals(pixel(frame.right(),160,360),new int[]{24,224,192}));
            check("both real WiFi native data directions are active",runtime.nativeStatus(0).sentDataPackets()>0&&runtime.nativeStatus(0).receivedDataPackets()>0&&runtime.nativeStatus(1).sentDataPackets()>0&&runtime.nativeStatus(1).receivedDataPackets()>0);
            Path a=out.resolve("left-before.mxs1"),b=out.resolve("right-before.mxs1");runtime.exportEndpointTo(0,a);runtime.exportEndpointTo(1,b);
            check("two ordinary endpoint states are independently exported",Files.size(a)>16&&Files.size(b)>16&&!Link3dsWorker.hash(a).equals(Link3dsWorker.hash(b)));
            int[][] prior={evidence(runtime.profileDirectory(0)),evidence(runtime.profileDirectory(1))};close(runtime,pids,nativePaths);runtime=null;
            runtime=Link3dsRuntime.open(config);pids=runtime.workerPids();nativePaths=runtime.workerNativeDirectories();
            check("new private frontend starts with empty transport counters",runtime.nativeStatus(0).sentPackets()==0&&runtime.nativeStatus(1).sentPackets()==0);
            runtime.restoreEndpointFrom(0,a);runtime.restoreEndpointFrom(1,b);
            if(!ordinaryOnly){
                int nonce=0x6d39a7c2;for(int i=0;i<12;i++)runtime.step(new PortInput(1),new PortInput(1));boolean newData=false;int[][] resumed=null;
                for(int i=0;i<600;i++){frame=runtime.step(PortInput.IDLE,PortInput.IDLE);resumed=new int[][]{evidence(runtime.profileDirectory(0)),evidence(runtime.profileDirectory(1))};
                    if(resumed[0]!=null&&resumed[1]!=null&&resumed[0][5]>prior[0][5]&&resumed[1][5]>prior[1][5]&&resumed[0][8]==(0xb6502fe4^nonce)&&resumed[1][8]==(0x319ac76d^nonce)&&runtime.nativeStatus(0).receivedDataPackets()>0&&runtime.nativeStatus(1).receivedDataPackets()>0){newData=true;break;}}
                check("fresh Room exchanges new post-restore guest-input challenge in both directions without claiming old ENet queues",newData);
                result.addProperty("activeUdsContextRestoreTestedPassing",true);result.addProperty("freshChallengeXor",Integer.toUnsignedLong(nonce));result.add("radioBefore",JSON.toJsonTree(prior));result.add("radioAfter",JSON.toJsonTree(resumed));
            }else result.addProperty("activeUdsContextRestoreTestedPassing",false);
            for(int i=0;i<12;i++)runtime.step(new PortInput(256),PortInput.IDLE);for(int i=0;i<35;i++)runtime.step(PortInput.IDLE,PortInput.IDLE);
            int[] writes=data(runtime.profileDirectory(0),"muxi-original-relocation.bin");check("real A input writes restored machine native files",writes!=null&&writes[0]==0x52454c31&&writes[1]==1);
            Path after=out.resolve("left-after.mxs1");runtime.exportEndpointTo(0,after);close(runtime,pids,nativePaths);runtime=null;
            var first=config.endpoints().get(0).launch();ordinary=ProcessConsoleRuntime.open(first);long ordinaryPid=ordinary.workerPid();Path ordinaryNative=ordinary.nativeTemporaryDirectory();ordinary.loadStateFrom(after);
            for(int i=0;i<12;i++)ordinary.step(new InputFrame(List.of(new PortInput(256))));for(int i=0;i<35;i++)ordinary.step(InputFrame.idle());
            writes=data(first.saveDirectory(),"muxi-original-relocation.bin");check("ordinary single core resumes exported CPU state and increments its own native save",writes!=null&&writes[1]==2);
            ordinary.close();JsonObject single=new JsonObject();single.addProperty("pid",ordinaryPid);single.addProperty("exit",ordinary.exitCode().orElse(-1));single.addProperty("forced",ordinary.wasForcedShutdown());single.addProperty("nativeClean",ordinary.nativeTemporaryFilesCleaned());processes.add(single);
            check("ordinary core has actual guarded natural exit and native cleanup",!ordinary.isAlive()&&ordinary.exitCode().orElse(-1)==0&&!ordinary.wasForcedShutdown()&&ordinary.nativeTemporaryFilesCleaned()&&!Files.exists(ordinaryNative));ordinary=null;
            var inputConfig=config(java,classpath,core,coreSHA,inputRom,inputRom,out.resolve("input-profiles"));runtime=Link3dsRuntime.open(inputConfig);pids=runtime.workerPids();nativePaths=runtime.workerNativeDirectories();
            for(int i=0;i<90;i++)frame=runtime.step(PortInput.IDLE,PortInput.IDLE);
            PortInput touched=new PortInput(256).withPointer(new PortInput.Pointer("bottom",(short)-10000,(short)10000,true,true));
            for(int i=0;i<35;i++)frame=runtime.step(touched,PortInput.IDLE);
            image(frame.left(),out.resolve("runtime-input-left.png"));image(frame.right(),out.resolve("runtime-input-right.png"));
            check("real extended PortInput touches only the selected bottom surface",Arrays.equals(pixel(frame.left(),100,400),new int[]{240,220,0})&&Arrays.equals(pixel(frame.right(),100,400),new int[]{0,0,240}));
            check("real keyboard-mapped A reaches only its independent CPU",Arrays.equals(pixel(frame.left(),200,100),new int[]{0,240,16})||Arrays.equals(pixel(frame.left(),200,100),new int[]{0,240,24}));
            int[] record=data(runtime.profileDirectory(0),"muxi-console-original-save.dat");check("original input diagnostic makes an actual native save",record!=null&&record[0]==0x4d584433&&record[1]==1);
            close(runtime,pids,nativePaths);runtime=null;result.addProperty("complete",true);
        } catch(Throwable failure){result.addProperty("error",failure.toString());failure.printStackTrace(System.err);}
        finally {
            if(runtime!=null)try{close(runtime,pids,nativePaths);}catch(Throwable cleanup){result.addProperty("cleanupFailure",cleanup.toString());}
            if(ordinary!=null)try{ordinary.close();}catch(Throwable cleanup){result.addProperty("ordinaryCleanupFailure",cleanup.toString());}
            if(result.has("cleanupFailure")||result.has("ordinaryCleanupFailure"))result.addProperty("complete",false);
            Files.writeString(out.resolve("result.json"),JSON.toJson(result)+"\n");
        }
        if(!result.get("complete").getAsBoolean())System.exit(1);
    }
}
