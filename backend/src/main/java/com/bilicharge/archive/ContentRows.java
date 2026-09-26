package com.bilicharge.archive;

import java.time.LocalDateTime;

final class ContentRows {
    private ContentRows() {}

    static final class Up {
        public String uid;
        public String displayName;
        public String avatarUrl;
        public boolean enabled;
    }

    static final class Dynamic {
        public String dynamicId;
        public String upUid;
        public String upName;
        public String upAvatarUrl;
        public boolean upEnabled;
        public String title;
        public String text;
        public LocalDateTime publishedAt;
        public long storedCommentCount;
        public boolean sourceUnavailable;
    }

    static final class Comment {
        public String dynamicId;
        public String rpid;
        public String rootRpid;
        public String parentRpid;
        public String authorMid;
        public String authorName;
        public String authorAvatarUrl;
        public int authorLevel;
        public String text;
        public LocalDateTime publishedAt;
        public long likeCount;
        public long replyCount;
        public long storedReplyCount;
        public boolean isUp;
        public boolean sourceUnavailable;
    }

    static final class Image {
        public int position;
        public String status;
    }
}
