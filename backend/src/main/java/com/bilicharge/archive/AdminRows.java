package com.bilicharge.archive;

import java.time.LocalDateTime;

final class AdminRows {
    private AdminRows() {}

    static final class Group {
        public Long id;
        public String name;
        public String webhookCiphertext;
    }

    static final class Up {
        public String uid;
        public String displayName;
        public String avatarUrl;
        public boolean enabled;
        public Long opsGroupId;
        public Long defaultAllGroupId;
        public Long defaultUpGroupId;
        public LocalDateTime workerHeartbeatAt;
        public LocalDateTime lastScanStartedAt;
        public LocalDateTime lastScanSucceededAt;
        public LocalDateTime lastScanErrorAt;
        public String lastScanError;
    }

    static final class Route {
        public String dynamicId;
        public String upUid;
        public Long allGroupId;
        public Long upGroupId;
    }
}
