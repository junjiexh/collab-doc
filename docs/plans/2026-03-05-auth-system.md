# Auth System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add user registration/login with JWT auth to protect all REST and WebSocket endpoints, with private per-user documents.

**Architecture:** Spring Security filter chain with a custom `JwtAuthFilter` reading JWT from HttpOnly cookies. Frontend uses React Context for auth state, checks `GET /api/auth/me` on mount. E2E tests use Playwright's global setup to auto-register/login before all tests.

**Tech Stack:** Spring Security, JJWT 0.12.6, BCrypt, React Context, Playwright global setup

---

### Task 1: Database Migration + User Entity

**Files:**
- Create: `backend/src/main/resources/db/migration/V3__add_users_and_ownership.sql`
- Create: `backend/src/main/java/com/collabdoc/model/User.java`
- Create: `backend/src/main/java/com/collabdoc/repository/UserRepository.java`
- Modify: `backend/src/main/java/com/collabdoc/model/Document.java`
- Modify: `backend/src/main/java/com/collabdoc/repository/DocumentRepository.java`

**Step 1: Create the Flyway migration**

Create `backend/src/main/resources/db/migration/V3__add_users_and_ownership.sql`:

```sql
CREATE TABLE users (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username   VARCHAR(50) UNIQUE NOT NULL,
    password   VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE documents ADD COLUMN owner_id UUID REFERENCES users(id) ON DELETE CASCADE;
```

**Step 2: Create the User JPA entity**

Create `backend/src/main/java/com/collabdoc/model/User.java`:

```java
package com.collabdoc.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(name = "created_at")
    private Instant createdAt;

    protected User() {}

    public User(String username, String password) {
        this.username = username;
        this.password = password;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public Instant getCreatedAt() { return createdAt; }
}
```

**Step 3: Create UserRepository**

Create `backend/src/main/java/com/collabdoc/repository/UserRepository.java`:

```java
package com.collabdoc.repository;

import com.collabdoc.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

**Step 4: Add ownerId to Document entity**

In `backend/src/main/java/com/collabdoc/model/Document.java`, add field and getter/setter:

```java
@Column(name = "owner_id")
private UUID ownerId;

public UUID getOwnerId() { return ownerId; }
public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
```

**Step 5: Add owner-scoped queries to DocumentRepository**

In `backend/src/main/java/com/collabdoc/repository/DocumentRepository.java`, add:

```java
List<Document> findByOwnerIdAndParentIdIsNullOrderBySortOrderAsc(UUID ownerId);

List<Document> findByOwnerIdAndParentIdOrderBySortOrderAsc(UUID ownerId, UUID parentId);

List<Document> findByOwnerIdOrderBySortOrderAsc(UUID ownerId);

@Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d WHERE d.ownerId = :ownerId AND d.parentId = :parentId")
int findMaxSortOrderByOwnerIdAndParentId(UUID ownerId, UUID parentId);

@Query("SELECT COALESCE(MAX(d.sortOrder), -1) FROM Document d WHERE d.ownerId = :ownerId AND d.parentId IS NULL")
int findMaxSortOrderByOwnerIdForRoot(UUID ownerId);
```

**Step 6: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V3__add_users_and_ownership.sql \
  backend/src/main/java/com/collabdoc/model/User.java \
  backend/src/main/java/com/collabdoc/repository/UserRepository.java \
  backend/src/main/java/com/collabdoc/model/Document.java \
  backend/src/main/java/com/collabdoc/repository/DocumentRepository.java
git commit -m "feat(auth): add users table, User entity, document ownership"
```

---

### Task 2: Add Auth Dependencies

**Files:**
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/resources/application.yml`

**Step 1: Add Spring Security and JJWT to build.gradle.kts**

In the `dependencies` block of `backend/build.gradle.kts`, add:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("io.jsonwebtoken:jjwt-api:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
```

**Step 2: Add JWT config to application.yml**

Append to `backend/src/main/resources/application.yml`:

```yaml
collabdoc:
  jwt:
    secret: change-me-to-a-random-64-char-string-in-production-please-now
    expiration-days: 7
```

(Merge under existing `collabdoc:` key so final looks like):

