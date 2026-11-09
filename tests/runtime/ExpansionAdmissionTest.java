import net.muxigame.console.domain.*;
import java.nio.*;
import java.nio.file.*;
public final class ExpansionAdmissionTest{
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}interface Task{void run()throws Exception;}
 static void rejected(Task task)throws Exception{try{task.run();throw new AssertionError("invalid format admitted");}catch(DomainException expected){checks++;}}
 static void crc(byte[] h){int c=0xffff;for(int i=0;i<0x15e;i++){c^=h[i]&255;for(int b=0;b<8;b++)c=(c>>>1)^((c&1)!=0?0xa001:0);}h[0x15e]=(byte)c;h[0x15f]=(byte)(c>>>8);}
 public static void main(String[]args)throws Exception{
  Path original=Path.of(args[0]),out=Path.of(args[1]);Files.createDirectories(out);var inspector=new RomInspector();
  for(var row:new Object[][]{{Platform.NDS,"muxi-input.nds"},{Platform.N3DS,"muxi-input.3dsx"},{Platform.WII,"muxi-input.dol"}}){Platform platform=(Platform)row[0];Path file=original.resolve((String)row[1]);RomDescriptor descriptor=inspector.inspect(file,platform);check(descriptor.platform()==platform,"genuine original platform admission");check(descriptor.sha256().equals(Hashing.sha256(file)),"actual streaming digest");check(!descriptor.detection().equals("extension-and-size"),"structured format validation");}
  check(Platform.NDS.customModelData()==23&&Platform.N3DS.customModelData()==24&&Platform.WII.customModelData()==25,"new stable model ids");check(Platform.ARCADE.customModelData()==21&&Platform.FDS.customModelData()==20,"old ids preserved");check(Platform.NES.maxBytes()==64L*1024*1024&&Platform.ARCADE.maxBytes()==512L*1024*1024,"old per-platform limits preserved");check(Platform.WII.maxBytes()==9L*1024*1024*1024&&RomInspector.MAX_ROM_BYTES>=Platform.WII.maxBytes(),"long ROM streaming admission");
  byte[] ds=Files.readAllBytes(original.resolve("muxi-input.nds"));ds[10]^=1;Path file=out.resolve("bad-crc.nds");Files.write(file,ds);rejected(()->inspector.inspect(file,Platform.NDS));ds[10]^=1;ByteBuffer.wrap(ds).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20,ds.length);crc(ds);Files.write(file,ds);rejected(()->inspector.inspect(file,Platform.NDS));
  byte[] three=Files.readAllBytes(original.resolve("muxi-input.3dsx"));ByteBuffer.wrap(three).order(ByteOrder.LITTLE_ENDIAN).putInt(16,-1);Path large=out.resolve("bad-segment.3dsx");Files.write(large,three);rejected(()->inspector.inspect(large,Platform.N3DS));
  byte[] wii=Files.readAllBytes(original.resolve("muxi-input.dol"));ByteBuffer.wrap(wii).order(ByteOrder.BIG_ENDIAN).putInt(0xe0,0x1234);Path dol=out.resolve("bad-entry.dol");Files.write(dol,wii);rejected(()->inspector.inspect(dol,Platform.WII));
  byte[] host=new byte[512];host[0]=0x7f;host[1]='E';host[2]='L';host[3]='F';host[4]=2;host[5]=1;host[18]=62;Path elf=out.resolve("host.elf");Files.write(elf,host);rejected(()->inspector.inspect(elf,Platform.WII));
  Thread.currentThread().interrupt();try{try{inspector.inspect(original.resolve("muxi-input.nds"),Platform.NDS);throw new AssertionError("cancelled hash continued");}catch(java.io.InterruptedIOException expected){checks++;}}finally{Thread.interrupted();}
  System.out.println("ExpansionAdmissionTest: "+checks+" checks passed");
 }
}
