# Frontend Flows

Files: app.js, fetchUtils.js, folderStructure.js, notesReview.js, renderMarkdown.js, nameMapSingleton.js, leafNodeSingleton.js, linkProcessing.js, index.html

---

## Initialization

`index.html` loads → CDN `markdown-it` script → all JS modules → `app.js` `DOMContentLoaded` fires  
→ parallel: `fetchNoteNames()` + `populateReviewNotes()`

---

## Left Panel: Folder Tree

`app.js` → `fetchNoteNames()` [fetchUtils.js] → `GET /names` → returns `string[]` of absolute paths  
→ `buildFolderTree(paths)` [folderStructure.js]:  
  - Splits each path on `\` → builds nested object tree  
  - Renders folders as collapsible `<details>` / `<summary>`, files as `<span>` with click handler  
  - File click → `fetchNoteContent(fullPath)` → `renderMarkdown(content)` → inject into `#markdown-output`  
To change tree styling: `folderStructure.js` DOM building section + `styles.css`

---

## Right Panel: Review Notes

`app.js` → `populateReviewNotes()` [notesReview.js] → `GET /review` → returns `string[]` of paths  
→ strip path prefix and `.md` extension for display name  
→ store `shortName → fullPath` in `NameMapSingleton.getInstance()` [nameMapSingleton.js]  
→ render `<li>` items in `#notes-to-review`  
→ click → `switchNote(shortName)` → lookup fullPath from `NameMapSingleton` → `fetchNoteContent()` → render  
To change display name format: `notesReview.js` name-trimming logic

---

## Center Panel: Markdown Rendering

`fetchNoteContent(fullPath)` [fetchUtils.js] → `GET /text?noteName={encodedPath}` → raw markdown string  
→ `renderMarkdown(content)` [renderMarkdown.js]:  
  1. Replace `![[image.png]]` → `<img src="/images/image.png">`  
  2. Replace `[[link text]]` → `<a href="#">link text</a>` (links are non-navigating)  
  3. `markdownIt.render(processed)` → HTML string  
  4. Inject into `#markdown-output`  
Image `src="/images/..."` triggers browser → `GET /images/{filename}` → `ImageRepository` (backend)  
To change Obsidian syntax handling: `renderMarkdown.js` regex replacements

---

## NameMapSingleton

`NameMapSingleton.getInstance()` returns single shared instance across modules  
Holds `Map<shortName, fullPath>` — populated by `notesReview.js`, read by click handlers  
`LeafNodeSingleton` — defined but unused [NOT IMPLEMENTED]  
`linkProcessing.js` — window load handler stub, currently no-op [NOT IMPLEMENTED]

---

## Change Index

| Thing to change | Where |
|---|---|
| API base URL / port | `fetchUtils.js` fetch calls (hardcoded to same origin) |
| Folder tree DOM structure | `folderStructure.js` |
| Review list display name format | `notesReview.js` |
| Obsidian `[[link]]` / `![[img]]` syntax | `renderMarkdown.js` regex replacements |
| Markdown renderer library | `renderMarkdown.js` + CDN script tag in `index.html` |
| Panel layout | `index.html` + `styles.css` |
| Name↔path mapping | `nameMapSingleton.js` |