```yaml
collabdoc:
  yrs-bridge:
    library-path: ${YRS_BRIDGE_LIB_PATH:../yrs-bridge/target/release/libyrs_bridge.dylib}
  jwt:
    secret: change-me-to-a-random-64-char-string-in-production-please-now
    expiration-days: 7
```

**Step 3: Verify dependencies resolve**

Run: `cd backend && ./gradlew dependencies --configuration runtimeClasspath | grep jjwt`
Expected: Shows jjwt-api, jjwt-impl, jjwt-jackson

**Step 4: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/resources/application.yml
git commit -m "build(auth): add Spring Security + JJWT dependencies"
```

---

### Task 3: JWT Utility

**Files:**
- Create: `backend/src/main/java/com/collabdoc/auth/JwtUtil.java`

**Step 1: Create JwtUtil**

Create `backend/src/main/java/com/collabdoc/auth/JwtUtil.java`:

```java
package com.collabdoc.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final Duration expiration;

    public JwtUtil(
            @Value("${collabdoc.jwt.secret}") String secret,
            @Value("${collabdoc.jwt.expiration-days}") int expirationDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofDays(expirationDays);
    }

    public String generateToken(UUID userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    public String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public long getExpirationSeconds() {
        return expiration.toSeconds();
    }
}
```

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/collabdoc/auth/JwtUtil.java
git commit -m "feat(auth): add JWT utility for token generation and parsing"
```

---

### Task 4: AuthService + AuthController

**Files:**
- Create: `backend/src/main/java/com/collabdoc/auth/AuthService.java`
- Create: `backend/src/main/java/com/collabdoc/auth/AuthController.java`

**Step 1: Create AuthService**

Create `backend/src/main/java/com/collabdoc/auth/AuthService.java`:

```java
package com.collabdoc.auth;

import com.collabdoc.model.User;
import com.collabdoc.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User register(String username, String password) {
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already taken");
        }
        User user = new User(username, passwordEncoder.encode(password));
        return userRepository.save(user);
    }

    public Optional<User> login(String username, String password) {
        return userRepository.findByUsername(username)
                .filter(user -> passwordEncoder.matches(password, user.getPassword()));
    }
}
```

**Step 2: Create AuthController**

Create `backend/src/main/java/com/collabdoc/auth/AuthController.java`:

```java
package com.collabdoc.auth;

import com.collabdoc.model.User;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;

    public AuthController(AuthService authService, JwtUtil jwtUtil) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null || username.isBlank() || password.length() < 4) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username and password (min 4 chars) required"));
        }
        try {
            User user = authService.register(username.trim(), password);
            String token = jwtUtil.generateToken(user.getId(), user.getUsername());
            return ResponseEntity.status(201)
                    .header(HttpHeaders.SET_COOKIE, buildCookie(token).toString())
                    .body(Map.of("id", user.getId(), "username", user.getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        return authService.login(username, password)
                .map(user -> {
                    String token = jwtUtil.generateToken(user.getId(), user.getUsername());
                    return ResponseEntity.ok()
                            .header(HttpHeaders.SET_COOKIE, buildCookie(token).toString())
                            .<Map<String, Object>>body(Map.of("id", user.getId(), "username", user.getUsername()));
                })
                .orElse(ResponseEntity.status(401).body(Map.of("error", "Invalid credentials")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        ResponseCookie cookie = ResponseCookie.from("token", "")
                .httpOnly(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal UUID userId,
            @RequestHeader(value = "X-Auth-Username", required = false) String username) {
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        return ResponseEntity.ok(Map.of("id", userId, "username", username != null ? username : ""));
    }

    private ResponseCookie buildCookie(String token) {
        return ResponseCookie.from("token", token)
                .httpOnly(true)
                .path("/")
                .maxAge(jwtUtil.getExpirationSeconds())
                .sameSite("Lax")
                .build();
    }
}
```

