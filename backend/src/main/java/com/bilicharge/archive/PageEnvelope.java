package com.bilicharge.archive;

import java.util.List;

record PageEnvelope<T>(List<T> data, Page page) {
    static final int PAGE_SIZE = 20;

    record Page(int pageSize, String nextCursor, String prevCursor, boolean hasNext, boolean hasPrev) {}
}
