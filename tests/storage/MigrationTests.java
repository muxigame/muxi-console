package net.muxigame.console.tests.storage;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.muxigame.console.domain.*;
import net.muxigame.console.ops.ContentMigration;

/** A real loopback HEAD/PUT migration, including an idempotent repeat and an unchanged local source. */
public final class MigrationTests {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Files.createDirectories(Path.of(args[0])),"migration-");byte[] body=new byte[32768];new Random(819).nextBytes(body);Path rom=root.resolve("demo.sfc");Files.write(rom,body);
        ContentStore content=new ContentStore(root.resolve("content"));RomDescriptor descriptor=new RomInspector().inspect(rom,Platform.SNES);content.publish(rom,descriptor);
        AtomicInteger heads=new AtomicInteger(),puts=new AtomicInteger(),gets=new AtomicInteger();Map<String,byte[]> objects=new HashMap<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            String key=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
            if(method.equals("GET")){gets.incrementAndGet();exchange.sendResponseHeaders(500,-1);return;}
            if(method.equals("PUT")){puts.incrementAndGet();check("*".equals(exchange.getRequestHeaders().getFirst("If-None-Match")),"migration PUT cannot overwrite");if(objects.containsKey(key)){exchange.sendResponseHeaders(412,-1);return;}objects.put(key,exchange.getRequestBody().readAllBytes());exchange.sendResponseHeaders(200,-1);return;}
            heads.incrementAndGet();byte[] stored=objects.get(key);if(stored==null){exchange.sendResponseHeaders(404,-1);return;}
            exchange.getResponseHeaders().add("Content-Length",Integer.toString(stored.length));exchange.getResponseHeaders().add("x-amz-meta-sha256",descriptor.sha256());exchange.getResponseHeaders().add("x-amz-meta-platform",descriptor.platform().id());exchange.sendResponseHeaders(200,-1);
        }finally{exchange.close();}});server.start();
        try{
            Properties config=new Properties();config.setProperty("provider","s3");config.setProperty("endpoint","http://127.0.0.1:"+server.getAddress().getPort());config.setProperty("bucket","test-bucket");config.setProperty("region","us-east-1");config.setProperty("keyPrefix","roms");config.setProperty("accessKeyId","FAKE_TEST_KEY");config.setProperty("accessKeySecret","synthetic-test-secret");config.setProperty("addressingStyle","path");Path external=root.resolve("private.properties");try(Writer writer=Files.newBufferedWriter(external)){config.store(writer,"synthetic fixture only");}
            ContentMigration.main(new String[]{root.resolve("missing.properties").toString(),content.root().toString()});check(heads.get()==0&&puts.get()==0&&gets.get()==0,"plan performs no cloud request");
            String[] apply={external.toString(),content.root().toString(),"--apply"};ContentMigration.main(apply);check(puts.get()==1,"one streaming PUT");check(content.isRemote(descriptor.sha256()),"same world metadata records cloud object");check(content.hasLocal(descriptor.sha256()),"migration preserves local ROM");
            check(Arrays.equals(body,objects.values().iterator().next()),"cloud receives exact local bytes");check(content.usedBytes()==body.length,"local plus cloud counted once");
            ContentMigration.main(apply);check(puts.get()==1,"repeat reuses same hash without upload");check(gets.get()==0,"no full or partial object-body GET");check(Arrays.equals(body,Files.readAllBytes(rom)),"input remains unchanged");check(Arrays.equals(Arrays.copyOf(body,body.length/2),content.read(descriptor.sha256(),0,body.length/2)),"local read remains available after migration");
        }finally{server.stop(0);}
        System.out.println("MigrationTests: "+checks+" checks passed");
    }
}
