package com.dfc.mosquittoui.backup.target;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dfc.mosquittoui.backup.target.TargetStorage.checkName;

/**
 * S3-compatible storage (AWS S3, MinIO, Hetzner, Wasabi, Backblaze, ...) via the REST API, signed with AWS Signature
 * Version 4. An object only appears once it is completely uploaded, so no temporary name is needed. Large files are
 * uploaded in parts (multipart upload).
 */
final class S3Storage implements TargetStorage {

    /** Files above this size go up in parts; a single PUT is limited to 5 GB by S3 */
    static final long PART_SIZE = 32L << 20;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final String EMPTY_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final long partSize;
    private final String scheme;
    private final String host;
    private final String basePath;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String prefix;
    private volatile CompletableFuture<?> running;
    private volatile boolean closed;

    S3Storage(Settings settings) throws Exception {
        this(settings, PART_SIZE);
    }

    S3Storage(Settings settings, long partSize) throws Exception {
        if (blank(settings.host()) || blank(settings.bucket()) || blank(settings.username()) || blank(settings.password())) {
            throw new IOException("An S3 target needs endpoint, bucket, access key and secret key");
        }
        this.partSize = partSize;
        scheme = settings.tls() ? "https" : "http";
        String endpoint = settings.host().strip().replaceFirst("^https?://", "").replaceFirst("/+$", "");
        boolean defaultPort = settings.port() <= 0 || settings.port() == (settings.tls() ? 443 : 80);
        // AWS expects the bucket in the host name, other providers (MinIO, ...) reliably accept it in the path
        boolean virtualHosted = endpoint.endsWith("amazonaws.com");
        host = (virtualHosted ? settings.bucket().strip() + "." : "") + endpoint + (defaultPort ? "" : ":" + settings.port());
        basePath = virtualHosted ? "/" : "/" + encode(settings.bucket().strip()) + "/";
        region = blank(settings.region()) ? "us-east-1" : settings.region().strip();
        accessKey = settings.username().strip();
        secretKey = settings.password();
        String p = settings.path() == null ? "" : settings.path().strip().replaceAll("^/+|/+$", "");
        prefix = p.isEmpty() ? "" : p + "/";
        // Check credentials and bucket right away, not only when uploading
        list(1);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public void upload(Path file, String name) throws Exception {
        String object = objectPath(name);
        long size = Files.size(file);
        if (size <= partSize) {
            sendForString("PUT", object, Map.of(), sha256(file), HttpRequest.BodyPublishers.ofFile(file), false);
            return;
        }
        String uploadId = element(sendForString("POST", object, Map.of("uploads", ""), EMPTY_HASH,
                HttpRequest.BodyPublishers.noBody(), false).body(), "UploadId");
        if (uploadId == null) {
            throw new IOException("S3 did not return an upload ID for the multipart upload");
        }
        try {
            StringBuilder complete = new StringBuilder("<CompleteMultipartUpload>");
            try (InputStream in = Files.newInputStream(file)) {
                for (int number = 1; ; number++) {
                    byte[] part = in.readNBytes((int) partSize);
                    if (part.length == 0) {
                        break;
                    }
                    Reply response = sendForString("PUT", object,
                            Map.of("partNumber", String.valueOf(number), "uploadId", uploadId),
                            hex(sha256().digest(part)), HttpRequest.BodyPublishers.ofByteArray(part), false);
                    String etag = response.headers().firstValue("ETag")
                            .orElseThrow(() -> new IOException("S3 returned no checksum (ETag) for a part"));
                    complete.append("<Part><PartNumber>").append(number).append("</PartNumber><ETag>").append(etag)
                            .append("</ETag></Part>");
                }
            }
            byte[] xml = complete.append("</CompleteMultipartUpload>").toString().getBytes(StandardCharsets.UTF_8);
            String response = sendForString("POST", object, Map.of("uploadId", uploadId), hex(sha256().digest(xml)),
                    HttpRequest.BodyPublishers.ofByteArray(xml), false).body();
            // S3 reports errors while assembling the parts with status 200 as well
            if (response.contains("<Error>")) {
                throw new IOException("S3: " + element(response, "Message"));
            }
        } catch (Exception e) {
            // Release the uploaded parts, otherwise they occupy storage invisibly
            try {
                sendForString("DELETE", object, Map.of("uploadId", uploadId), EMPTY_HASH, HttpRequest.BodyPublishers.noBody(), true);
            } catch (Exception cleanup) {
                e.addSuppressed(cleanup);
            }
            // A part still in flight when the upload was aborted may arrive after the release, so release a second time
            try {
                Thread.sleep(1500);
                sendForString("DELETE", object, Map.of("uploadId", uploadId), EMPTY_HASH, HttpRequest.BodyPublishers.noBody(), true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception alreadyReleased) {
                // NoSuchUpload
            }
            throw e;
        }
    }

    @Override
    public List<FileEntry> entries() throws Exception {
        return list(1000);
    }

    /** Objects directly under the prefix (no "subfolders"), names without the prefix */
    private List<FileEntry> list(int pageSize) throws Exception {
        List<FileEntry> result = new ArrayList<>();
        String token = null;
        do {
            Map<String, String> query = new TreeMap<>(Map.of("list-type", "2", "delimiter", "/",
                    "max-keys", String.valueOf(pageSize), "prefix", prefix));
            if (token != null) {
                query.put("continuation-token", token);
            }
            String xml = sendForString("GET", basePath, query, EMPTY_HASH, HttpRequest.BodyPublishers.noBody(), false).body();
            Matcher m = Pattern.compile("<Contents>(.*?)</Contents>", Pattern.DOTALL).matcher(xml);
            while (m.find()) {
                String key = element(m.group(1), "Key");
                String name = key != null && key.startsWith(prefix) ? key.substring(prefix.length()) : "";
                if (!name.isEmpty()) {
                    String size = element(m.group(1), "Size");
                    String modified = element(m.group(1), "LastModified");
                    result.add(new FileEntry(name, size == null ? null : Long.valueOf(size), modified == null ? null : Instant.parse(modified)));
                }
            }
            token = xml.contains("<IsTruncated>true</IsTruncated>") && pageSize > 1 ? element(xml, "NextContinuationToken") : null;
        } while (token != null);
        return result;
    }

    @Override
    public void download(String name, Path destination) throws Exception {
        HttpResponse<InputStream> response = send("GET", objectPath(name), Map.of(), EMPTY_HASH,
                HttpRequest.BodyPublishers.noBody(), false);
        try (InputStream in = response.body()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void delete(String name) throws Exception {
        sendForString("DELETE", objectPath(name), Map.of(), EMPTY_HASH, HttpRequest.BodyPublishers.noBody(), false);
    }

    /** Also aborts a running transfer; upload() then still releases the parts already uploaded */
    @Override
    public void close() {
        closed = true;
        CompletableFuture<?> f = running;
        if (f != null) {
            f.cancel(true);
        }
    }

    private String objectPath(String name) {
        return basePath + encodePath(prefix + checkName(name));
    }

    // ---------- Signed requests (AWS Signature Version 4) ----------

    private Reply sendForString(String method, String path, Map<String, String> query, String payloadHash,
                                               HttpRequest.BodyPublisher body, boolean cleanup) throws Exception {
        HttpResponse<InputStream> response = send(method, path, query, payloadHash, body, cleanup);
        String text;
        try (InputStream in = response.body()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Reply(text, response.headers());
    }

    /**
     * @param cleanup send even after close() (releasing the parts of an aborted multipart upload)
     * @throws IOException if S3 answers with an error status
     */
    private HttpResponse<InputStream> send(String method, String path, Map<String, String> query, String payloadHash,
                                           HttpRequest.BodyPublisher body, boolean cleanup) throws Exception {
        if (closed && !cleanup) {
            throw new IOException("The connection to the S3 target is closed");
        }
        String timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(TIMESTAMP);
        String date = timestamp.substring(0, 8);
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(query).entrySet()) {
            q.append(q.isEmpty() ? "" : "&").append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        String canonical = method + "\n" + path + "\n" + q + "\nhost:" + host + "\nx-amz-content-sha256:" + payloadHash
                + "\nx-amz-date:" + timestamp + "\n\nhost;x-amz-content-sha256;x-amz-date\n" + payloadHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n"
                + hex(sha256().digest(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date), region), "s3"),
                "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));

        HttpRequest request = HttpRequest.newBuilder(URI.create(scheme + "://" + host + path + (q.isEmpty() ? "" : "?" + q)))
                .method(method, body)
                .header("x-amz-date", timestamp)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                        + ", SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=" + signature)
                .build();
        CompletableFuture<HttpResponse<InputStream>> f = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        running = f;
        HttpResponse<InputStream> response;
        try {
            response = f.get();
        } catch (CancellationException e) {
            throw new IOException("Transfer to the S3 target aborted", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } finally {
            running = null;
        }
        if (response.statusCode() / 100 != 2) {
            String error;
            try (InputStream in = response.body()) {
                error = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String code = element(error, "Code");
            String message = element(error, "Message");
            throw new IOException("S3 responds with " + response.statusCode() + (code == null ? "" : " " + code)
                    + (message == null ? "" : ": " + message));
        }
        return response;
    }

    /** A response whose body has already been read as text */
    private record Reply(String body, HttpHeaders headers) {
    }

    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() throws Exception {
        return MessageDigest.getInstance("SHA-256");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }

    /** RFC 3986 encoding as required by the signature */
    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/", -1)) {
            sb.append(sb.isEmpty() ? "" : "/").append(encode(part));
        }
        return sb.toString();
    }

    private static String element(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml == null ? "" : xml);
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#34;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&");
    }
}
