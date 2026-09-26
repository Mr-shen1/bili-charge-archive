package com.bilicharge.archive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class MediaJobRunner {
    private final MediaMapper mapper;
    private final BiliImageDownloader downloader;
    private final MediaStore store;
    private final boolean enabled;

    MediaJobRunner(MediaMapper mapper, BiliImageDownloader downloader, MediaStore store,
                   @Value("${app.media.worker-enabled:true}") boolean enabled) {
        this.mapper = mapper;
        this.downloader = downloader;
        this.store = store;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.media.poll-ms:5000}")
    void poll() {
        if (!enabled || !store.configured()) return;
        runDue();
    }

    void runDue() {
        for (MediaRows.Image job : mapper.dueDynamics()) process(job, false);
        for (MediaRows.Image job : mapper.dueComments()) process(job, true);
    }

    void process(MediaRows.Image job, boolean comment) {
        int claimed = comment ? mapper.claimComment(job) : mapper.claimDynamic(job);
        if (claimed != 1) return;
        int attempt = job.attempts + 1;
        try {
            BiliImageDownloader.Download download = downloader.fetch(job.sourceUrl);
            String key = objectKey(job, comment, download.extension());
            store.putPrivate(key, download.bytes(), download.contentType());
            if (comment) mapper.readyComment(job, attempt, key);
            else mapper.readyDynamic(job, attempt, key);
        } catch (MediaDownloadException error) {
            fail(job, comment, attempt, error.getMessage(), error.permanent());
        } catch (RuntimeException error) {
            // External exception text can contain source URLs or signed credentials.
            fail(job, comment, attempt, "MEDIA_STORE_ERROR", false);
        }
    }

    private void fail(MediaRows.Image job, boolean comment, int attempt, String reason, boolean permanent) {
        String status = permanent ? "UNAVAILABLE" : "RETRY";
        LocalDateTime next = permanent ? null : LocalDateTime.now(ZoneOffset.UTC).plusSeconds(backoff(attempt));
        if (comment) mapper.failComment(job, attempt, status, next, reason);
        else mapper.failDynamic(job, attempt, status, next, reason);
    }

    static long backoff(int attempt) {
        return Math.min(300, 5L * (1L << Math.min(6, Math.max(0, attempt - 1))));
    }

    private String objectKey(MediaRows.Image job, boolean comment, String extension) {
        String identity = (comment ? "comments/" + job.dynamicId + "/" + job.rpid
                : "dynamics/" + job.dynamicId) + "/" + job.position;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(job.sourceUrl.getBytes(StandardCharsets.UTF_8));
            return "bili-charge/" + identity + "/" + HexFormat.of().formatHex(digest) + "." + extension;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
