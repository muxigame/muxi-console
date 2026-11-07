package net.muxigame.console.domain;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Our media envelope preserves MAME set basenames, never accepts a command line and never supplies game data. */
public final class ArcadeBundle {
    public static final String MANIFEST="manifest.properties";
    public static final long MAX_BYTES=512L*1024*1024, MAX_EXPANDED=2L*1024*1024*1024;
    public static final int MAX_SETS=64, MAX_INNER_ENTRIES=65536;
    public record Member(String name,long bytes,String sha256){}
    public record Manifest(String driver,String main,List<Member> members){public Manifest{members=List.copyOf(members);}}
    private record Scanned(Member member,long crc){}
    private static boolean setName(String name){return name!=null&&name.matches("[a-z0-9_]{1,32}\\.zip");}
    private static IOException invalid(String detail){return new IOException("Invalid mxarc media: "+detail);}
    private static void regular(Path file)throws IOException{if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw invalid("expected a regular file");}
    private static Scanned scan(Path file,String name)throws IOException{
        regular(file);long size=Files.size(file);if(size<22||size>MAX_BYTES)throw invalid("ROM set size");var hash=Hashing.sha256Digest();CRC32 crc=new CRC32();long count=0;
        try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[32768];for(int n;(n=in.read(buffer))!=-1;){count+=n;if(count>size)throw invalid("set changed while read");hash.update(buffer,0,n);crc.update(buffer,0,n);}}
        if(count!=size)throw invalid("set changed while read");return new Scanned(new Member(name,size,HexFormat.of().formatHex(hash.digest())),crc.getValue());
    }
    private static void safeInnerName(String name)throws IOException{
        if(name==null||name.isBlank()||name.length()>256||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0||name.codePoints().anyMatch(Character::isISOControl))throw invalid("unsafe ROM member name");
        String value=name.endsWith("/")?name.substring(0,name.length()-1):name;String[] parts=value.split("/",-1);if(parts.length>16)throw invalid("ROM member depth");for(String part:parts)if(part.isEmpty()||part.equals(".")||part.equals("..")||part.endsWith(".")||part.endsWith(" "))throw invalid("unsafe ROM member path");
    }
    /** Reads every decompressed ROM byte with fixed memory; no inner ROM entry is ever extracted or executed. */
    public static long validateRomZip(Path zip,long budget)throws IOException{
        regular(zip);long expanded=0;Set<String> names=new HashSet<>();
        try(ZipFile archive=new ZipFile(zip.toFile(),StandardCharsets.UTF_8)){
            Enumeration<? extends ZipEntry> entries=archive.entries();byte[] buffer=new byte[32768];int count=0;
            while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();if(++count>MAX_INNER_ENTRIES)throw invalid("too many ROM members");safeInnerName(entry.getName());if(!names.add(entry.getName().toLowerCase(Locale.ROOT)))throw invalid("duplicate ROM member");if(entry.getMethod()!=ZipEntry.STORED&&entry.getMethod()!=ZipEntry.DEFLATED)throw invalid("unsupported ZIP compression");if(entry.getSize()<0||entry.getSize()>MAX_BYTES||entry.getSize()>budget-expanded)throw invalid("expanded ROM size");
                CRC32 crc=new CRC32();long bytes=0;try(InputStream in=archive.getInputStream(entry)){for(int n;(n=in.read(buffer))!=-1;){bytes+=n;expanded+=n;if(bytes>entry.getSize()||expanded>budget||expanded>MAX_EXPANDED)throw invalid("expanded ROM limit");crc.update(buffer,0,n);}}
                if(bytes!=entry.getSize()||crc.getValue()!=entry.getCrc())throw invalid("ROM member size/CRC");
                if(bytes>16L*1024*1024&&bytes>Math.max(1,entry.getCompressedSize())*1000L)throw invalid("ROM compression ratio");
            }
        }catch(ZipException bad){throw invalid("malformed ROM ZIP: "+bad.getMessage());}return expanded;
    }
    /** Packs a chosen driver.zip plus explicitly chosen parent/BIOS sets, without renaming its driver. */
    public static Manifest pack(Path main,List<Path> dependencies,Path output)throws IOException{
        String mainName=main.getFileName().toString().toLowerCase(Locale.ROOT);if(!setName(mainName)||dependencies==null||dependencies.size()>=MAX_SETS)throw invalid("choose a driver.zip and at most 63 dependencies");
        TreeMap<String,Path> inputs=new TreeMap<>();inputs.put(mainName,main);for(Path file:dependencies){String name=file.getFileName().toString().toLowerCase(Locale.ROOT);if(!setName(name)||inputs.putIfAbsent(name,file)!=null)throw invalid("duplicate or invalid dependency set name");}
        List<Scanned> scanned=new ArrayList<>();long total=0,expanded=0;for(var entry:inputs.entrySet()){Scanned item=scan(entry.getValue(),entry.getKey());total+=item.member.bytes;if(total>MAX_BYTES-65536)throw invalid("bundle exceeds 512 MiB");expanded+=validateRomZip(entry.getValue(),MAX_EXPANDED-expanded);scanned.add(item);}
        Manifest manifest=new Manifest(mainName.substring(0,mainName.length()-4),mainName,scanned.stream().map(Scanned::member).toList());byte[] description=description(manifest).getBytes(StandardCharsets.US_ASCII);boolean success=false;OutputStream target=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW);
        try{try(ZipOutputStream zip=new ZipOutputStream(target,StandardCharsets.UTF_8)){
            stored(zip,MANIFEST,description);byte[] buffer=new byte[32768];for(Scanned item:scanned){ZipEntry entry=new ZipEntry("roms/"+item.member.name);entry.setTime(315532800000L);entry.setMethod(ZipEntry.STORED);entry.setSize(item.member.bytes);entry.setCompressedSize(item.member.bytes);entry.setCrc(item.crc);zip.putNextEntry(entry);var hash=Hashing.sha256Digest();long count=0;try(InputStream in=Files.newInputStream(inputs.get(item.member.name),LinkOption.NOFOLLOW_LINKS)){for(int n;(n=in.read(buffer))!=-1;){count+=n;if(count>item.member.bytes)throw invalid("set changed during packing");hash.update(buffer,0,n);zip.write(buffer,0,n);}}if(count!=item.member.bytes||!HexFormat.of().formatHex(hash.digest()).equals(item.member.sha256))throw invalid("set changed during packing");zip.closeEntry();}}success=true;
        }finally{if(!success)Files.deleteIfExists(output);}if(Files.size(output)>MAX_BYTES){Files.delete(output);throw invalid("bundle exceeds 512 MiB");}return manifest;
    }
    private static void stored(ZipOutputStream zip,String name,byte[] body)throws IOException{CRC32 crc=new CRC32();crc.update(body);ZipEntry e=new ZipEntry(name);e.setTime(315532800000L);e.setMethod(ZipEntry.STORED);e.setSize(body.length);e.setCompressedSize(body.length);e.setCrc(crc.getValue());zip.putNextEntry(e);zip.write(body);zip.closeEntry();}
    private static String description(Manifest m){StringBuilder s=new StringBuilder("format=mxarc-1\ndriver="+m.driver+"\nmain="+m.main+"\nsets="+String.join(",",m.members.stream().map(Member::name).toList())+"\n");for(Member member:m.members)s.append("bytes.").append(member.name).append('=').append(member.bytes).append('\n').append("sha256.").append(member.name).append('=').append(member.sha256).append('\n');return s.toString();}
    private static Manifest parse(byte[] text)throws IOException{
        if(text.length<1||text.length>32768)throw invalid("manifest size");for(byte b:text)if(b!=10&&(b<32||b>126))throw invalid("manifest must be plain ASCII lines");Map<String,String> values=new LinkedHashMap<>();for(String line:new String(text,StandardCharsets.US_ASCII).split("\n")){if(line.isEmpty())continue;int equals=line.indexOf('=');if(equals<1||values.putIfAbsent(line.substring(0,equals),line.substring(equals+1))!=null)throw invalid("duplicate or malformed manifest field");}
        String driver=values.get("driver"),main=values.get("main");if(!"mxarc-1".equals(values.get("format"))||driver==null||!driver.matches("[a-z0-9_]{1,32}")||!Objects.equals(main,driver+".zip")||!values.containsKey("sets"))throw invalid("manifest driver/format");String[] sets=values.get("sets").split(",",-1);if(sets.length<1||sets.length>MAX_SETS)throw invalid("manifest set count");Set<String> names=new HashSet<>();List<Member> members=new ArrayList<>();long total=0;
        for(String name:sets){if(!setName(name)||!names.add(name))throw invalid("manifest set name");long size;try{String raw=values.get("bytes."+name);if(raw==null||!raw.matches("[0-9]{1,10}"))throw new NumberFormatException();size=Long.parseLong(raw);}catch(NumberFormatException bad){throw invalid("manifest set length");}String hash=values.get("sha256."+name);if(size<22||size>MAX_BYTES||hash==null||!hash.matches("[0-9a-f]{64}"))throw invalid("manifest set identity");total+=size;if(total>MAX_BYTES)throw invalid("manifest total length");members.add(new Member(name,size,hash));}
        if(!names.contains(main)||values.size()!=4+2*members.size())throw invalid("unlisted manifest fields or missing main");return new Manifest(driver,main,members);
    }
    private static Manifest openManifest(ZipFile zip)throws IOException{
        ZipEntry manifest=zip.getEntry(MANIFEST);if(manifest==null||manifest.getSize()<1||manifest.getSize()>32768)throw invalid("missing manifest");byte[] text;try(InputStream in=zip.getInputStream(manifest)){text=in.readNBytes(32769);}CRC32 crc=new CRC32();crc.update(text);if(text.length!=manifest.getSize()||text.length>32768||crc.getValue()!=manifest.getCrc())throw invalid("manifest length/CRC");Manifest parsed=parse(text);Set<String> expected=new HashSet<>();expected.add(MANIFEST);for(Member m:parsed.members)expected.add("roms/"+m.name);Set<String> actual=new HashSet<>();Enumeration<? extends ZipEntry> entries=zip.entries();while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();if(!expected.contains(e.getName())||!actual.add(e.getName())||e.isDirectory()||e.getMethod()!=ZipEntry.STORED&&e.getMethod()!=ZipEntry.DEFLATED)throw invalid("unexpected or duplicate bundle member");}if(!actual.equals(expected))throw invalid("missing bundle member");return parsed;
    }
    private static void copySet(ZipFile archive,Member member,Path output)throws IOException{
        ZipEntry entry=archive.getEntry("roms/"+member.name);if(entry==null||entry.getSize()!=member.bytes)throw invalid("set length differs from manifest");var hash=Hashing.sha256Digest();CRC32 crc=new CRC32();long count=0;try(InputStream in=archive.getInputStream(entry);OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)){byte[] buffer=new byte[32768];for(int n;(n=in.read(buffer))!=-1;){count+=n;if(count>member.bytes)throw invalid("set exceeds manifest length");hash.update(buffer,0,n);crc.update(buffer,0,n);out.write(buffer,0,n);}}
        if(count!=member.bytes||crc.getValue()!=entry.getCrc()||!HexFormat.of().formatHex(hash.digest()).equals(member.sha256))throw invalid("set hash/CRC differs from manifest");
    }
    public static Manifest inspect(Path file)throws IOException{
        regular(file);if(Files.size(file)<22||Files.size(file)>MAX_BYTES)throw invalid("bundle length");try(InputStream input=Files.newInputStream(file)){if(!Arrays.equals(input.readNBytes(4),new byte[]{80,75,3,4}))throw invalid("bundle must begin with ZIP data");}Path temporary=Files.createTempDirectory(file.toAbsolutePath().getParent(),".mxarc-check-");
        try(ZipFile zip=new ZipFile(file.toFile(),StandardCharsets.UTF_8)){Manifest manifest=openManifest(zip);long expanded=0;for(Member member:manifest.members){Path inner=temporary.resolve(member.name);copySet(zip,member,inner);expanded+=validateRomZip(inner,MAX_EXPANDED-expanded);Files.delete(inner);}return manifest;}
        finally{try(var files=Files.list(temporary)){for(Path path:files.toList())Files.deleteIfExists(path);}Files.delete(temporary);}
    }
    /** Extracts only the already-validated set ZIPs, preserving MAME's basename lookup semantics. */
    public static Path extract(Path bundle,Path cache)throws IOException{
        Manifest manifest=inspect(bundle);Path root=cache.toAbsolutePath().normalize();Files.createDirectories(root);if(Files.isSymbolicLink(root)||!root.toRealPath().equals(root))throw invalid("unsafe media cache");Path roms=root.resolve("roms");Files.createDirectories(roms);if(Files.isSymbolicLink(roms)||!roms.toRealPath().equals(roms))throw invalid("unsafe ROM directory");
        Set<String> expected=new HashSet<>(manifest.members.stream().map(Member::name).toList());try(var children=Files.list(roms)){for(Path file:children.toList())if(!expected.contains(file.getFileName().toString()))throw invalid("unlisted cached ROM set");}
        try(ZipFile zip=new ZipFile(bundle.toFile(),StandardCharsets.UTF_8)){for(Member member:manifest.members){Path file=roms.resolve(member.name);if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){regular(file);if(Files.size(file)==member.bytes&&Hashing.sha256(file).equals(member.sha256))continue;throw invalid("cached ROM set was modified");}Path temp=roms.resolve("copy-"+UUID.randomUUID()+".tmp");try{copySet(zip,member,temp);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}}return roms.resolve(manifest.main);
    }
    private ArcadeBundle(){}
}
