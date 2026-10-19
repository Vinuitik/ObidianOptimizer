package com.obsidian.obsidian.vaults;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Vault registry REST (nginx strips {@code /api}). Registration is M6 (needs the mount
 * override), so there is no POST /vaults yet.
 */
@RestController
@RequestMapping("/vaults")
public class VaultController {

    private final VaultRepository repo;
    private final ActiveVault activeVault;

    public VaultController(VaultRepository repo, ActiveVault activeVault) {
        this.repo = repo;
        this.activeVault = activeVault;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return repo.listAll();
    }

    @PutMapping("/active")
    public Map<String, Object> setActive(@RequestBody Map<String, Object> body) {
        Object id = body.get("id");
        if (!(id instanceof Number n)) throw new VaultException(400, "id required");
        activeVault.switchTo(n.longValue());
        return repo.getView(activeVault.id());
    }

    @PatchMapping("/{id}")
    public Map<String, Object> rename(@PathVariable long id, @RequestBody Map<String, Object> body) {
        Object name = body.get("name");
        if (!(name instanceof String s) || s.isBlank()) throw new VaultException(400, "name required");
        repo.rename(id, s.trim());
        return repo.getView(id);
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable long id) {
        repo.archive(id);
        return repo.getView(id);
    }

    @PostMapping("/{id}/restore")
    public Map<String, Object> restore(@PathVariable long id) {
        repo.restore(id);
        return repo.getView(id);
    }

    @ExceptionHandler(VaultException.class)
    public ResponseEntity<Map<String, Object>> onVaultException(VaultException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
