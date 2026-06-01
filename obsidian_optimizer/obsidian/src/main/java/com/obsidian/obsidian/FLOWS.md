# Backend Flows

Files: ObsidianApplication.java, MyController.java, FileRepository.java, ImageRepository.java, WebConfig.java, ServletInitializer.java

---

## Startup

`ObsidianApplication.main()` → `SpringApplication.run()` → beans init: `FileRepository`, `ImageRepository`, `MyController`, `WebConfig` → Tomcat starts on port 8082  
To change port: `application.properties → server.port`

---

## GET /names — All Note Paths

`MyController.getNames()` → `FileRepository.getNoteNames()` → check `cacheUpToDate` flag  
- Cache hit: return `cache` (ArrayList<String>)  
- Cache miss: BFS from `ROOT_PATH` (`C:\Users\ACER\Desktop\NewLife`), skip `.git` and `resources` dirs, collect all `.md` file absolute paths → sort → store in `cache`, set `cacheUpToDate=true` → return list  
To change vault root: `FileRepository` line 21 (hardcoded, no env var)

---

## GET /review — Notes Due for Review

`MyController.getReviewName()` → `FileRepository.getReviewNotes()` → check `cacheReviewUpToDate`  
- Cache miss: calls `getNoteNames()` first → for each path open `BufferedReader`, read line 1 (skip), read line 2 → parse date after `"reviewed: "` prefix → compare to today (`LocalDate.now()`) → if date ≤ today, add path to `reviewNames` → set `cacheReviewUpToDate=true`  
Format assumed on line 2: `reviewed: yyyy-MM-dd...`  
To change review format: `FileRepository.getReviewNotes()`

---

## GET /text?noteName={path} — Note Content

`MyController.getText(noteName)` → `FileRepository.getText(path)` → `Files.readString(Paths.get(path))` → returns raw markdown string as plain text response

---

## GET /images/{filename} — Image Serving

`ImageRepository.getImage(filename)` → `serveFile(IMAGE_DIR, filename)` → validate file exists under `C:\Users\ACER\Desktop\NewLife\resources\images\` → detect MIME type from extension → return `ResponseEntity<Resource>` with Content-Type header  
To change image dir: `ImageRepository` line 20 (hardcoded)

---

## Cache Invalidation

`FileRepository.invalidateCache()` sets `cacheUpToDate=false` and `cacheReviewUpToDate=false`  
No endpoint triggers this — invalidation is manual / restart-only [NOT IMPLEMENTED: no HTTP invalidation endpoint]

---

## Static Frontend Serving

`WebConfig.addResourceHandlers()` → maps `/static/**` → `classpath:/static/` with `no-store` cache  
`index.html` served at root by Spring Boot default

---

## Change Index

| Thing to change | Where |
|---|---|
| Vault root path | `FileRepository` line 21 |
| Image directory | `ImageRepository` line 20 |
| Server port | `application.properties` → `server.port` |
| Review date format | `FileRepository.getReviewNotes()` |
| Cache strategy | `FileRepository` fields: `cache`, `cacheUpToDate`, `cacheReview`, `cacheReviewUpToDate` |
| Static resource cache headers | `WebConfig.addResourceHandlers()` |
| New REST endpoint | `MyController` + matching `FileRepository` method |
