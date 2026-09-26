package com.bilicharge.archive;

final class MediaDownloadException extends Exception {
    private final boolean permanent;

    MediaDownloadException(String code, boolean permanent) {
        super(code);
        this.permanent = permanent;
    }

    boolean permanent() { return permanent; }
}