Note: The `/me` endpoint reads the principal set by the JWT filter (Task 5). The `X-Auth-Username` header is set by the filter for convenience.

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL (may have warnings about Spring Security auto-config, that's fine — SecurityConfig comes next)

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/auth/AuthService.java \
  backend/src/main/java/com/collabdoc/auth/AuthController.java
git commit -m "feat(auth): add AuthService and AuthController for register/login/logout/me"
```

---

### Task 5: Spring Security Config + JWT Filter

**Files:**
- Create: `backend/src/main/java/com/collabdoc/auth/JwtAuthFilter.java`
- Create: `backend/src/main/java/com/collabdoc/config/SecurityConfig.java`
- Modify: `backend/src/main/java/com/collabdoc/config/CorsConfig.java`

**Step 1: Create JwtAuthFilter**

Create `backend/src/main/java/com/collabdoc/auth/JwtAuthFilter.java`:

```java
package com.collabdoc.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && jwtUtil.isValid(token)) {
            UUID userId = jwtUtil.getUserId(token);
            String username = jwtUtil.getUsername(token);
            var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
            // Pass username downstream via request attribute (used by /me endpoint)
            request.setAttribute("authUsername", username);
            // Also set as header wrapper for controller access
            var wrapper = new jakarta.servlet.http.HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    if ("X-Auth-Username".equalsIgnoreCase(name)) return username;
                    return super.getHeader(name);
                }
            };
            chain.doFilter(wrapper, response);
            return;
        }
        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if ("token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
```

**Step 2: Create SecurityConfig**

Create `backend/src/main/java/com/collabdoc/config/SecurityConfig.java`:

```java
package com.collabdoc.config;

import com.collabdoc.auth.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/ws/**").permitAll()  // WS auth done at handshake level
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

**Step 3: Update CorsConfig for credentials**

Replace the full content of `backend/src/main/java/com/collabdoc/config/CorsConfig.java`:

```java
package com.collabdoc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                    .allowedOrigins("http://localhost:3000")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH")
                    .allowCredentials(true);
            }
        };
    }
}
```

**Step 4: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add backend/src/main/java/com/collabdoc/auth/JwtAuthFilter.java \
  backend/src/main/java/com/collabdoc/config/SecurityConfig.java \
  backend/src/main/java/com/collabdoc/config/CorsConfig.java
git commit -m "feat(auth): add JWT filter, Spring Security config, CORS credentials"
```

---

### Task 6: Document Ownership in Service + Controllers

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/service/DocumentService.java`
- Modify: `backend/src/main/java/com/collabdoc/controller/DocumentController.java`
- Modify: `backend/src/main/java/com/collabdoc/controller/BlockController.java`

**Step 1: Update DocumentService — all methods take ownerId**

Replace the full content of `DocumentService.java`. Key changes:
- `createDocument(title, parentId, ownerId)` — sets ownerId on new doc
- `listDocumentsForTree(ownerId)` — filters by owner
- `moveDocument(id, newParentId, targetIndex, ownerId)` — verifies ownership
- `getDocument(id, ownerId)` — verifies ownership
- `updateTitle(id, title, ownerId)` — verifies ownership
- `deleteDocument(id, ownerId)` — verifies ownership
- All sibling queries use owner-scoped repository methods

```java
package com.collabdoc.service;

import com.collabdoc.model.Document;
import com.collabdoc.repository.DocumentRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DocumentService {

    private final DocumentRepository documentRepository;

    public DocumentService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public Document createDocument(String title, UUID parentId, UUID ownerId) {
        int maxSort;
        if (parentId == null) {
            maxSort = documentRepository.findMaxSortOrderByOwnerIdForRoot(ownerId);
        } else {
            maxSort = documentRepository.findMaxSortOrderByOwnerIdAndParentId(ownerId, parentId);
        }
        Document doc = new Document(title, parentId);
        doc.setOwnerId(ownerId);
        doc.setSortOrder(maxSort + 1);
        return documentRepository.save(doc);
    }

    public List<Document> listDocumentsForTree(UUID ownerId) {
        return documentRepository.findByOwnerIdOrderBySortOrderAsc(ownerId);
    }

    public Optional<Document> moveDocument(UUID id, UUID newParentId, int targetIndex, UUID ownerId) {
        return documentRepository.findById(id)
                .filter(doc -> ownerId.equals(doc.getOwnerId()))
                .map(doc -> {
                    List<Document> siblings;
                    if (newParentId == null) {
                        siblings = documentRepository.findByOwnerIdAndParentIdIsNullOrderBySortOrderAsc(ownerId);
                    } else {
                        siblings = documentRepository.findByOwnerIdAndParentIdOrderBySortOrderAsc(ownerId, newParentId);
                    }
                    siblings.removeIf(d -> d.getId().equals(id));
                    int insertAt = Math.max(0, Math.min(targetIndex, siblings.size()));
                    siblings.add(insertAt, doc);
                    for (int i = 0; i < siblings.size(); i++) {
                        siblings.get(i).setSortOrder(i);
                    }
                    doc.setParentId(newParentId);
                    doc.setUpdatedAt(Instant.now());
                    documentRepository.saveAll(siblings);
                    return doc;
                });
    }

    public Optional<Document> getDocument(UUID id, UUID ownerId) {
        return documentRepository.findById(id)
                .filter(doc -> ownerId.equals(doc.getOwnerId()));
    }

    public Optional<Document> updateTitle(UUID id, String title, UUID ownerId) {
        return documentRepository.findById(id)
                .filter(doc -> ownerId.equals(doc.getOwnerId()))
                .map(doc -> {
                    doc.setTitle(title);
                    doc.setUpdatedAt(Instant.now());
                    return documentRepository.save(doc);
                });
    }

    public boolean deleteDocument(UUID id, UUID ownerId) {
        return documentRepository.findById(id)
                .filter(doc -> ownerId.equals(doc.getOwnerId()))
                .map(doc -> {
                    documentRepository.deleteById(id);
                    return true;
                })
                .orElse(false);
    }

    /** Check if user owns a document (used by WebSocket auth). */
    public boolean isOwner(UUID docId, UUID ownerId) {
        return documentRepository.findById(docId)
                .map(doc -> ownerId.equals(doc.getOwnerId()))
                .orElse(false);
    }
}
```

**Step 2: Update DocumentController — extract userId from SecurityContext**

Replace the full content of `DocumentController.java`:

```java
package com.collabdoc.controller;

