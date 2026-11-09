package net.muxigame.console.domain.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** OSS V4 is not AWS SigV4: bucket URI, mandatory headers, empty queries and scope differ. */
final class OssV4Signer {
    // https://www.alibabacloud.com/help/en/oss/developer-reference/add-signatures-to-urls
    static ObjectStorage.Ticket sign(StorageConfig config, Instant now, int seconds, String method, String key,
                                     Map<String, String> requestHeaders, Map<String, String> query, Set<String> additional) {
        URI object = config.objectUri(key);
        TreeMap<String, String> available = new TreeMap<>(requestHeaders);
        available.put("host", Signing.host(object));
        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        TreeSet<String> extraNames = new TreeSet<>();
        available.forEach((name, value) -> {
            boolean mandatory = name.equals("content-type") || name.equals("content-md5") || name.startsWith("x-oss-");
            if (mandatory || additional.contains(name)) canonicalHeaders.put(name, value);
            if (!mandatory && additional.contains(name)) extraNames.add(name);
        });
        String names = String.join(";", extraNames);
        String date = Signing.DATE.format(now), time = Signing.TIME.format(now);
        String scope = date + "/" + config.region + "/oss/aliyun_v4_request";
        TreeMap<String, String> parameters = new TreeMap<>(query);
        parameters.put("x-oss-signature-version", "OSS4-HMAC-SHA256");
        parameters.put("x-oss-credential", config.accessKeyId + "/" + scope);
        parameters.put("x-oss-date", time); parameters.put("x-oss-expires", Integer.toString(seconds));
        if (!names.isEmpty()) parameters.put("x-oss-additional-headers", names);
        if (!config.sessionToken.isEmpty()) parameters.put("x-oss-security-token", config.sessionToken);
        String canonical = method + "\n/" + Signing.encode(config.bucket + "/" + key, true) + "\n"
                + Signing.query(parameters, true) + "\n" + Signing.canonicalHeaders(canonicalHeaders) + "\n"
                + names + "\nUNSIGNED-PAYLOAD";
        String stringToSign = "OSS4-HMAC-SHA256\n" + time + "\n" + scope + "\n" + Signing.sha(canonical);
        byte[] derived = Signing.hmac(Signing.bytes("aliyun_v4" + config.accessKeySecret), date);
        derived = Signing.hmac(derived, config.region); derived = Signing.hmac(derived, "oss");
        derived = Signing.hmac(derived, "aliyun_v4_request");
        parameters.put("x-oss-signature", Signing.hex(Signing.hmac(derived, stringToSign)));
        return new ObjectStorage.Ticket(method, URI.create(object + "?" + Signing.query(parameters, true)),
                requestHeaders, now.plusSeconds(seconds).toEpochMilli());
    }
}
