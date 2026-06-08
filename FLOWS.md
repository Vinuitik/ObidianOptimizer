# ObsidianOptimizer — Documentation Index

Spring Boot API + React frontend for browsing and reviewing an Obsidian vault.

---

## Find all project docs

```powershell
Get-ChildItem -Path . -Recurse -Filter "*.md" | Where-Object { $_.FullName -notlike "*node_modules*" -and $_.FullName -notlike "*target*" } | Select-Object -ExpandProperty FullName
```

---

## Docs

| File | Type | Covers |
|---|---|---|
| [Backend FLOWS](obsidian_optimizer/obsidian/src/main/java/com/obsidian/obsidian/FLOWS.md) | Flows | REST endpoints, auth, file CRUD, diff-patch, soft-delete, cache |
| [Frontend FLOWS](frontend/FLOWS.md) | Flows | User interactions, routing, CRUD, auth, wiki-link resolution |
| [Frontend DESIGN](frontend/DESIGN.md) | Reference | Design tokens, atoms/molecules, state shape, markdown rendering |
| [Host Wrapper FLOWS](host-wrapper/FLOWS.md) | Flows | Image processing service, Anthropic vision API |
| [ML Architecture](ML_ARCH.md) | Architecture | MCP server, pgvector search, CNN/VLM image pipeline [NOT IMPLEMENTED] |
| [Mobile Architecture](MOBILE_ARCH.md) | Architecture | Offline-first mobile app, sync engine [NOT IMPLEMENTED] |