import com.collabdoc.model.Document;
import com.collabdoc.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping
    public List<Document> listDocuments(@AuthenticationPrincipal UUID userId) {
        return documentService.listDocumentsForTree(userId);
    }

    @GetMapping("/tree")
    public List<Document> listDocumentsForTree(@AuthenticationPrincipal UUID userId) {
        return documentService.listDocumentsForTree(userId);
    }

    @PostMapping
    public Document createDocument(@AuthenticationPrincipal UUID userId, @RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "Untitled");
        String parentIdStr = body.get("parentId");
        UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
        return documentService.createDocument(title, parentId, userId);
    }

    @PutMapping("/{id}/move")
    public ResponseEntity<Document> moveDocument(@AuthenticationPrincipal UUID userId,
                                                  @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        String parentIdStr = (String) body.get("parentId");
        UUID parentId = parentIdStr != null ? UUID.fromString(parentIdStr) : null;
        int sortOrder = (int) body.get("sortOrder");
        return documentService.moveDocument(id, parentId, sortOrder, userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        return documentService.getDocument(id, userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Document> updateDocument(@AuthenticationPrincipal UUID userId,
                                                    @PathVariable UUID id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        return documentService.updateTitle(id, title, userId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        if (documentService.deleteDocument(id, userId)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
```

**Step 3: Update BlockController — add ownership check**

In `backend/src/main/java/com/collabdoc/controller/BlockController.java`, add `@AuthenticationPrincipal UUID userId` to each method and a doc ownership check. The block operations already verify the doc exists via `docManager`, but we need to verify ownership. Add a `DocumentService` dependency and check before each operation.

Add import and field:
```java
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.collabdoc.service.DocumentService;
```

Update constructor to inject `DocumentService`:
```java
private final YrsDocumentManager docManager;
private final YjsWebSocketHandler wsHandler;
private final DocumentService documentService;

public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler, DocumentService documentService) {
    this.docManager = docManager;
    this.wsHandler = wsHandler;
    this.documentService = documentService;
}
```

Add `@AuthenticationPrincipal UUID userId` as first param to each endpoint. Add ownership check at start of each method:
```java
if (!documentService.isOwner(docId, userId)) {
    return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
}
```

**Step 4: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add backend/src/main/java/com/collabdoc/service/DocumentService.java \
  backend/src/main/java/com/collabdoc/controller/DocumentController.java \
  backend/src/main/java/com/collabdoc/controller/BlockController.java
git commit -m "feat(auth): enforce document ownership in service and controllers"
```

---

### Task 7: WebSocket Auth

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/config/WebSocketConfig.java`
- Modify: `backend/src/main/java/com/collabdoc/websocket/YjsWebSocketHandler.java`

**Step 1: Add HandshakeInterceptor to WebSocketConfig**

Replace the full content of `WebSocketConfig.java`:

```java
package com.collabdoc.config;

import com.collabdoc.auth.JwtUtil;
import com.collabdoc.service.YrsDocumentManager;
import com.collabdoc.websocket.YjsWebSocketHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final YrsDocumentManager docManager;
    private final JwtUtil jwtUtil;

    public WebSocketConfig(YrsDocumentManager docManager, JwtUtil jwtUtil) {
        this.docManager = docManager;
        this.jwtUtil = jwtUtil;
    }

    @Bean
    public YjsWebSocketHandler yjsWebSocketHandler() {
        return new YjsWebSocketHandler(docManager);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(yjsWebSocketHandler(), "/ws/*")
                .addInterceptors(jwtHandshakeInterceptor())
                .setAllowedOrigins("http://localhost:3000");
    }

    private HandshakeInterceptor jwtHandshakeInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler wsHandler, Map<String, Object> attributes) {
                if (request instanceof ServletServerHttpRequest servletRequest) {
                    HttpServletRequest httpRequest = servletRequest.getServletRequest();
                    Cookie[] cookies = httpRequest.getCookies();
                    if (cookies != null) {
                        for (Cookie cookie : cookies) {
                            if ("token".equals(cookie.getName()) && jwtUtil.isValid(cookie.getValue())) {
                                UUID userId = jwtUtil.getUserId(cookie.getValue());
                                attributes.put("userId", userId);
                                return true;
                            }
                        }
                    }
                }
                return false; // Reject handshake — no valid JWT
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Exception exception) {}
        };
    }
}
```

**Step 2: Update YjsWebSocketHandler — verify document ownership**

In `YjsWebSocketHandler.java`, inject `DocumentService` and check ownership in `afterConnectionEstablished`.

Add import and field:
```java
import com.collabdoc.service.DocumentService;
```

Update constructor:
```java
private final YrsDocumentManager docManager;
private final DocumentService documentService;

public YjsWebSocketHandler(YrsDocumentManager docManager) {
    this.docManager = docManager;
    this.documentService = null; // set via setter for backward compat
}

public YjsWebSocketHandler(YrsDocumentManager docManager, DocumentService documentService) {
    this.docManager = docManager;
    this.documentService = documentService;
}
```

Actually, simpler approach: update `WebSocketConfig` to pass `DocumentService` and use the two-arg constructor. Update `afterConnectionEstablished` to read `userId` from attributes and verify ownership:

In `afterConnectionEstablished`, after parsing `docId`, add:

```java
// Verify ownership
UUID userId = (UUID) session.getAttributes().get("userId");
if (userId == null || (documentService != null && !documentService.isOwner(docId, userId))) {
    log.warn("Unauthorized WebSocket access: user={}, doc={}", userId, docId);
    try { session.close(); } catch (IOException ignored) {}
    return;
}
```

Update `WebSocketConfig.yjsWebSocketHandler()` bean to inject `DocumentService`:

```java
@Bean
public YjsWebSocketHandler yjsWebSocketHandler(DocumentService documentService) {
    return new YjsWebSocketHandler(docManager, documentService);
}
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/config/WebSocketConfig.java \
  backend/src/main/java/com/collabdoc/websocket/YjsWebSocketHandler.java
git commit -m "feat(auth): add WebSocket JWT handshake auth + document ownership check"
```

---

### Task 8: Frontend API — Add Credentials + Auth Functions

**Files:**
- Modify: `frontend/src/api.ts`

**Step 1: Add `credentials: "include"` to all fetch calls and auth functions**

Replace full content of `frontend/src/api.ts`:

```typescript
const API_BASE = "/api";

export interface DocumentMeta {
  id: string;
  title: string;
  parentId: string | null;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface AuthUser {
  id: string;
  username: string;
}

// --- Auth API ---

export async function authRegister(username: string, password: string): Promise<AuthUser> {
  const res = await fetch(`${API_BASE}/auth/register`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    const data = await res.json();
    throw new Error(data.error || "Registration failed");
  }
  return res.json();
}

export async function authLogin(username: string, password: string): Promise<AuthUser> {
  const res = await fetch(`${API_BASE}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    const data = await res.json();
    throw new Error(data.error || "Login failed");
  }
  return res.json();
}

export async function authLogout(): Promise<void> {
  await fetch(`${API_BASE}/auth/logout`, {
    method: "POST",
    credentials: "include",
  });
}

export async function authMe(): Promise<AuthUser | null> {
  const res = await fetch(`${API_BASE}/auth/me`, { credentials: "include" });
  if (!res.ok) return null;
  return res.json();
}

// --- Document API ---

export async function listDocuments(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs`, { credentials: "include" });
  return res.json();
}

export async function createDocument(title: string, parentId?: string): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ title, parentId: parentId ?? null }),
  });
  return res.json();
}

