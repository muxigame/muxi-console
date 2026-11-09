package net.muxigame.console.domain.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** Original implementation of AWS's published S3 query authentication algorithm. */
final class S3V4Signer {
    // https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
    static ObjectStorage.Ticket sign(StorageConfig config, Instant now, int seconds, String method, String key,
                                     Map<String, String> requestHeaders, Map<String, String> query) {
        URI object = config.objectUri(key);
        TreeMap<String, String> headers = new TreeMap<>(requestHeaders);
        headers.put("host", Signing.host(object));
        String names = String.join(";", headers.keySet());
        String date = Signing.DATE.format(now), time = Signing.TIME.format(now);
        String scope = date + "/" + config.region + "/s3/aws4_request";
        TreeMap<String, String> parameters = new TreeMap<>(query);
        parameters.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        parameters.put("X-Amz-Credential", config.accessKeyId + "/" + scope);
        parameters.put("X-Amz-Date", time); parameters.put("X-Amz-Expires", Integer.toString(seconds));
        parameters.put("X-Amz-SignedHeaders", names);
        if (!config.sessionToken.isEmpty()) parameters.put("X-Amz-Security-Token", config.sessionToken);
        String canonical = method + "\n" + object.getRawPath() + "\n" + Signing.query(parameters, false) + "\n"
                + Signing.canonicalHeaders(headers) + "\n" + names + "\nUNSIGNED-PAYLOAD";
        String stringToSign = "AWS4-HMAC-SHA256\n" + time + "\n" + scope + "\n" + Signing.sha(canonical);
        byte[] derived = Signing.hmac(Signing.bytes("AWS4" + config.accessKeySecret), date);
        derived = Signing.hmac(derived, config.region); derived = Signing.hmac(derived, "s3");
        derived = Signing.hmac(derived, "aws4_request");
        parameters.put("X-Amz-Signature", Signing.hex(Signing.hmac(derived, stringToSign)));
        return new ObjectStorage.Ticket(method, URI.create(object + "?" + Signing.query(parameters, false)),
                requestHeaders, now.plusSeconds(seconds).toEpochMilli());
    }
}
