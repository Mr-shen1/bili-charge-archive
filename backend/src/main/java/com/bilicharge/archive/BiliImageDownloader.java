package com.bilicharge.archive;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class BiliImageDownloader {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String cookie;

    BiliImageDownloader(@Value("${app.bili.cookie:}") String cookie) {
        this.cookie = cookie;
    }

    record Download(byte[] bytes, String contentType, String extension) {}

    Download fetch(String source) throws MediaDownloadException {
        URI uri = checkedSource(source);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 BiliChargeArchive/1.0")
                .header("Referer", "https://www.bilibili.com/");
        if (!cookie.isBlank()) builder.header("Cookie", cookie);
        try {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (status == 410) throw new MediaDownloadException("SOURCE_GONE", true);
                // CDN 404 can be temporary; only an explicit Gone response ends retries.
                if (status == 404) throw new MediaDownloadException("SOURCE_HTTP_404", false);
                if (status != 200) throw new MediaDownloadException("SOURCE_HTTP_" + status, false);
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new MediaDownloadException("IMAGE_TOO_LARGE", false);
                return identify(bytes);
            }
        } catch (MediaDownloadException error) {
            throw error;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MediaDownloadException("SOURCE_NETWORK_ERROR", false);
        }
    }

    static URI checkedSource(String source) throws MediaDownloadException {
        try {
            URI uri = URI.create(source);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    || host == null || uri.getPort() != -1
                    || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new MediaDownloadException("SOURCE_URL_INVALID", true);
            String lower = host.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith(".hdslb.com") || lower.endsWith(".biliimg.com")))
                throw new MediaDownloadException("SOURCE_HOST_INVALID", true);
            // Historic Bilibili snapshots contain HTTP CDN URLs; fetch them over HTTPS.
            return "http".equalsIgnoreCase(scheme)
                    ? URI.create("https" + source.substring(source.indexOf(':'))) : uri;
        } catch (IllegalArgumentException error) {
            throw new MediaDownloadException("SOURCE_URL_INVALID", true);
        }
    }

    static Download identify(byte[] bytes) throws MediaDownloadException {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return new Download(bytes, "image/jpeg", "jpg");
        if (bytes.length >= 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4e && bytes[3] == 0x47)
            return new Download(bytes, "image/png", "png");
        if (bytes.length >= 6 && new String(bytes, 0, 3, java.nio.charset.StandardCharsets.US_ASCII).equals("GIF"))
            return new Download(bytes, "image/gif", "gif");
        if (bytes.length >= 12 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
            return new Download(bytes, "image/webp", "webp");
        if (bytes.length >= 12 && new String(bytes, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp")
                && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("avif"))
            return new Download(bytes, "image/avif", "avif");
        throw new MediaDownloadException("SOURCE_NOT_IMAGE", false);
    }
}
