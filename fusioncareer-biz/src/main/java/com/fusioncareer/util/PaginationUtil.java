package com.fusioncareer.util;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

public final class PaginationUtil {

    private PaginationUtil() {
    }

    public static <T> Page<T> createPage(int readPage, int readSize) {
        return new Page<>(Math.max(readPage, 1), Math.min(Math.max(readSize, 1), 100));
    }
}
