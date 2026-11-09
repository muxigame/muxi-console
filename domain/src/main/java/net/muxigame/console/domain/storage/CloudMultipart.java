package net.muxigame.console.domain.storage;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.muxigame.console.domain.Hashing;
import static net.muxigame.console.domain.storage.ObjectStorage.*;

/** AWS S3 and OSS multipart control plane. ROM bytes go directly from the client to UploadPart. */
final class CloudMultipart implements ObjectStorage.Multipart {
    private final CloudObjectStorage storage;
    private final StorageConfig.Provider provider;
    CloudMultipart(CloudObjectStorage storage, StorageConfig.Provider provider) { this.storage=storage; this.provider=provider; }
    @Override public Upload start(String hash, long size, String platform) throws IOException {
        // Validate the complete size/namespace before any remote side effect.
        Upload checked = new Upload("validation", hash, size, platform, PART_BYTES);
        String prefix = provider == StorageConfig.Provider.S3 ? "x-amz-meta-" : "x-oss-meta-";
        var headers = new HashMap<String,String>(); headers.put("content-type","application/octet-stream");
        headers.put(prefix+"sha256",checked.hash()); headers.put(prefix+"platform",checked.platform());
        if (provider == StorageConfig.Provider.OSS) headers.put("x-oss-forbid-overwrite","true");
        var response=storage.request(storage.ticket("POST",checked.hash(),headers,Map.of("uploads","")),HttpRequest.BodyPublishers.noBody());
        if(response.status()!=200)throw status("initiate",response.status());
        var root=MultipartXml.parse(response.body(),"InitiateMultipartUploadResult");
        try{return new Upload(MultipartXml.text(root,"UploadId"),checked.hash(),size,platform,PART_BYTES);}
        catch(RuntimeException invalid){throw new IOException("Invalid multipart initiation identity");}
    }
    @Override public Ticket partTicket(Upload upload,int number) {
        return storage.ticket("PUT",upload.hash(),Map.of("content-length",Long.toString(upload.partBytes(number))),
                Map.of("uploadId",upload.id(),"partNumber",Integer.toString(number)));
    }
    @Override public List<Part> list(Upload upload)throws IOException {
        var parts=new ArrayList<Part>();int marker=0;
        for(int page=0;page<11;page++){
            var query=new HashMap<String,String>();query.put("uploadId",upload.id());query.put("max-parts","1000");if(marker>0)query.put("part-number-marker",Integer.toString(marker));
            var response=storage.request(storage.ticket("GET",upload.hash(),Map.of(),query),HttpRequest.BodyPublishers.noBody());
            if(response.status()!=200)throw status("list parts",response.status());
            var root=MultipartXml.parse(response.body(),"ListPartsResult");
            if(!MultipartXml.text(root,"UploadId").equals(upload.id()))throw new IOException("Multipart list identity mismatch");
            var rows=MultipartXml.children(root,"Part");if(rows.size()>1000)throw new IOException("Oversize multipart page");
            try{for(var row:rows){Part part=new Part(Integer.parseInt(MultipartXml.text(row,"PartNumber")),Long.parseLong(MultipartXml.text(row,"Size")),MultipartXml.text(row,"ETag"));
                if(part.number()<=marker||part.number()>upload.partCount()||part.size()!=upload.partBytes(part.number()))throw new IOException("Invalid multipart part range");
                marker=part.number();parts.add(part);if(parts.size()>upload.partCount())throw new IOException("Too many multipart parts");}}
            catch(IllegalArgumentException invalid){throw new IOException("Invalid multipart part metadata");}
            String truncated=MultipartXml.text(root,"IsTruncated");if(truncated.equals("false"))return List.copyOf(parts);
            if(!truncated.equals("true")||rows.isEmpty())throw new IOException("Invalid multipart pagination");
            try{if(Integer.parseInt(MultipartXml.text(root,"NextPartNumberMarker"))!=marker)throw new IOException("Multipart pagination did not advance");}
            catch(NumberFormatException invalid){throw new IOException("Invalid multipart page marker");}
        }throw new IOException("Too many multipart pages");
    }
    @Override public Metadata complete(Upload upload,List<Part> receipts)throws IOException {
        List<Part> expected=validate(upload,receipts);
        var prior=storage.head(upload.hash());
        if(prior.isPresent()){same(upload,prior.get());abort(upload);return prior.get();}
        if(!list(upload).equals(expected))throw new IOException("Cloud parts differ from the original client receipts");
        var xml=new StringBuilder("<CompleteMultipartUpload>");
        for(Part part:expected)xml.append("<Part><PartNumber>").append(part.number()).append("</PartNumber><ETag>").append(MultipartXml.escape(part.etag())).append("</ETag></Part>");
        byte[] body=xml.append("</CompleteMultipartUpload>").toString().getBytes(StandardCharsets.UTF_8);
        if(body.length>CloudObjectStorage.MAX_CONTROL_BYTES)throw new IOException("Oversize multipart completion XML");
        Map<String,String> headers=provider==StorageConfig.Provider.S3?Map.of("content-type","application/xml","if-none-match","*"):
                Map.of("content-type","application/xml","x-oss-forbid-overwrite","true");
        var response=storage.request(storage.ticket("POST",upload.hash(),headers,Map.of("uploadId",upload.id())),HttpRequest.BodyPublishers.ofByteArray(body));
        if(response.status()==200)MultipartXml.parse(response.body(),"CompleteMultipartUploadResult");
        else if(response.status()!=409&&response.status()!=412)throw status("complete",response.status());
        Metadata result=storage.head(upload.hash()).orElseThrow(()->new IOException("Cloud multipart completion is not confirmed"));same(upload,result);
        // Concurrent identical content may win the conditional create; our unfinished parts still need deletion.
        if(response.status()!=200)abort(upload);
        return result;
    }
    static List<Part> validate(Upload upload,List<Part> receipts)throws IOException {
        if(receipts==null||receipts.size()!=upload.partCount())throw new IOException("Incomplete multipart receipts");
        List<Part> result=List.copyOf(receipts);
        for(int i=0;i<result.size();i++){Part part=result.get(i);if(part.number()!=i+1||part.size()!=upload.partBytes(i+1))throw new IOException("Multipart receipts are duplicated, unordered or incomplete");}
        return result;
    }
    private static void same(Upload upload,Metadata metadata)throws IOException {
        if(metadata.size()!=upload.size()||!metadata.sha256().equals(upload.hash())||!metadata.platform().equals(upload.platform()))throw new IOException("Existing cloud object conflicts with multipart content");
    }
    @Override public void abort(Upload upload)throws IOException {
        var response=storage.request(storage.ticket("DELETE",upload.hash(),Map.of(),Map.of("uploadId",upload.id())),HttpRequest.BodyPublishers.noBody());
        if(response.status()!=204&&response.status()!=200&&response.status()!=404)throw status("abort",response.status());
    }
    private static IOException status(String operation,int status){return new IOException("Object storage multipart "+operation+" returned HTTP "+status);}
}
