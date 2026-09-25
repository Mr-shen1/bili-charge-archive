package com.bilicharge.archive;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

record BatchRequest(List<Item> items, List<String> baselineCompletedDynamicIds,
                    List<AvailabilityChange> availabilityChanges) {
    record Item(Dynamic dynamic, List<Comment> comments, ScanState scanState,
                List<EventCandidate> eventCandidates) {}

    record Dynamic(String dynamicId, String upUid, String title, String text,
                   String publishedAt, String commentOid, Integer commentType,
                   List<String> images) {}

    record Comment(String rpid, String rootRpid, String parentRpid,
                   String authorMid, String authorName, String authorAvatarUrl,
                   Integer authorLevel, String text, String publishedAt,
                   Long likeCount, Long replyCount, List<String> images) {}

    record ScanState(JsonNode state, String lastCompleteScanAt, String lastFullScanAt,
                     String fullScanRetryAt) {}

    record EventCandidate(String dedupeKey, String type, String commentRpid,
                          String text, List<String> imageSourceUrls) {}

    record AvailabilityChange(String dynamicId, String rpid, Boolean unavailable) {}
}
