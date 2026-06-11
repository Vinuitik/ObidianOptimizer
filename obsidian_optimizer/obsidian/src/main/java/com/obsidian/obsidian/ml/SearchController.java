package com.obsidian.obsidian.ml;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/search")
    public List<SearchResult> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        return searchService.search(q, Math.min(limit, 20));
    }
}
