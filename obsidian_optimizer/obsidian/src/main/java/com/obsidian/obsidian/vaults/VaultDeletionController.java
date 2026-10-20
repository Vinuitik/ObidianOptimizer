package com.obsidian.obsidian.vaults;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** {@code POST /vaults/{id}/delete}: enqueue a user-requested deletion (202); see vaults/FLOWS.md. */
@RestController
@RequestMapping("/vaults")
public class VaultDeletionController {

    private final VaultDeletionRepository repo;

    public VaultDeletionController(VaultDeletionRepository repo) { this.repo = repo; }

    @PostMapping("/{id}/delete")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id) {
        return ResponseEntity.accepted().body(repo.enqueue(id));
    }

    @ExceptionHandler(VaultException.class)
    public ResponseEntity<Map<String, Object>> onVaultException(VaultException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }
}
