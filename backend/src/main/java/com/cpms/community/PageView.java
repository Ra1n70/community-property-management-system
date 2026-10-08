package com.cpms.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.List;
import java.util.function.Function;

/** One page of a list: the rows plus enough totals for Previous/Next and "N results". */
public record PageView<T>(List<T> items, int page, int size, long total, int totalPages) {
    public static final int DEFAULT_SIZE = 20, MAX_SIZE = 100;

    public static PageRequest request(Integer page, Integer size, Sort sort) {
        int p = page == null ? 0 : page, s = size == null ? DEFAULT_SIZE : size;
        if (p < 0 || s < 1 || s > MAX_SIZE)
            throw AccountService.fail(org.springframework.http.HttpStatus.BAD_REQUEST, "Page must be 0 or more and size between 1 and " + MAX_SIZE + ".");
        return PageRequest.of(p, s, sort);
    }

    public static <E, T> PageView<T> of(Page<E> page, Function<E, T> view) {
        return new PageView<>(page.getContent().stream().map(view).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
