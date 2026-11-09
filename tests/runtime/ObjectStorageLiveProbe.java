package net.muxigame.console.tests.runtime;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.muxigame.console.domain.Hashing;
import net.muxigame.console.domain.storage.*;
import net.muxigame.console.client.transfer.*;

/** Explicit live-provider acceptance with an operator-selected original diagnostic; never an automatic test. */
public final class ObjectStorageLiveProbe {
    private static int checks;
    private static void check(boolean good)throws Exception{checks++;if(!good)throw new Exception("Live storage check "+checks+" failed");}
    private static HttpMediaTransfer.Ticket client(ObjectStorage.Ticket t){
        // Match the real CloudMediaServer ticket serializer: Java's HTTP stack supplies these headers.
        Map<String,String> headers=new LinkedHashMap<>();t.headers().forEach((key,value)->{if(!Set.of("host","content-length","connection","expect","upgrade").contains(key.toLowerCase(Locale.ROOT)))headers.put(key,value);});
        return new HttpMediaTransfer.Ticket(t.method(),t.url(),headers,t.expiresAtMillis());
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=5)throw new IllegalArgumentException("Explicit private config, original ROM, expected SHA256, platform and new output directory required");
        Path config=Path.of(args[0]),rom=Path.of(args[1]),out=Path.of(args[4]);String hash=Hashing.normalizeSha256(args[2]),platform=args[3];
        check(Files.isRegularFile(rom,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(rom)&&Files.size(rom)>=1024&&Files.size(rom)<=1024*1024&&Hashing.sha256(rom).equals(hash));
        if(Files.exists(out))throw new IllegalArgumentException("Preserve prior live-provider evidence");Files.createDirectories(out);
        ObjectStorage storage=ObjectStorages.load(config).orElseThrow();long size=Files.size(rom);AtomicLong sent=new AtomicLong();boolean reused=storage.head(hash).isPresent();
        if(!reused){int status=HttpMediaTransfer.put(client(storage.upload(hash,size,platform)),rom,size,()->true,sent::set);check(status==200||status==201);check(sent.get()==size);}
        var metadata=storage.head(hash).orElseThrow();check(metadata.size()==size&&metadata.sha256().equals(hash)&&metadata.platform().equals(platform));
        // The provider itself must reject a second PUT. No server object-body GET is used to check this.
        int conflict=HttpMediaTransfer.put(client(storage.upload(hash,size,platform)),rom,size,()->true,ignored->{});check(conflict==409||conflict==412);
        byte[] original=Files.readAllBytes(rom);
        try(var reader=new HttpMediaTransfer.RangeReader(hash,size,offset->client(storage.download(hash)),()->true)){
            var piece=reader.read(1024);check(piece.offset()==1024&&piece.data().length>0&&Arrays.equals(piece.data(),Arrays.copyOfRange(original,1024,1024+piece.data().length)));
        }
        LoadProgress progress=new LoadProgress(UUID.randomUUID());Path downloaded;
        try(var reader=new HttpMediaTransfer.RangeReader(hash,size,offset->client(storage.download(hash)),()->true)){
            downloaded=RomTransfer.receive(out.resolve("client-cache"),hash,"rom",size,reader,()->true,progress,RomTransfer.STREAM_CHUNK_BYTES);
        }
        check(Files.size(downloaded)==size&&Hashing.sha256(downloaded).equals(hash));
        check(storage.head(hash).orElseThrow().equals(metadata));
        Files.writeString(out.resolve("result.json"),"{\"complete\":true,\"checks\":"+checks+",\"romSha256\":\""+hash+"\",\"romBytes\":"+size+",\"firstPutReused\":"+reused+",\"authorUploadedBytes\":"+sent.get()+",\"conditionalPutStatus\":"+conflict+",\"actualClientRangeAndShaVerified\":true,\"serverObjectBodyGets\":0,\"credentialsOrSignedUrlsRecorded\":false}\n");
        System.out.println("Live object storage: "+checks+" checks passed; direct client PUT/Range/SHA, server HEAD only; credentials redacted");
    }
}
