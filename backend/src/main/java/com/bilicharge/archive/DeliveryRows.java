package com.bilicharge.archive;

import java.time.LocalDateTime;

final class DeliveryRows {
    private DeliveryRows() {}

    static final class Event {
        public long id;
        public String upUid;
        public String dynamicId;
        public String commentRpid;
        public String eventType;
        public boolean isUpComment;
        public String messageText;
        public String imageSourceUrls;
    }

    static final class Target {
        public long eventId;
        public String role;
        public Long groupId;
        public String groupNameSnapshot;
        public String status;
        public int attempts;
        public LocalDateTime nextRetryAt;
        public String leaseOwner;
        public LocalDateTime leaseUntil;
        public LocalDateTime sentAt;
    }
}
