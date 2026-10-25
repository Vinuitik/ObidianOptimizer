package com.obsidian.obsidian.bin;

import com.obsidian.obsidian.bin.BinService.BinItem;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unified Bin API (mounted under /api by the proxy like every controller). See bin/FLOWS.md. */
@RestController
@RequestMapping("bin")
public class BinController {

    private final BinService service;

    public BinController(BinService service) { this.service = service; }

    /** {note:[],folder:[],file:[],feed:[],track:[],track_item:[],subscription:[],unknown:[]} — items {kind,id,label,originalPath?,deletedAt}. */
    @GetMapping
    public Map<String, List<BinItem>> list() { return service.list(); }

    /** id is accepted as a JSON string or number. */
    public record RestoreRequest(String kind, Object id) {}

    @PostMapping("restore")
    public ResponseEntity<Map<String, Object>> restore(@RequestBody RestoreRequest req) {
        if (req.kind() == null || req.id() == null) return ResponseEntity.badRequest().build();
        String restored = service.restore(req.kind(), String.valueOf(req.id()));
        Map<String, Object> body = new HashMap<>();
        body.put("restored", true);
        if (restored != null) body.put("path", restored);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("{kind}/{id:.+}")
    public ResponseEntity<Void> purge(@PathVariable String kind, @PathVariable String id) {
        service.purge(kind, id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public Map<String, Object> emptyAll() { return Map.of("removed", service.emptyAll()); }
}
