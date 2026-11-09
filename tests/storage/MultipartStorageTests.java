package net.muxigame.console.domain.storage;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.muxigame.console.domain.*;
import static net.muxigame.console.domain.storage.ObjectStorage.*;

/** Synthetic 9GiB control-plane metadata, plus real bounded loopback XML. No 9GiB ROM is sent. */
public final class MultipartStorageTests {
    private static int checks;
    private static final String HASH="a".repeat(64);
    private static final long SIZE=9L*1024*1024*1024;
    private static final UUID ACTOR=new UUID(0,1),OTHER=new UUID(0,2);
    public static void main(String[] args)throws Exception{
        plans();loopback();oss();transactions();
        System.out.println("Multipart storage: "+checks+" checks passed; 9GiB is logical metadata, no object-body GET");
    }
    private static void plans()throws Exception{
        var u=new Multipart.Upload("opaque+/=",HASH,SIZE,"wii",Multipart.PART_BYTES);
        eq(576,u.partCount(),"9GiB uses 576 bounded parts");eq(5_368_709_120L,u.offset(321),"long offset exceeds 5GB without int truncation");
        eq((long)Multipart.PART_BYTES,u.partBytes(576),"last exact part");
        var odd=new Multipart.Upload("id",HASH,MAX_SINGLE_PUT_BYTES+1,"wii",Multipart.PART_BYTES);
        eq(5_000_000_001L,odd.offset(odd.partCount())+odd.partBytes(odd.partCount()),"last nonaligned part ends at exact declared size");
        fails(()->u.partBytes(0),"zero part rejected");fails(()->u.offset(577),"out of bounds part rejected");
        fails(()->new Multipart.Upload("id",HASH,Long.MAX_VALUE,"wii",Multipart.PART_BYTES),"overflow-size plan rejected");
        fails(()->new Multipart.Part(1,1,"x\r\nHost: secret"),"header injection rejected");
        check(!u.toString().contains("opaque"),"upload identity not printed");
        var parts=receipts(u);var wrong=new ArrayList<>(parts);wrong.set(0,wrong.get(1));
        fails(()->CloudMultipart.validate(u,wrong),"duplicate receipts rejected");
        fails(()->CloudMultipart.validate(u,parts.subList(0,575)),"missing receipt rejected");
        fails(()->MultipartXml.parse("<!DOCTYPE x [<!ENTITY e SYSTEM 'http://127.0.0.1:1/private'>]><ListPartsResult>&e;</ListPartsResult>".getBytes(StandardCharsets.UTF_8),"ListPartsResult"),"DTD/XXE rejected before any external request");
    }
    private static void loopback()throws Exception{
        var service=new Service();service.http.start();
        try{
            var config=new StorageConfig(StorageConfig.Provider.S3,URI.create("http://127.0.0.1:"+service.http.getAddress().getPort()),"bucket","us-east-1","fixtures","synthetic","not-a-real-secret","",true,900);
            var storage=new CloudObjectStorage(config,Clock.systemUTC());var mp=storage.multipart().orElseThrow();
            fails(()->storage.upload(HASH,MAX_SINGLE_PUT_BYTES+1,"wii"),"old single PUT stays bounded");
            Multipart.Upload first=mp.start(HASH,SIZE,"wii"),other=mp.start(HASH,SIZE,"wii");
            check(service.starts==2&&service.metadata,"initiate sets declared hash/platform, no full-file checksum header");
            var ticket=mp.partTicket(first,321);eq(Long.toString(Multipart.PART_BYTES),ticket.headers().get("content-length"),"UploadPart signs exact length");
            eq("321",query(ticket.url()).get("partNumber"),"part identity signed in URL");
            check(!ticket.toString().contains("Signature"),"presigned URL not printed");
            var expected=receipts(first);eq(expected,mp.list(first),"bounded XML lists all 576 exact logical part receipts");
            var wrong=new ArrayList<>(expected);wrong.set(5,new Multipart.Part(6,Multipart.PART_BYTES,"\"wrong\""));
            fails(()->mp.complete(first,wrong),"client ETag mismatch cannot complete");eq(0,service.completes,"no remote merge after ETag mismatch");
            eq(new Metadata(SIZE,HASH,"wii"),mp.complete(first,expected),"conditional remote merge plus HEAD confirms object metadata");
            check(service.conditional&&service.completes==1,"S3 complete is signed If-None-Match star");
            eq(new Metadata(SIZE,HASH,"wii"),mp.complete(other,receipts(other)),"same-hash competitor reuses identical confirmed object");
            eq(1,service.completes,"same-hash competitor cannot overwrite");check(service.aborted.contains(other.id())&&!service.aborted.contains(first.id()),"only competitor's incomplete upload is aborted");
            service.exists=false;service.error200=true;
            fails(()->mp.complete(first,expected),"HTTP 200 containing Error is not successful completion");service.error200=false;
            service.oversize=true;fails(()->mp.list(first),"actual HTTP XML above 1MiB rejected with bounded subscriber");service.oversize=false;
            service.badPart=true;fails(()->mp.list(first),"cloud part size mismatch rejected");service.badPart=false;
            service.abortFailure=true;fails(()->mp.abort(first),"provider abort failure remains visible");service.abortFailure=false;mp.abort(first);mp.abort(first);
            check(service.objectGets==0&&service.maxRequest<CloudObjectStorage.MAX_CONTROL_BYTES,"no object-body GET and all control requests stay bounded");
        }finally{service.http.stop(0);}
    }
    private static void oss()throws Exception{
        var config=new StorageConfig(StorageConfig.Provider.OSS,URI.create("https://oss.example.test"),"bucket","test-region","fixtures","synthetic","not-a-real-secret","",false,900);
        var requests=new ArrayList<java.net.http.HttpRequest>();boolean[] exists={false};
        var storage=new CloudObjectStorage(config,Clock.systemUTC(),request->{
            requests.add(request);var q=query(request.uri());String method=request.method();
            if(method.equals("HEAD"))return exists[0]?new CloudObjectStorage.Response(200,headers(Map.of("content-length",Long.toString(SIZE),"x-oss-meta-sha256",HASH,"x-oss-meta-platform","wii"))):response(404,"");
            if(method.equals("POST")&&q.containsKey("uploads")){eq("true",request.headers().firstValue("x-oss-forbid-overwrite").orElse(""),"OSS initiate forbids overwrite");return response(200,"<InitiateMultipartUploadResult><UploadId>oss-id</UploadId></InitiateMultipartUploadResult>");}
            if(method.equals("GET"))return response(200,listXml("oss-id",false));
            if(method.equals("POST")){eq("true",request.headers().firstValue("x-oss-forbid-overwrite").orElse(""),"OSS complete forbids overwrite");exists[0]=true;return response(200,"<CompleteMultipartUploadResult/>");}
            return response(204,"");
        });
        var mp=storage.multipart().orElseThrow();var upload=mp.start(HASH,SIZE,"wii");mp.complete(upload,receipts(upload));
        check(requests.stream().filter(r->r.method().equals("POST")).allMatch(r->query(r.uri()).get("x-oss-additional-headers").contains("host")),"OSS signs control requests with distinct V4 scheme");
    }
    private static void transactions()throws Exception{
        Path root=Files.createTempDirectory(Path.of("build"),"multipart-domain-");
        try{
            var fake=new Fake();var store=new ContentStore(root);var uploads=new CloudUploads(store,fake);var request=new UploadManager.Request("original.iso",Platform.WII,SIZE,HASH,true);
            var status=uploads.begin(ACTOR,request,0);var plan=uploads.multipartPlan(ACTOR,status.id(),1).orElseThrow();
            eq(SIZE,plan.size(),"large transaction selects multipart, not impossible single PUT");fails(()->uploads.uploadTicket(ACTOR,status.id(),1),"single PUT cannot bypass multipart plan");
            fails(()->uploads.partTicket(OTHER,status.id(),1,1),"different actor cannot acquire part ticket");
            uploads.partTicket(ACTOR,status.id(),1,1);eq(1,fake.tickets,"one on-demand part ticket, no hundreds-URL message");
            fails(()->uploads.complete(OTHER,status.id(),receipts(plan),2),"different actor cannot merge");
            uploads.complete(ACTOR,status.id(),receipts(plan),2);check(!store.has(HASH),"merged metadata alone has not issued/registered content");uploads.finish(ACTOR,status.id(),3);
            check(store.isRemote(HASH)&&!store.hasLocal(HASH)&&uploads.activeCount()==0,"finish registers remote reference with no local ROM");eq(0,fake.aborts,"successful completed object is never aborted");
            var reused=uploads.begin(ACTOR,request,4);check(uploads.multipartPlan(ACTOR,reused.id(),5).isEmpty()&&reused.offset()==SIZE,"existing remote object needs no new multipart start");uploads.abort(ACTOR,reused.id());
            fake.remote.clear();var cancelled=uploads.begin(ACTOR,request,6);fake.abortFailure=true;fails(()->uploads.abort(ACTOR,cancelled.id()),"failed remote cancel not silently discarded");eq(1,uploads.activeCount(),"failed cancel retained for retry");fake.abortFailure=false;uploads.abort(ACTOR,cancelled.id());eq(0,uploads.activeCount(),"retry cleans exact transaction");
            var expired=uploads.begin(ACTOR,request,10);uploads.expire(10+CloudUploads.IDLE_TIMEOUT_MILLIS);eq(0,uploads.activeCount(),"expiry aborts incomplete cloud upload");
            var closed=uploads.begin(ACTOR,request,300_000);fake.abortFailure=true;fails(uploads::close,"close reports pending remote cleanup");eq(1,uploads.activeCount(),"failed shutdown abort not claimed removed");fake.abortFailure=false;uploads.close();eq(0,uploads.activeCount(),"idempotent close can retry failed remote abort");
            check(fake.aborts>=4,"cancel, expiry and shutdown all invoke provider AbortMultipart");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static List<Multipart.Part> receipts(Multipart.Upload u){var result=new ArrayList<Multipart.Part>();for(int n=1;n<=u.partCount();n++)result.add(new Multipart.Part(n,u.partBytes(n),"\"part-"+n+"\""));return result;}
    private static String listXml(String id,boolean bad){var s=new StringBuilder("<ListPartsResult><UploadId>").append(MultipartXml.escape(id)).append("</UploadId><IsTruncated>false</IsTruncated>");for(var p:receipts(new Multipart.Upload(id,HASH,SIZE,"wii",Multipart.PART_BYTES)))s.append("<Part><PartNumber>").append(p.number()).append("</PartNumber><Size>").append(bad&&p.number()==1?1:p.size()).append("</Size><ETag>").append(MultipartXml.escape(p.etag())).append("</ETag></Part>");return s.append("</ListPartsResult>").toString();}
    private static Map<String,String> query(URI uri){var result=new HashMap<String,String>();if(uri.getRawQuery()!=null)for(String piece:uri.getRawQuery().split("&")){String[] p=piece.split("=",2);result.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length==2?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return result;}
    private static HttpHeaders headers(Map<String,String> values){var map=new HashMap<String,List<String>>();values.forEach((k,v)->map.put(k,List.of(v)));return HttpHeaders.of(map,(a,b)->true);}
    private static CloudObjectStorage.Response response(int status,String xml){return new CloudObjectStorage.Response(status,headers(Map.of()),xml.getBytes(StandardCharsets.UTF_8));}
    private static final class Service{
        final HttpServer http;int starts,completes,objectGets,maxRequest;boolean exists,metadata,conditional,error200,oversize,badPart,abortFailure;final List<String> aborted=new ArrayList<>();
        Service()throws IOException{http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext("/",exchange->{try{
            var q=query(exchange.getRequestURI());String method=exchange.getRequestMethod(),xml="";int code=200;
            byte[] body=exchange.getRequestBody().readNBytes(CloudObjectStorage.MAX_CONTROL_BYTES+1);maxRequest=Math.max(maxRequest,body.length);
            if(method.equals("HEAD")){if(!exists)code=404;else{exchange.getResponseHeaders().add("Content-Length",Long.toString(SIZE));exchange.getResponseHeaders().add("x-amz-meta-sha256",HASH);exchange.getResponseHeaders().add("x-amz-meta-platform","wii");exchange.getResponseHeaders().add("x-amz-checksum-type","COMPOSITE");exchange.getResponseHeaders().add("x-amz-checksum-sha256","A".repeat(43)+"=-576");}}
            else if(method.equals("POST")&&q.containsKey("uploads")){starts++;metadata=HASH.equals(exchange.getRequestHeaders().getFirst("x-amz-meta-sha256"))&&"wii".equals(exchange.getRequestHeaders().getFirst("x-amz-meta-platform"))&&exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256")==null;xml="<InitiateMultipartUploadResult><UploadId>upload-"+starts+"</UploadId></InitiateMultipartUploadResult>";}
            else if(method.equals("GET")&&q.containsKey("uploadId"))xml=oversize?"x".repeat(CloudObjectStorage.MAX_CONTROL_BYTES+1):listXml(q.get("uploadId"),badPart);
            else if(method.equals("GET")){objectGets++;code=403;}
            else if(method.equals("POST")){completes++;conditional="*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"));if(error200)xml="<Error><Code>InternalError</Code></Error>";else{exists=true;xml="<CompleteMultipartUploadResult/>";}}
            else if(method.equals("DELETE")){if(abortFailure)code=503;else{aborted.add(q.get("uploadId"));code=204;}}
            else code=400;
            byte[] bytes=xml.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,method.equals("HEAD")||code==204?-1:bytes.length);if(!method.equals("HEAD")&&code!=204)exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});}
    }
    private static final class Fake implements ObjectStorage,Multipart{
        final Map<String,Metadata> remote=new HashMap<>();int tickets,aborts;boolean abortFailure;
        public Optional<Metadata> head(String hash){return Optional.ofNullable(remote.get(hash));}
        public Ticket upload(String h,long s,String p){throw new AssertionError("Large transaction used single PUT");}
        public Ticket download(String h){throw new AssertionError("No ROM download in this test");}
        public boolean putExisting(Path f,String h,String p){throw new AssertionError();}
        public Optional<Multipart> multipart(){return Optional.of(this);}
        public Upload start(String h,long s,String p){return new Upload(UUID.randomUUID().toString(),h,s,p,PART_BYTES);}
        public Ticket partTicket(Upload u,int n){tickets++;return new Ticket("PUT",URI.create("https://example.test/?uploadId="+u.id()+"&partNumber="+n),Map.of("content-length",Long.toString(u.partBytes(n))),Long.MAX_VALUE);}
        public List<Part> list(Upload u){return receipts(u);}
        public Metadata complete(Upload u,List<Part> p)throws IOException{CloudMultipart.validate(u,p);Metadata value=new Metadata(u.size(),u.hash(),u.platform());remote.put(u.hash(),value);return value;}
        public void abort(Upload u)throws IOException{aborts++;if(abortFailure)throw new IOException("Synthetic abort unavailable");}
    }
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private static void fails(Action action,String why)throws Exception{try{action.run();throw new AssertionError(why);}catch(IOException|IllegalArgumentException expected){checks++;}}
    private static void eq(Object expected,Object actual,String why){check(java.util.Objects.equals(expected,actual),why+" expected="+expected+" actual="+actual);}
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);checks++;}
}
