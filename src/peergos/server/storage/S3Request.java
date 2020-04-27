package peergos.server.storage;

import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

public class S3Request {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String UNSIGNED = "UNSIGNED-PAYLOAD";

    public final String verb, host;
    public final String key;
    public final String contentSha256;
    public final boolean allowPublicReads;
    public final String accessKeyId;
    public final String region;
    public final Map<String, String> extraHeaders;
    public final Instant date;

    public S3Request(String verb,
                     String host,
                     String key,
                     String contentSha256,
                     boolean allowPublicReads,
                     Map<String, String> extraHeaders,
                     String accessKeyId,
                     String region,
                     Instant date) {
        this.verb = verb;
        this.host = host;
        this.key = key;
        this.contentSha256 = contentSha256;
        this.allowPublicReads = allowPublicReads;
        this.extraHeaders = extraHeaders;
        this.accessKeyId = accessKeyId;
        this.region = region;
        this.date = date;
    }

    /**
     * Presign a url for a PUT
     *
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
     */
    public static PresignedUrl preSignPut(String key,
                                          int size,
                                          String contentSha256,
                                          boolean allowPublicReads,
                                          ZonedDateTime now,
                                          String verb,
                                          String host,
                                          Map<String, String> extraHeaders,
                                          String region,
                                          String accessKeyId,
                                          String s3SecretKey) {
        extraHeaders.put("Content-Length", "" + size);
        Instant timestamp = now.withNano(0).withZoneSameInstant(ZoneId.of("UTC")).toInstant();
        S3Request policy = new S3Request(verb, host, key, contentSha256, allowPublicReads, extraHeaders, accessKeyId,
                region, timestamp);

        String signature = computeSignature(policy, s3SecretKey);

        return new PresignedUrl("https://" + host + "/" + key, policy.getHeaders(signature));
    }

    /**
     * Presign a url for a GET
     *
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
     */
    public static PresignedUrl preSignGet(String key,
                                          ZonedDateTime now,
                                          String host,
                                          String region,
                                          String accessKeyId,
                                          String s3SecretKey) {
        S3Request policy = new S3Request("GET", host, key, UNSIGNED, false, Collections.emptyMap(),
                accessKeyId, region, now.withNano(0).withZoneSameInstant(ZoneId.of("UTC")).toInstant());

        String signature = computeSignature(policy, s3SecretKey);

        String query = policy.getQueryString(signature);
        return new PresignedUrl("https://" + host + "/" + key + query, policy.getHeaders(signature));
    }

    private static byte[] hmacSha256(byte[] secretKeyBytes, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HMACSHA256");
            SecretKey secretKey = new SecretKeySpec(secretKeyBytes, "HMACSHA256");
            mac.init(secretKey);
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] hmacSha256(String secretKey, byte[] message) {
        return hmacSha256(secretKey.getBytes(), message);
    }

    /**
     * Method for generating policy signature V4 for direct browser upload.
     *
     * @link https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html
     */
    public static String computeSignature(S3Request policy,
                                          String s3SecretKey) {
        String stringToSign = policy.stringToSign();
        String shortDate = S3Request.asAwsShortDate(policy.date);

        byte[] dateKey = hmacSha256("AWS4" + s3SecretKey, shortDate.getBytes());
        byte[] dateRegionKey = hmacSha256(dateKey, policy.region.getBytes());
        byte[] dateRegionServiceKey = hmacSha256(dateRegionKey, "s3".getBytes());
        byte[] signingKey = hmacSha256(dateRegionServiceKey, "aws4_request".getBytes());

        return ArrayOps.bytesToHex(hmacSha256(signingKey, stringToSign.getBytes()));
    }

    public String stringToSign() {
        StringBuilder res = new StringBuilder();
        res.append(ALGORITHM + "\n");
        res.append(asAwsDate(date) + "\n");
        res.append(scope() + "\n");
        res.append(ArrayOps.bytesToHex(Hash.sha256(toCanonicalRequest().getBytes())));
        return res.toString();
    }

    private static String urlEncode(String in) {
        try {
            return URLEncoder.encode(in, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    public String toCanonicalRequest() {
        StringBuilder res = new StringBuilder();
        res.append(verb + "\n");
        res.append("/" + key + "\n");

        res.append(getQueryParameters().entrySet()
                .stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&")));
        res.append("\n"); // no query parameters

        Map<String, String> headers = getCanonicalHeaders();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            res.append(e.getKey().toLowerCase() + ":" + e.getValue() + "\n");
        }
        res.append("\n");

        res.append(headersToSign() + "\n");
        res.append(contentSha256);
        return res.toString();
    }

    private Map<String, String> getHeaders(String signature) {
        Map<String, String> headers = getOriginalHeaders();
        if (isGet())
            return headers;
        headers.put("Authorization", ALGORITHM + " Credential=" + credential()
                + ",SignedHeaders=" + headersToSign() + ",Signature=" + signature);
        return headers;
    }

    private Map<String, String> getOriginalHeaders() {
        Map<String, String> res = new LinkedHashMap<>();
        res.put("Host", host);
        if (isGet())
            return res;
        res.put("x-amz-date", asAwsDate(date));
        res.put("x-amz-content-sha256", contentSha256);
        for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
            res.put(e.getKey(), e.getValue());
        }
        if (allowPublicReads)
            res.put("x-amz-acl", "public-read");
        return res;
    }

    private String getQueryString(String signature) {
        if (! isGet())
            return "";
        Map<String, String> res = getQueryParameters();
        res.put("x-amz-signature", signature);
        return "?" + res.entrySet()
                .stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private Map<String, String> getQueryParameters() {
        if (! isGet())
            return Collections.emptyMap();
        Map<String, String> res = new TreeMap<>();
        res.put("x-amz-algorithm", ALGORITHM);
        res.put("x-amz-content-sha256", contentSha256);
        res.put("x-amz-credential", credential());
        res.put("x-amz-date", asAwsDate(date));
        res.put("x-amz-signedheaders", "host");
        return res;
    }

    private SortedMap<String, String> getCanonicalHeaders() {
        SortedMap<String, String> res = new TreeMap<>();
        Map<String, String> originalHeaders = getOriginalHeaders();
        for (Map.Entry<String, String> e : originalHeaders.entrySet()) {
            res.put(e.getKey().toLowerCase(), e.getValue());
        }
        return res;
    }

    private String headersToSign() {
        return getCanonicalHeaders().keySet()
                .stream()
                .sorted()
                .collect(Collectors.joining(";"));
    }

    private String scope() {
        return String.format(
                "%s/%s/%s/%s",
                asAwsShortDate(date),
                region,
                "s3",
                "aws4_request");
    }

    private String credential() {
        return String.format(
                "%s/%s/%s/%s/%s",
                accessKeyId,
                asAwsShortDate(date),
                region,
                "s3",
                "aws4_request"
        );
    }

    public boolean isGet() {
        return "GET".equals(verb);
    }

    private static String asAwsDate(Instant instant) {
        return instant.toString()
                .replaceAll("[:\\-]|\\.\\d{3}", "");
    }

    private static String asAwsShortDate(Instant instant) {
        return asAwsDate(instant).substring(0, 8);
    }
}