export async function deleteDocument(id: string): Promise<void> {
  await fetch(`${API_BASE}/docs/${id}`, { method: "DELETE", credentials: "include" });
}

export async function fetchDocumentTree(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs/tree`, { credentials: "include" });
  return res.json();
}

export async function renameDocument(id: string, title: string): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs/${id}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ title }),
  });
  return res.json();
}

export async function moveDocument(id: string, parentId: string | null, sortOrder: number): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs/${id}/move`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    credentials: "include",
    body: JSON.stringify({ parentId, sortOrder }),
  });
  return res.json();
}
```

**Step 2: Verify TypeScript**

Run: `cd frontend && npx tsc --noEmit`
Expected: No errors

**Step 3: Commit**

```bash
git add frontend/src/api.ts
git commit -m "feat(auth): add credentials to all fetch calls, add auth API functions"
```

---

### Task 9: Frontend AuthContext + ProtectedRoute

**Files:**
- Create: `frontend/src/contexts/AuthContext.tsx`
- Create: `frontend/src/components/ProtectedRoute.tsx`

**Step 1: Create AuthContext**

Create `frontend/src/contexts/AuthContext.tsx`:

```tsx
import { createContext, useContext, useEffect, useState, useCallback, type ReactNode } from "react";
import { authMe, authLogin, authRegister, authLogout, type AuthUser } from "../api";

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    authMe().then(setUser).finally(() => setLoading(false));
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const u = await authLogin(username, password);
    setUser(u);
  }, []);

  const register = useCallback(async (username: string, password: string) => {
    const u = await authRegister(username, password);
    setUser(u);
  }, []);

  const logout = useCallback(async () => {
    await authLogout();
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within AuthProvider");
  return ctx;
}
```

**Step 2: Create ProtectedRoute**

Create `frontend/src/components/ProtectedRoute.tsx`:

```tsx
import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "../contexts/AuthContext";

export default function ProtectedRoute() {
  const { user, loading } = useAuth();

  if (loading) {
    return <div style={{ display: "flex", justifyContent: "center", alignItems: "center", height: "100vh" }}>
      Loading...
    </div>;
  }

  if (!user) return <Navigate to="/login" replace />;

  return <Outlet />;
}
```

**Step 3: Verify TypeScript**

Run: `cd frontend && npx tsc --noEmit`
Expected: No errors

**Step 4: Commit**

```bash
git add frontend/src/contexts/AuthContext.tsx frontend/src/components/ProtectedRoute.tsx
git commit -m "feat(auth): add AuthContext provider and ProtectedRoute component"
```

---

### Task 10: Frontend LoginPage + Route Wiring

**Files:**
- Create: `frontend/src/pages/LoginPage.tsx`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/main.tsx`
- Modify: `frontend/src/layouts/MainLayout.tsx`

**Step 1: Create LoginPage**

Create `frontend/src/pages/LoginPage.tsx`:

```tsx
import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../contexts/AuthContext";

export default function LoginPage() {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (action: "login" | "register") => {
    setError("");
    setIsSubmitting(true);
    try {
      if (action === "register") {
        await register(username, password);
      } else {
        await login(username, password);
      }
      navigate("/");
    } catch (e: any) {
      setError(e.message || "Something went wrong");
    } finally {
      setIsSubmitting(false);
    }
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    handleSubmit("login");
  };

  return (
    <div style={{ display: "flex", justifyContent: "center", alignItems: "center", height: "100vh", background: "#fafafa" }}>
      <form onSubmit={onSubmit} style={{
        background: "#fff", padding: 32, borderRadius: 8,
        boxShadow: "0 2px 8px rgba(0,0,0,0.1)", width: 320,
      }}>
        <h2 style={{ marginTop: 0, marginBottom: 24 }}>CollabDoc</h2>
        {error && <div style={{ color: "#e53e3e", marginBottom: 12, fontSize: 14 }}>{error}</div>}
        <input
          type="text"
          placeholder="Username"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          required
          style={{ width: "100%", padding: 8, marginBottom: 12, borderRadius: 4, border: "1px solid #ddd", boxSizing: "border-box" }}
        />
        <input
          type="password"
          placeholder="Password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
          minLength={4}
          style={{ width: "100%", padding: 8, marginBottom: 16, borderRadius: 4, border: "1px solid #ddd", boxSizing: "border-box" }}
        />
        <button
          type="submit"
          disabled={isSubmitting}
          style={{ width: "100%", padding: 10, background: "#2563eb", color: "#fff", border: "none", borderRadius: 4, cursor: "pointer", marginBottom: 8 }}
        >
          Log in
        </button>
        <button
          type="button"
          disabled={isSubmitting}
          onClick={() => handleSubmit("register")}
          style={{ width: "100%", padding: 10, background: "#f3f4f6", color: "#333", border: "1px solid #ddd", borderRadius: 4, cursor: "pointer" }}
        >
          Register
        </button>
      </form>
    </div>
  );
}
```

**Step 2: Update App.tsx — add login route + ProtectedRoute wrapper**

Replace full content of `frontend/src/App.tsx`:

```tsx
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "./contexts/AuthContext";
import ProtectedRoute from "./components/ProtectedRoute";
import MainLayout from "./layouts/MainLayout";
import EditorPage from "./pages/EditorPage";
import WelcomePage from "./pages/WelcomePage";
import LoginPage from "./pages/LoginPage";

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<ProtectedRoute />}>
            <Route element={<MainLayout />}>
              <Route path="/" element={<WelcomePage />} />
              <Route path="/doc/:docId" element={<EditorPage />} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
```

**Step 3: Add logout button to Sidebar area in MainLayout**

In `frontend/src/layouts/MainLayout.tsx`, add a logout button. Import `useAuth` and add a logout handler.

Add at top:
```typescript
import { useAuth } from "../contexts/AuthContext";
```

Inside the component, add:
```typescript
const { user, logout } = useAuth();
```

In the JSX, add a user info / logout section at the bottom of the sidebar `<div>`. Before the closing `</div>` of the sidebar (the one with `Sidebar` component), add:

```tsx
<div style={{ padding: "8px 12px", borderTop: "1px solid #e5e5e5", fontSize: 13, display: "flex", justifyContent: "space-between", alignItems: "center" }}>
  <span style={{ color: "#666" }}>{user?.username}</span>
  <button onClick={logout} style={{ background: "none", border: "none", color: "#999", cursor: "pointer", fontSize: 13 }}>
    Log out
  </button>
</div>
```

The sidebar section in `MainLayout` needs a wrapper to stack the Sidebar component and the logout row vertically. Wrap the sidebar in a flex column container.

**Step 4: Verify TypeScript**

Run: `cd frontend && npx tsc --noEmit`
Expected: No errors

**Step 5: Commit**

```bash
git add frontend/src/pages/LoginPage.tsx frontend/src/App.tsx \
  frontend/src/main.tsx frontend/src/layouts/MainLayout.tsx
git commit -m "feat(auth): add LoginPage, route protection, logout button"
```

---

### Task 11: E2E Test Refactor

**Files:**
- Create: `frontend/e2e/auth.setup.ts`
- Modify: `frontend/playwright.config.ts`
- Modify: `frontend/e2e/connecting-bug.spec.ts`
- Modify: `frontend/e2e/browser-then-agent.spec.ts`
- Modify: `frontend/e2e/agent-write-visible.spec.ts`
- Modify: `.gitignore`

**Step 1: Create auth.setup.ts**

Create `frontend/e2e/auth.setup.ts`:

```typescript
import { test as setup } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";
const TEST_USER = { username: "e2e_testuser", password: "testpass123" };

setup("authenticate", async ({ request, context }) => {
  // Try to register; if user already exists, login instead
  const registerRes = await request.post(`${API_BASE}/auth/register`, {
    data: TEST_USER,
  });

  if (registerRes.status() === 409) {
    // User already exists — login
    const loginRes = await request.post(`${API_BASE}/auth/login`, {
      data: TEST_USER,
    });
    if (!loginRes.ok()) {
      throw new Error(`Login failed: ${loginRes.status()}`);
    }
  } else if (!registerRes.ok()) {
    throw new Error(`Register failed: ${registerRes.status()}`);
  }

  // Save cookies (storageState) for all tests to reuse
  await context.storageState({ path: "e2e/.auth/user.json" });
});
```

**Step 2: Update playwright.config.ts**

Replace full content of `frontend/playwright.config.ts`:

```typescript
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  timeout: 30000,
  use: {
    baseURL: "http://localhost:3000",
    headless: true,
  },
  projects: [
    {
      name: "setup",
      testMatch: /auth\.setup\.ts/,
    },
    {
      name: "tests",
      dependencies: ["setup"],
      use: {
        storageState: "e2e/.auth/user.json",
      },
    },
  ],
});
```

**Step 3: Update the 3 existing test files**

The tests already use `page.request.post(...)` for API calls, which is great — Playwright's `page.request` inherits the browser context cookies from `storageState`. The `API_BASE` points to `http://localhost:8080` directly though, so the requests go direct to backend. The `page.request` in Playwright carries cookies from the browser context, which includes our auth cookie from storageState. This should work as-is.

No code changes needed in the test files themselves — `page.request` inherits cookies from the browser context that was loaded with `storageState`.

**Step 4: Add .auth to .gitignore**

Append to root `.gitignore`:

```
.auth
```

**Step 5: Create the auth state directory**

Run: `mkdir -p frontend/e2e/.auth && echo '{}' > frontend/e2e/.auth/user.json`

This placeholder prevents Playwright from erroring before first run.

**Step 6: Verify test config is valid**

Run: `cd frontend && npx playwright test --list`
Expected: Lists all tests organized under "setup" and "tests" projects

**Step 7: Commit**

```bash
git add frontend/e2e/auth.setup.ts frontend/playwright.config.ts .gitignore
git commit -m "feat(auth): add Playwright auth setup, refactor e2e tests for auth"
```

---

### Task 12: End-to-End Verification

**Step 1: Backend compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 2: Frontend type check**

Run: `cd frontend && npx tsc --noEmit`
Expected: No errors

**Step 3: Start backend + frontend, manual smoke test**

Run backend: `cd backend && ./gradlew bootRun`
Run frontend: `cd frontend && npm run dev`

Verify:
- Visit `http://localhost:3000` → redirected to `/login`
- Register user "alice" with password "test1234" → redirected to home
- Create a document → title editable, editor works
- Open incognito → login page
- Log out → redirected to login

**Step 4: Run E2E tests**

Run: `cd frontend && npx playwright test`
Expected: All 3 tests pass (setup project runs first, authenticates, then tests run with cookies)

**Step 5: Final commit (if any fixes needed)**

```bash
git add -A
git commit -m "fix(auth): address issues found during verification"
```
