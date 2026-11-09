package net.muxigame.console.client.transfer;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;
import net.muxigame.console.domain.Hashing;

/** Only mutable files in this mod's new per-machine directory cross the server boundary. */
public final class NativeProfileArchive {
    public static final long MAX_BYTES=512L*1024*1024;
    private static final int MAX_FILES=10000;
    private NativeProfileArchive(){}
    public record Snapshot(Path path,long size,String hash,int files,long expandedBytes){}
    public static boolean allowed(String platform,String path){
        if(path==null||path.isEmpty()||path.startsWith("/")||path.contains("\\")||path.contains(":")||Arrays.stream(path.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")))return false;
        String lower=path.toLowerCase(Locale.ROOT),base=lower.substring(lower.lastIndexOf('/')+1);
        if(lower.contains("/content/")||lower.contains("/cache/")||lower.contains("/shaders/")||lower.contains("/logs/")||lower.contains("/config/"))return false;
        if(base.matches(".*\\.(?:3ds|3dsx|cci|cxi|cia|app|nds|dsi|dol|elf|iso|wbfs|rvz|wad|nes|gba|gb|gbc|sfc|rom|pem|key|keys)"))return false;
        if(Set.of("aes_keys.txt","keys.bin","boot9.bin","boot11.bin","bios7.bin","bios9.bin","firmware.bin","nand.bin","movable.sed","otp.bin","ticket.db","credentials.json","tokens.json").contains(base))return false;
        return switch(platform){
            case "nds" -> path.startsWith("melonDS DS/dldi_sd_card/")||Set.of("melonDS DS/dldi_sd_card.bin","melonDS DS/dldi_sd_card.bin.idx","system/melonDS DS/wfcsettings.bin").contains(path);
            case "n3ds" -> path.startsWith("Azahar/sdmc/")||path.startsWith("Azahar/nand/data/")||path.matches("Azahar/nand/title/[0-9a-fA-F]{8}/[0-9a-fA-F]{8}/data/.+")||Set.of("Azahar/sysdata/mac.txt","Azahar/sysdata/mcu.dat").contains(path);
            case "wii" -> path.startsWith("User/Wii/shared2/")||path.matches("User/Wii/title/[0-9a-fA-F]{8}/[0-9a-fA-F]{8}/data/.+")||Set.of("User/Wii/sys/uid.sys","User/Wii/fst.bin","User/Load/WiiSD.raw").contains(path)||path.matches("User/GC/[A-Za-z0-9_. -]+\\.(?:raw|gci)");
            default -> false;
        };
    }
    private static void active(BooleanSupplier active)throws IOException{if(Thread.currentThread().isInterrupted()||!active.getAsBoolean())throw new InterruptedIOException("Machine profile transfer cancelled");}
    private static void directory(Path path)throws IOException{Path normal=path.toAbsolutePath().normalize(),existing=normal;while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();if(!existing.equals(existing.toRealPath()))throw new IOException("Machine profile ancestor is redirected");Files.createDirectories(normal);if(!normal.equals(normal.toRealPath()))throw new IOException("Machine profile directory is redirected");}
    private static List<Path> files(Path root,String platform)throws IOException{
        directory(root);List<Path> files=new ArrayList<>();
        try(var walk=Files.walk(root)){for(Path p:walk.toList())if(!p.equals(root)){
            BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(a.isSymbolicLink()||a.isOther()||!p.toAbsolutePath().normalize().equals(p.toRealPath()))throw new IOException("Machine profile contains a redirected or special file");
            if(a.isRegularFile()&&allowed(platform,root.relativize(p).toString().replace('\\','/')))files.add(p);
        }}
        if(files.size()>MAX_FILES)throw new IOException("Machine profile has too many files");files.sort(Comparator.comparing(p->root.relativize(p).toString()));return files;
    }
    public static Snapshot pack(Path root,String platform,Path target,BooleanSupplier running,LongConsumer progress)throws IOException{
        List<Path> files=files(root,platform);long total=0;boolean created=false,complete=false;
        try{
            try(var zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)))){
                created=true;byte[] block=new byte[65536];
                for(Path file:files){active(running);BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);ZipEntry entry=new ZipEntry(root.relativize(file).toString().replace('\\','/'));entry.setTimeLocal(java.time.LocalDateTime.of(1980,1,1,0,0));zip.putNextEntry(entry);long bytes=0;
                    try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){for(int n;(n=input.read(block))!=-1;){active(running);bytes+=n;total+=n;if(total>MAX_BYTES)throw new IOException("Expanded machine profile exceeds 512 MiB");zip.write(block,0,n);progress.accept(total);}}
                    zip.closeEntry();BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(bytes!=before.size()||!Objects.equals(before.fileKey(),after.fileKey())||before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw new IOException("Machine profile changed during checkpoint");
                }
            }
            long size=Files.size(target);if(size<1||size>MAX_BYTES)throw new IOException("Machine profile archive exceeds 512 MiB");active(running);Snapshot snapshot=new Snapshot(target,size,Hashing.sha256(target),files.size(),total);complete=true;return snapshot;
        }finally{if(created&&!complete)Files.deleteIfExists(target);}
    }
    public static void restore(Path root,String platform,Path archive,BooleanSupplier running)throws IOException{
        directory(root);int expectedFiles=validateContainer(archive);
        Path stage=Files.createTempDirectory(root.getParent(),"native-profile-restore-"),backup=Files.createTempDirectory(root.getParent(),"native-profile-prior-");
        List<Path> incoming=new ArrayList<>(),retired=new ArrayList<>(),installed=new ArrayList<>();Set<String> names=new HashSet<>();long total=0;boolean success=false;
        try{
            try(var zip=new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive,LinkOption.NOFOLLOW_LINKS)))){for(ZipEntry entry;(entry=zip.getNextEntry())!=null;){active(running);String name=entry.getName();
                if(entry.isDirectory()||!allowed(platform,name)||!names.add(name.toLowerCase(Locale.ROOT))||names.size()>MAX_FILES)throw new IOException("Machine profile archive contains an unauthorized or duplicate path");
                Path file=stage.resolve(name).normalize();if(!file.startsWith(stage))throw new IOException("Machine profile path escaped staging");directory(file.getParent());
                try(var output=Files.newOutputStream(file,StandardOpenOption.CREATE_NEW)){byte[] block=new byte[65536];for(int n;(n=zip.read(block))!=-1;){active(running);total+=n;if(total>MAX_BYTES)throw new IOException("Expanded machine profile exceeds 512 MiB");output.write(block,0,n);}}incoming.add(file);
            }}
            if(incoming.size()!=expectedFiles)throw new IOException("Machine profile central directory count differs");active(running);
            try{
                for(Path file:files(root,platform)){Path relative=root.relativize(file),saved=backup.resolve(relative);directory(saved.getParent());Files.move(file,saved);retired.add(relative);}
                for(Path file:incoming){Path relative=stage.relativize(file),destination=root.resolve(relative);directory(destination.getParent());Files.move(file,destination);installed.add(relative);}
                success=true;
            }catch(IOException|RuntimeException failure){
                for(int i=installed.size()-1;i>=0;i--)try{Files.delete(root.resolve(installed.get(i)));}catch(IOException rollback){failure.addSuppressed(rollback);}
                for(int i=retired.size()-1;i>=0;i--)try{Path relative=retired.get(i);directory(root.resolve(relative).getParent());Files.move(backup.resolve(relative),root.resolve(relative));}catch(IOException rollback){failure.addSuppressed(rollback);}
                throw failure;
            }
        }finally{remove(stage);if(success||retired.stream().noneMatch(p->Files.exists(backup.resolve(p))))remove(backup);}
    }
    private static int validateContainer(Path archive)throws IOException{
        if(!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS)||!archive.toAbsolutePath().normalize().equals(archive.toRealPath()))throw new IOException("Machine profile archive is redirected");
        long size=Files.size(archive);if(size<22||size>MAX_BYTES)throw new IOException("Machine profile archive size is invalid");
        try(var file=new RandomAccessFile(archive.toFile(),"r")){
            file.seek(size-22);byte[] end=new byte[22];file.readFully(end);java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(end).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if(b.getInt()!=0x06054b50||b.getShort()!=0||b.getShort()!=0)throw new IOException("Machine profile ZIP end record is invalid");
            int disk=Short.toUnsignedInt(b.getShort()),entries=Short.toUnsignedInt(b.getShort());long centralSize=Integer.toUnsignedLong(b.getInt()),offset=Integer.toUnsignedLong(b.getInt());
            if(entries>MAX_FILES||entries!=disk||b.getShort()!=0||offset+centralSize!=size-22)throw new IOException("Machine profile ZIP directory is invalid");
            return entries;
        }
    }
    private static void remove(Path owned)throws IOException{
        if(!owned.getFileName().toString().startsWith("native-profile-"))throw new IOException("Invalid profile staging directory");
        try(var paths=Files.walk(owned)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList()){if(Files.isSymbolicLink(path))throw new IOException("Profile staging was redirected");Files.delete(path);}}
    }
}
