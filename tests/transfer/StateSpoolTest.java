import net.muxigame.console.client.transfer.StateSpool;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.security.*;
public final class StateSpoolTest{
 static int checks;static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}interface Task{void run()throws Exception;}
 static void bad(Task task)throws Exception{try{task.run();throw new AssertionError("bad state admitted");}catch(IOException expected){checks++;}}
 static String sha(Path path)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}return HexFormat.of().formatHex(digest.digest());}
 public static void main(String[]args)throws Exception{
  Path dir=Path.of(args[0]).toAbsolutePath();Files.createDirectories(dir);Path raw=dir.resolve("original.mxs1");
  try(var out=Files.newOutputStream(raw)){byte[] block=new byte[65536];Arrays.fill(block,(byte)31);for(int i=0;i<144;i++)out.write(block);}
  Path compressed=StateSpool.compress(raw,dir.resolve("packed.mxz1"),512L*1024*1024,()->true,n->{});check(!compressed.equals(raw),"large useful compression selected");check(Files.size(compressed)<Files.size(raw)/10,"real compressed reduction");
  Path expanded=StateSpool.expand(compressed,dir.resolve("expanded.mxs1"),512L*1024*1024,()->true,n->{});check(sha(raw).equals(sha(expanded)),"original bytes restored");check(Files.size(expanded)==Files.size(raw),"raw length restored");
  Path small=dir.resolve("small.mxs1");Files.write(small,new byte[16]);check(StateSpool.compress(small,dir.resolve("unused.bin"),512L*1024*1024,()->true,n->{}).equals(small),"small old state untouched");check(!Files.exists(dir.resolve("unused.bin")),"no wrapper for small state");check(StateSpool.expand(small,dir.resolve("unused-expanded.bin"),512L*1024*1024,()->true,n->{}).equals(small),"old state compatible");
  bad(()->StateSpool.expand(compressed,dir.resolve("over.bin"),1024,()->true,n->{}));bad(()->StateSpool.compress(raw,dir.resolve("cancel.bin"),512L*1024*1024,()->false,n->{}));check(!Files.exists(dir.resolve("cancel.bin")),"cancel does not publish partial");
  byte[] packed=Files.readAllBytes(compressed);packed[packed.length-8]^=1;Path corrupt=dir.resolve("corrupt.mxz1");Files.write(corrupt,packed);bad(()->StateSpool.expand(corrupt,dir.resolve("bad-expanded.bin"),512L*1024*1024,()->true,n->{}));check(!Files.exists(dir.resolve("bad-expanded.bin")),"CRC failure unpublished");
  if(args.length>1){Path real=Path.of(args[1]);long start=System.nanoTime();Path packedReal=StateSpool.compress(real,dir.resolve("wii.mxz1"),512L*1024*1024,()->true,n->{});Path restored=StateSpool.expand(packedReal,dir.resolve("wii-restored.mxs1"),512L*1024*1024,()->true,n->{});check(sha(real).equals(sha(restored)),"actual Wii state compression byte equality");System.out.println("ACTUAL_WII raw="+Files.size(real)+" transport="+Files.size(packedReal)+" elapsedMs="+(System.nanoTime()-start)/1_000_000);}
  System.out.println("StateSpoolTest: "+checks+" checks passed");
 }
}
