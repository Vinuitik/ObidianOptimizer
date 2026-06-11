# Config Domain Flows

Files: SecurityConfig.java, WebConfig.java, ObsidianApplication.java, ServletInitializer.java

---

## Auth (SecurityConfig)

Spring Security session-based single-user auth.  
Credentials come from env vars `APP_AUTH_USERNAME` / `APP_AUTH_PASSWORD` (set in `.env`, passed via `docker-compose.yml`). `application.properties` only holds placeholders with throwaway local defaults — never put real credentials there (BCrypt is applied at startup).

`POST /login` (form-encoded: `username=&password=`) → Spring Security → session cookie → 200  
`POST /logout` → session invalidated → 200  
`GET /me` → 200 + username if authenticated, 401 if not

Only `/login` and `/logout` are public — **everything else requires session auth**
(`GET /settings` was public until the security-hardening pass; it leaked the vault path).  
To add/remove protected endpoints: `SecurityConfig.filterChain()` `authorizeHttpRequests`

CSRF disabled — app is session-cookie-based, not token-based. Enabling CSRF requires adding a token to every mutating frontend request.

`server.forward-headers-strategy=framework` — Spring honors `X-Forwarded-Proto: https`
from the nginx TLS edge, so the session cookie is marked `Secure` behind the proxy.

---

## CORS (WebConfig)

`WebConfig.addCorsMappings()` allows frontend origins (dev: `localhost:5173`, prod: Nginx container).  
To add an allowed origin: `WebConfig.addCorsMappings()`

---

## Application Entry Points

`ObsidianApplication` — main class. Annotations:
- `@SpringBootApplication` — component scan + auto-configuration
- `@EnableScheduling` — required for `ChronoService` `@Scheduled` cron

`ServletInitializer` — enables WAR deployment. `java -jar app.war` is self-executable despite `provided` Tomcat scope.

---

## Startup Bean Init Order

Spring resolves dependency graph:
1. `SettingsRepository` — creates `app_settings`, seeds defaults
2. `NoteLinkRepository` — creates `note_links` + index
3. `NoteIndexRepository` — creates `notes` + index
4. `FileRepository.@PostConstruct init()` — reads vault path → `bfsDiskFiles()` → `syncWithDisk()`
5. `ChronoService.@PostConstruct onStartup()` — runs jobs if not run today

To change port: `application.properties server.port`

---

## Technology Notes

- **WAR + embedded Tomcat**: `java -jar app.war` works because Tomcat is bundled (scope `provided` only affects WAR packaging, not the executable jar manifest).
- **Session fixation**: Spring Security rotates session ID on authentication by default — session cookie changes after successful `POST /login`.
- **BCrypt cost factor**: default (10 rounds). Increase in `SecurityConfig` `BCryptPasswordEncoder(int)` — higher = slower login but no runtime impact.
- **`@EnableScheduling` required**: without it, `@Scheduled` annotations are silently ignored.
- **Postgres connection**: `application.properties` datasource url/user/pass overridden at runtime by `SPRING_DATASOURCE_*` env vars (set in `docker-compose.yml`).

---

## Change Index

| Thing to change | Where |
|---|---|
| Auth credentials | `.env APP_AUTH_USERNAME / APP_AUTH_PASSWORD` (compose fails fast if password unset) |
| Protected vs public endpoints | `SecurityConfig.filterChain()` |
| BCrypt cost factor | `SecurityConfig` `new BCryptPasswordEncoder(n)` |
| Allowed CORS origins | `WebConfig.addCorsMappings()` |
| Server port | `application.properties server.port` |
| Postgres connection | `application.properties` (overridden by `SPRING_DATASOURCE_*` env vars) |
| Enable scheduling | `ObsidianApplication` `@EnableScheduling` annotation |
