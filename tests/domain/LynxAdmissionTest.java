package net.muxigame.console.tests.domain;

import java.nio.file.*;
import java.util.*;
import net.muxigame.console.domain.*;

public final class LynxAdmissionTest {
    private static int checks;
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Path.of(args[0]).toAbsolutePath().getParent(),"lynx-admission-");
        try{
            RomInspector inspector=new RomInspector();byte[] object=new byte[1024];object[0]=(byte)0x80;object[1]=8;object[2]=2;object[3]=10;object[4]=4;object[6]='B';object[7]='S';object[8]='9';object[9]='3';Path homebrew=root.resolve("own.o");Files.write(homebrew,object);
            check(inspector.inspect(homebrew,Platform.LYNX).detection().equals("lynx-bs93-size-and-entry"),"Explicit BS93 admitted");check(inspector.inspect(homebrew,null).platform()==Platform.LYNX,"Homebrew format selects Lynx");
            for(int at:new int[]{2,4,6,7,8,9}){byte[] bad=object.clone();bad[at]=0;if(at==2)bad[3]=0;Files.write(homebrew,bad);try{inspector.inspect(homebrew,Platform.LYNX);throw new AssertionError("Invalid BS93 admitted "+at);}catch(DomainException expected){checks++;}}
            Files.write(homebrew,new byte[131072]);try{inspector.inspect(homebrew,Platform.LYNX);throw new AssertionError("Arbitrary .o admitted");}catch(DomainException expected){checks++;}
            Path cart=root.resolve("own.lnx");Files.write(cart,new byte[131072]);check(inspector.inspect(cart,Platform.LYNX).detection().equals("lynx-raw-cartridge-size"),"Raw supported cartridge preserved");
            byte[] lnx=new byte[65600];lnx[0]='L';lnx[1]='Y';lnx[2]='N';lnx[3]='X';Files.write(cart,lnx);check(inspector.inspect(cart,Platform.LYNX).detection().equals("lynx-cartridge-header"),"Header cartridge admitted separately");
            Files.write(cart,Arrays.copyOf(lnx,64));try{inspector.inspect(cart,Platform.LYNX);throw new AssertionError("Header-only file admitted");}catch(DomainException expected){checks++;}
            Files.write(homebrew,new byte[]{0x7f,0x45,0x4c,0x46,0,0,'B','S','9','3',0});try{inspector.inspect(homebrew,Platform.LYNX);throw new AssertionError("Host ELF object admitted");}catch(DomainException expected){checks++;}
            System.out.println("Lynx admission: "+checks+" checks passed");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
