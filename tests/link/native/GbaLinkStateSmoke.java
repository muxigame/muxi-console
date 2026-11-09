package net.muxigame.console.tests.link;

import com.sun.jna.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.muxigame.console.runtime.*;

/** Native adapter execution and actual fixed official mGBA single-worker state interchange. Not an MC test. */
public final class GbaLinkStateSmoke {
    public interface Api extends Library {
        int muxi_gba_link_abi();
        int muxi_gba_link_open(String left,String right);
        void muxi_gba_link_close();
        int muxi_gba_link_step(int left,int right);
        int muxi_gba_link_read(int endpoint,int address);
        long muxi_gba_link_export(int endpoint,byte[] output,long capacity);
        int muxi_gba_link_restore(int endpoint,byte[] data,long size);
        long muxi_gba_link_group_export(byte[] output,long capacity);
        int muxi_gba_link_group_restore(byte[] data,long size);
    }
    private static int checks;
    private static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    private static void step(Api api,int left,int right,int count){for(int i=0;i<count;i++)check(api.muxi_gba_link_step(left,right)==1,"real cooperative GBA frame");}
    private static int count(Api api,int endpoint){return(api.muxi_gba_link_read(endpoint,0x02000000)>>>8)&255;}
    private static byte[] export(Api api,int endpoint){int n=Math.toIntExact(api.muxi_gba_link_export(endpoint,null,0));check(n>1000&&n<4*1024*1024,"bounded native single export");byte[] bytes=new byte[n];check(api.muxi_gba_link_export(endpoint,bytes,n)==n,"exact single export size");return bytes;}
    private static byte[] group(Api api){int n=Math.toIntExact(api.muxi_gba_link_group_export(null,0));check(n>1000&&n<8*1024*1024,"bounded native cable snapshot");byte[] bytes=new byte[n];check(api.muxi_gba_link_group_export(bytes,n)==n,"exact group export size");return bytes;}
    private static AvFrame run(ProcessConsoleRuntime runtime,int buttons,int count)throws IOException{AvFrame output=null;for(int i=0;i<count;i++)output=runtime.step(new InputFrame(List.of(new PortInput(buttons))));return output;}
    public static void main(String[] args)throws Exception {
        Path java=Path.of(args[0]);List<Path> classpath=Arrays.stream(args[1].split(File.pathSeparator)).map(Path::of).toList();Path adapter=Path.of(args[2]),base=Path.of(args[3]);Path[] roms={Path.of(args[4]),Path.of(args[5])};Path output=Path.of(args[6]);Files.createDirectories(output);
        Api api=Native.load(adapter.toAbsolutePath().toString(),Api.class);check(api.muxi_gba_link_abi()==1,"declared original ABI");
        check(api.muxi_gba_link_open(roms[0].toAbsolutePath().toString(),roms[1].toAbsolutePath().toString())==1,"two real GBA boards attached");
        byte[][] toSingle=new byte[2][],back=new byte[2][];byte[][] singleSram=new byte[2][];
        try{
            step(api,0,0,20);check((api.muxi_gba_link_read(0,0x02000000)&255)==0xa7,"primary receives actual second challenge");check((api.muxi_gba_link_read(1,0x02000000)&255)==0x3c,"secondary receives actual first challenge");
            step(api,1,0,8);check(count(api,0)==1&&count(api,1)==0,"separate native KEYINPUT");for(int i=0;i<2;i++)toSingle[i]=export(api,i);
            for(int i=0;i<2;i++){
                ProcessConsoleRuntime single=ProcessConsoleRuntime.open(new LaunchConfig(java,classpath,base,roms[i],output.resolve("system"),output.resolve("single-"+i),Map.of("mgba_use_bios","OFF")));
                try{run(single,0,1);single.loadState(toSingle[i]);byte[] sram=single.readSram();check((sram[0]&255)==(i==0?0xa7:0x3c)&&sram[1]==(i==0?1:0)&&sram[2]==1,"official single core restores own battery/serial progress");
                    run(single,0,5);AvFrame frame=run(single,1<<8,8);check(frame.width()==240&&frame.height()==160,"official single CPU continues native GBA picture");singleSram[i]=single.readSram();check((singleSram[i][1]&255)==(i==0?2:1),"official single input continues after bridge export");back[i]=single.saveState();Files.write(output.resolve("single-return-"+i+".mxs"),back[i]);
                }finally{single.close();check(single.exitCode().orElse(-1)==0&&!single.wasForcedShutdown()&&single.nativeTemporaryFilesCleaned(),"official single child naturally exits with native cleanup");}
            }
            for(int i=0;i<2;i++)check(api.muxi_gba_link_restore(i,back[i],back[i].length)==1,"official single MXS1 returns to physical link endpoint");
            check(count(api,0)==2&&count(api,1)==1,"reverse import retains independent CPU input progress");
            step(api,0,0,5);step(api,0,1,8);check(count(api,0)==2&&count(api,1)==2,"second endpoint input resumes after reverse import");
            byte[] group=group(api);Files.write(output.resolve("combined-native.mgl"),group);int[] before={api.muxi_gba_link_read(0,0x02000000),api.muxi_gba_link_read(1,0x02000000)};
            step(api,0,0,5);step(api,1,0,8);check(count(api,0)==3,"CPU progress changes after barrier");check(api.muxi_gba_link_group_restore(group,group.length)==1,"actual coordinator and both states restore");
            check(api.muxi_gba_link_read(0,0x02000000)==before[0]&&api.muxi_gba_link_read(1,0x02000000)==before[1],"both endpoint CPU progress restores together");
            byte[] bad=group.clone();bad[0]^=1;check(api.muxi_gba_link_group_restore(bad,bad.length)==0,"wrong group format rejected");check(api.muxi_gba_link_read(0,0x02000000)==before[0]&&api.muxi_gba_link_read(1,0x02000000)==before[1],"rejected state preserves live pair");
            api.muxi_gba_link_close();check(api.muxi_gba_link_open(roms[0].toAbsolutePath().toString(),roms[1].toAbsolutePath().toString())==1,"cold native pair opens");check(api.muxi_gba_link_group_restore(group,group.length)==1,"cold coordinator restore");step(api,0,0,5);step(api,1,0,8);check(count(api,0)==3&&count(api,1)==2,"cold-restored CPUs continue separate inputs");
        }finally{api.muxi_gba_link_close();}
        System.out.println("GbaLinkStateSmoke "+checks+" checks passed; actual single<->link compatibility, cable barrier/cold restore");
    }
}
