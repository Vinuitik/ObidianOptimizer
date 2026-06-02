# Frontend Flows

Files: main.jsx, App.jsx, pages/MainPage.jsx, store/useStore.js, api/notes.js, utils/markdown.js

---

## Startup

`main.jsx` → `createRoot` → `<App>` → `<MainPage>`  
`MainPage` `useEffect` → parallel: `fetchNoteNames()` + `fetchReviewNotes()` → store updated → components re-render

---

## State (Zustand — useStore.js)

Global store holds:
- `tree` — nested tree object `{ type: 'folder'|'file', children?: {}, fullPath?: string }`
- `reviewNotes` — `[{ shortName, fullPath }]`
- `currentNoteHtml` — rendered HTML string
- `currentNotePath` — active note full path
- `leftCollapsed`, `rightCollapsed` — panel UI state

Actions: `fetchNoteNames()`, `fetchReviewNotes()`, `openNote(fullPath)`, `toggleLeft()`, `toggleRight()`  
To add global state: add field + action to `useStore.js`

---

## API Layer (api/notes.js)

All calls prefixed `/api/` — Vite dev proxy strips it → `localhost:8082`, Nginx proxy does same in prod.

`fetchNames()` → `GET /api/names` → `string[]` of absolute paths  
`fetchReview()` → `GET /api/review` → `string[]` of absolute paths  
`fetchNoteContent(fullPath)` → `GET /api/text?noteName={encoded}` → raw markdown string  
Images: `<img src="/api/images/filename">` — browser fetches directly, proxied by Nginx/Vite

To change API base URL: `api/notes.js` `BASE` constant

---

## Tree Building (useStore.js → buildTree)

`buildTree(paths)`:
1. Split each path on `/` or `\`, strip common prefix (vault root) automatically
2. Build nested `{ type: 'folder', children: {} }` / `{ type: 'file', fullPath }` structure
3. No hardcoded paths — prefix derived dynamically from all paths

To change prefix stripping: `buildTree()` `prefixLen` logic in `useStore.js`

---

## Component Flow (Atomic Design)

```
SplitLayout (template)
├── PanelHeader (molecule) + FolderTree (organism)   ← left panel
│   └── TreeNode (internal) → NavItem (molecule)
├── NoteViewer (organism)                            ← center
└── PanelHeader (molecule) + ReviewList (organism)   ← right panel
```

`FolderTree` → reads `tree` from store → renders `TreeNode` recursively  
`TreeNode` click (file) → `openNote(fullPath)` → `fetchNoteContent` → `renderMarkdown` → store `currentNoteHtml`  
`ReviewList` → reads `reviewNotes` from store → click → same `openNote` flow  
`NoteViewer` → reads `currentNoteHtml` → `dangerouslySetInnerHTML`

---

## Markdown Rendering (utils/markdown.js)

`renderMarkdown(content)`:
1. Replace `![[image.png]]` → `<img src="/api/images/image.png" class="embedded-image">`
2. Replace `[[link text]]` → plain `link text` (links non-navigating)
3. `markdown-it.render()` → HTML string

To change Obsidian syntax: `utils/markdown.js` regex replacements

---

## Design System (styles/globals.css)

CSS variables: `--bg`, `--surface`, `--border`, `--text`, `--muted`, `--accent`  
Font: Inter (body) + JetBrains Mono (code/filenames)  
Spacing: `--space-1` (4px) through `--space-6` (48px)  
Markdown styles: `.markdown-body` class on `NoteViewer`  
No gradients, no box shadows, transitions 150ms ease only.

---

## Infrastructure

`docker-compose.yml` (project root) → single `frontend` service  
`frontend/Dockerfile` — multi-stage: Node 20 builds `npm run build` → Nginx alpine serves `dist/`  
`frontend/nginx.conf` — `/api/*` → `host.docker.internal:8082`, `/` → SPA with `try_files`  
`start.ps1` — opens Java backend in new PowerShell window, runs `docker compose up --build` in current terminal

Ports:
- `http://localhost:8083` — React app via Nginx (Docker)
- `http://localhost:8082` — Java backend direct (also serves old static UI as fallback)

Dev mode (no Docker): `cd frontend && npm run dev` — Vite proxy handles `/api/*` → `localhost:8082`

---

## Change Index

| Thing to change | Where |
|---|---|
| API base URL | `api/notes.js` `BASE` constant |
| Vault path prefix stripping | `useStore.js` `buildTree()` |
| Obsidian `[[link]]` / `![[img]]` syntax | `utils/markdown.js` |
| Design tokens (colours, spacing) | `styles/globals.css` `:root` |
| Markdown typography | `styles/globals.css` `.markdown-body` rules |
| Panel widths | `components/templates/SplitLayout.module.css` |
| Nginx proxy target | `frontend/nginx.conf` `proxy_pass` |
| Docker exposed port | `docker-compose.yml` `ports: 8083:80` |
| Dev proxy target | `vite.config.js` `server.proxy` |
| Add new global state | `store/useStore.js` |
| Add new atom/molecule/organism | `components/{atoms,molecules,organisms}/` |
