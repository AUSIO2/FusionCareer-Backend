package com.fusioncareer.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author Xiong heng
 * @param <T>
 */
@Data
@NoArgsConstructor
public class PageResult<T> implements Serializable {
    private List<T> list = new ArrayList<>();
    private long total;
    private int page;
    private int size;
    private int totalPages;

    public PageResult(long total, int page, int size) {
        this.total = total;
        this.page = page;
        this.size = size;
        this.totalPages = size == 0 ? 0:(int)Math.ceil((double)total/size);
    }

    public void addAll(Collection<? extends T> collection) {
        if(collection != null) {
            this.list.addAll(collection);
        }
    }
    public void add(T item) {
        if(item != null) {
            this.list.add(item);
        }
    }
}
