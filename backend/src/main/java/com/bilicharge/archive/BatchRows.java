package com.bilicharge.archive;

import java.time.LocalDateTime;

final class BatchRows {
    private BatchRows() {}

    record Dynamic(String dynamicId, String upUid, String title, String contentText,
                   LocalDateTime publishedAt, String commentOid, int commentType,
                   LocalDateTime seenAt) {}

    record Comment(String dynamicId, String rpid, String rootRpid, String parentRpid,
                   String authorMid, String authorName, String authorAvatarUrl,
                   int authorLevel, String contentText, LocalDateTime publishedAt,
                   long likeCount, long replyCount, boolean up, LocalDateTime seenAt) {}

    record ScanState(String dynamicId, String stateJson, LocalDateTime lastCompleteScanAt,
                     LocalDateTime lastFullScanAt, LocalDateTime fullScanRetryAt) {}

    record Event(String dedupeKey, String upUid, String dynamicId, String commentRpid,
                 String eventType, boolean upComment, String messageText,
                 String imageSourceUrls, LocalDateTime readyAt) {}
}
