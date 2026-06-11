package com.obsidian.obsidian.ml;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private final SearchService searchService;
    
    // Once FileRepository is introduced, inject it here for real I/O.
    // private final FileRepository fileRepository;

    public McpController(SearchService searchService) {
        this.searchService = searchService;
    }

    public static class McpRequest {
        private String tool;
        private Map<String, Object> parameters;

        public String getTool() {
            return tool;
        }

        public void setTool(String tool) {
            this.tool = tool;
        }

        public Map<String, Object> getParameters() {
            return parameters;
        }

        public void setParameters(Map<String, Object> parameters) {
            this.parameters = parameters;
        }
    }

    @PostMapping("/execute")
    public ResponseEntity<?> executeTool(@RequestBody McpRequest request, 
                                         @RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        
        // TODO: Validate apiKey against system properties

        try {
            switch (request.getTool()) {
                case "search_notes":
                    String query = (String) request.getParameters().get("query");
                    int limit = request.getParameters().containsKey("limit") 
                                    ? (Integer) request.getParameters().get("limit") : 10;
                    List<SearchResult> results = searchService.search(query, limit);
                    return ResponseEntity.ok(Map.of("results", results));

                case "get_note_content":
                    String notePath = (String) request.getParameters().get("note_path");
                    // TODO: Connect to FileRepository.getText(notePath)
                    return ResponseEntity.ok(Map.of("content", "STUB - Returns full raw content of: " + notePath));

                case "create_note":
                    String title = (String) request.getParameters().get("title");
                    String folderPath = (String) request.getParameters().get("folder_path");
                    String content = (String) request.getParameters().get("content");
                    // TODO: Connect to FileRepository.createNote()
                    return ResponseEntity.ok(Map.of(
                        "status", "success", 
                        "path", folderPath + "/" + title + ".md",
                        "message", "STUB - Created note in memory"
                    ));

                case "find_home_for_note":
                    String proposedTitle = (String) request.getParameters().get("proposed_title");
                    // TODO: Implement similarity search and cluster folders
                    return ResponseEntity.ok(Map.of(
                        "similar_notes", List.of(),
                        "suggested_folders", List.of("Inbox", "Ideas"),
                        "name_examples", List.of()
                    ));

                default:
                    return ResponseEntity.badRequest().body(Map.of("error", "Unknown tool: " + request.getTool()));
            }
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
