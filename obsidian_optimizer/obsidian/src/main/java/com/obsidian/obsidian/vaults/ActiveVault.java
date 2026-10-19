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

    /** Tests only. */
    void set(long id, Path root) { state.set(new State(id, root)); }
}
