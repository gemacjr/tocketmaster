package com.tocketmaster.search.api;

import com.tocketmaster.search.dto.SearchHit;
import com.tocketmaster.search.svc.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /**
     * GET /api/search?q=&category=&city=&page=&size=
     * Non-personalized -> safe to cache at the CDN with short TTLs.
     */
    @GetMapping
    public Map<String, Object> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {

        SearchService.SearchResult result = searchService.search(q, category, city, page, size);
        return Map.of(
                "hits", result.hits() == null ? List.<SearchHit>of() : result.hits(),
                "source", result.source(),
                "tookMs", result.tookMs());
    }
}
