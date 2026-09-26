package com.bilicharge.archive;

final class MediaRows {
    private MediaRows() {}

    static final class Image {
        public String dynamicId;
        public String rpid;
        public int position;
        public String sourceUrl;
        public String ossKey;
        public String uploadStatus;
        public int attempts;
    }
}
