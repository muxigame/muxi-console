import net.muxigame.console.client.transfer.NativeProfileArchive;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public final class NativeProfileArchiveTest{
 static int checks;static void check(boolean yes,String name){checks++;if(!yes)throw new AssertionError(name);}
 static void rejects(Throwing body,String name)throws Exception{try{body.run();throw new AssertionError(name);}catch(IOException expected){checks++;}}
 interface Throwing{void run()throws Exception;}
 static Path write(Path root,String path,String value)throws Exception{Path out=root.resolve(path);Files.createDirectories(out.getParent());Files.writeString(out,value);return out;}
 static Path zip(Path file,Map<String,String> paths)throws Exception{try(var zip=new ZipOutputStream(Files.newOutputStream(file))){for(var row:paths.entrySet()){zip.putNextEntry(new ZipEntry(row.getKey()));zip.write(row.getValue().getBytes());zip.closeEntry();}}return file;}
 public static void main(String[]args)throws Exception{
  Path base=Path.of(args[0]).toAbsolutePath();Files.createDirectories(base);Path root=base.resolve("machine-a");
  for(String platform:List.of("nds","n3ds","wii"))for(String bad:List.of("../outside","/absolute","C:/file","a\\file","a//file","a/../file","Azahar/sdmc/game.3dsx","Azahar/nand/title/00040000/00000001/content/00000000.app","User/Config/Dolphin.ini","Azahar/sysdata/aes_keys.txt"))check(!NativeProfileArchive.allowed(platform,bad),platform+" forbidden "+bad);
  write(root,"Azahar/sdmc/test/save.dat","original-save");write(root,"Azahar/sysdata/mac.txt","00:11:22:33:44:55");write(root,"Azahar/nand/title/00040000/00000001/data/save.dat","title-save");
  write(root,"Azahar/shaders/cache.bin","private-cache");write(root,"Azahar/sysdata/aes_keys.txt","private-user-file");write(root,"Azahar/sdmc/program.3dsx","not-save");
  var first=NativeProfileArchive.pack(root,"n3ds",base.resolve("first.zip"),()->true,n->{});check(first.files()==3,"only mutable files");
  var second=NativeProfileArchive.pack(root,"n3ds",base.resolve("second.zip"),()->true,n->{});check(first.hash().equals(second.hash()),"unchanged archive stable hash");
  Path target=base.resolve("machine-b");write(target,"Azahar/sdmc/old.dat","stale");write(target,"Azahar/sysdata/aes_keys.txt","retained-private");
  NativeProfileArchive.restore(target,"n3ds",first.path(),()->true);check(Files.readString(target.resolve("Azahar/sdmc/test/save.dat")).equals("original-save"),"restore real save");check(!Files.exists(target.resolve("Azahar/sdmc/old.dat")),"remove stale exported files");check(Files.readString(target.resolve("Azahar/sysdata/aes_keys.txt")).equals("retained-private"),"preserve unexported private files");
  Path bad=zip(base.resolve("escape.zip"),new LinkedHashMap<>(Map.of("Azahar/sdmc/valid.dat","a","../escape","b")));rejects(()->NativeProfileArchive.restore(target,"n3ds",bad,()->true),"reject traversal transactionally");check(Files.readString(target.resolve("Azahar/sdmc/test/save.dat")).equals("original-save"),"old save survives malformed archive");check(!Files.exists(base.resolve("escape")),"no escaped file");
  rejects(()->NativeProfileArchive.pack(root,"n3ds",base.resolve("cancelled.zip"),()->false,n->{}),"cancelled capture");check(!Files.exists(base.resolve("cancelled.zip")),"cancelled capture unpublished");
  Path random=write(base,"random.zip","this is not a ZIP archive but is over 22 bytes");rejects(()->NativeProfileArchive.restore(target,"n3ds",random,()->true),"reject nonzip without clearing saves");check(Files.exists(target.resolve("Azahar/sdmc/test/save.dat")),"nonzip leaves old save");
  byte[] truncated=Files.readAllBytes(first.path());Path trunc=base.resolve("truncated.zip");Files.write(trunc,Arrays.copyOf(truncated,truncated.length-22));rejects(()->NativeProfileArchive.restore(target,"n3ds",trunc,()->true),"reject missing central directory");
  Path wrong=zip(base.resolve("wrong-platform.zip"),Map.of("User/Wii/shared2/game.dat","wii-data"));rejects(()->NativeProfileArchive.restore(target,"n3ds",wrong,()->true),"reject other-platform paths");
  Path emptyRoot=base.resolve("empty");var empty=NativeProfileArchive.pack(emptyRoot,"n3ds",base.resolve("empty.zip"),()->true,n->{});NativeProfileArchive.restore(target,"n3ds",empty.path(),()->true);check(!Files.exists(target.resolve("Azahar/sdmc/test/save.dat")),"legitimate empty canonical profile clears saves");check(Files.exists(target.resolve("Azahar/sysdata/aes_keys.txt")),"empty archive preserves excluded data");
  System.out.println("NativeProfileArchiveTest: "+checks+" checks passed");
 }
}
