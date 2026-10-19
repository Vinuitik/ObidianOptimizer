package com.obsidian.obsidian.vaults;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Currently active vault, loaded from the DB. Switching logic arrives in a later milestone. */
@Component
@DependsOn("vaultRepository")
public class ActiveVault {

    private record State(long id, Path root) {}

    private final VaultRepository repo;
    private final AtomicReference<State> state = new AtomicReference<>();

    public ActiveVault(VaultRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void load() {
        Long id = repo.findActiveId();
        if (id == null) id = 1L;
        state.set(new State(id, Path.of(repo.containerPath(id))));
    }

    public long id() { return state.get().id(); }

    public Path root() { return state.get().root(); }

    /** Filesystem root of any vault: the active one without a DB hit, else its registered
     *  container path. For queue consumers finishing a job in the vault the job belongs to. */
    public Path rootFor(long vaultId) {
        State s = state.get();
        return s.id() == vaultId ? s.root() : Path.of(repo.containerPath(vaultId));
    }

    /** Tests only: a standalone instance pinned to (id, root), no DB behind it. */
    public static ActiveVault forTest(long id, Path root) {
        ActiveVault v = new ActiveVault(null);
        v.set(id, root);
        return v;
    }

    /** Tests only: pins the in-memory active vault (does not touch the DB flag). */
    public void set(long id, Path root) { state.set(new State(id, root)); }
}
