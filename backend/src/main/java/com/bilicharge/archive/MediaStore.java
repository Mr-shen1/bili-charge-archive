package com.bilicharge.archive;

import java.net.URI;
import java.time.Duration;

interface MediaStore {
    boolean configured();
    void putPrivate(String key, byte[] bytes, String contentType);
    URI signedGet(String key, Duration lifetime);
}
