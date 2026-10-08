package com.cpms.community.search;

import com.cpms.community.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.Set;

@RestController
@RequestMapping("/api/search")
public class SearchApi {
    private final SearchService search;

    public SearchApi(SearchService search) { this.search = search; }

    /** q: every word must match; type: comma-separated types (default all); sort: latest or relevance. */
    @GetMapping
    public SearchService.Page search(Authentication auth, @RequestParam(defaultValue = "") String q, @RequestParam(required = false) String type,
                                     @RequestParam(defaultValue = "latest") String sort, @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size) {
        if (q.length() > 200 || page < 0 || size < 1 || size > 50 || !Set.of("latest", "relevance").contains(sort))
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "Invalid search query, sort or pagination.");
        return search.search(auth.getName(), q, SearchService.types(type), sort.equals("relevance"), page, size);
    }

    @GetMapping("/{type}/{id}")
    public SearchService.Result detail(Authentication auth, @PathVariable String type, @PathVariable Long id) {
        String value = type.toUpperCase(Locale.ROOT);
        SearchService.types(value);
        return search.detail(auth.getName(), value, id);
    }
}
