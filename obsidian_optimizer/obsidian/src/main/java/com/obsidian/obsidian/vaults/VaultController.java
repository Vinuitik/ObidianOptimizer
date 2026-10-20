package com.obsidian.obsidian.vaults;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Vault registry REST (nginx strips {@code /api}). {@code POST /vaults} registers a new
 * (inactive) vault and asks the host-wrapper to mount it (M6).
 */
@RestController
@RequestMapping("/vaults")
public class VaultController {

    private final VaultRepository repo;
    private final ActiveVault activeVault;
    private final VaultWrapperClient wrapper;

    public VaultController(VaultRepository repo, ActiveVault activeVault, VaultWrapperClient wrapper) {
        this.repo = repo;
        this.activeVault = activeVault;
        this.wrapper = wrapper;
    }

    /** Insert inactive row (container_path /vaults/&lt;id&gt;) -> wrapper mounts it -> 202. A wrapper
     *  rejection or outage removes the row again and relays 400/409 (else 502/503). */
    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        if (!(body.get("name") instanceof String name) || name.isBlank()) throw new VaultException(400, "name required");
        if (!(body.get("hostPath") instanceof String host) || host.isBlank()) throw new VaultException(400, "hostPath required");
        long id = repo.register(name.trim(), host.trim());
        VaultWrapperClient.Result r;
        try {
            r = wrapper.register(id, host.trim());
        } catch (RuntimeException e) {
            repo.deleteRegistration(id);
            throw e;
        }
        if (r.status() != 202 && r.status() != 200) {
            repo.deleteRegistration(id);
            int st = r.status() == 400 || r.status() == 409 ? r.status() : 502;
            throw new VaultException(st, "host helper: " + r.body());
        }
        return ResponseEntity.accepted().body(repo.getView(id));
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
