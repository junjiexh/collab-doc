# CollabDoc Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a Notion-style collaborative document editor with Java 25 backend, CRDT (Yjs/Yrs) collaboration, BlockNote frontend, and Agent REST API.

**Architecture:** React+BlockNote frontend communicates with Java 25 Spring Boot backend via WebSocket (Yjs sync) and REST (Agent API). Java backend uses Yrs (Rust) via FFM API for server-side CRDT operations. PostgreSQL stores document state.

**Tech Stack:** Java 25, Spring Boot 3.4, React 19, TypeScript, BlockNote, Yjs, Yrs (Rust), PostgreSQL 17, Vite, Docker Compose

---

### Task 1: Project Scaffolding

**Files:**
- Create: `docker-compose.yml`
- Create: `backend/build.gradle.kts`
- Create: `backend/settings.gradle.kts`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/collabdoc/CollabDocApplication.java`
- Create: `yrs-bridge/Cargo.toml`
- Create: `yrs-bridge/src/lib.rs`
- Create: `frontend/package.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`
- Create: `frontend/index.html`
- Create: `frontend/src/main.tsx`
- Create: `frontend/src/App.tsx`

**Step 1: Create Docker Compose for PostgreSQL**

```yaml
# docker-compose.yml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: collabdoc
      POSTGRES_USER: collabdoc
      POSTGRES_PASSWORD: collabdoc
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```

**Step 2: Create Spring Boot backend project**

```kotlin
// backend/settings.gradle.kts
rootProject.name = "collabdoc-backend"
```

```kotlin
// backend/build.gradle.kts
plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.collabdoc"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--enable-preview")
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--enable-preview")
}
```

```yaml
# backend/src/main/resources/application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/collabdoc
    username: collabdoc
    password: collabdoc
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true

server:
  port: 8080

collabdoc:
  yrs-bridge:
    library-path: ${YRS_BRIDGE_LIB_PATH:../yrs-bridge/target/release/libyrs_bridge.dylib}
```

```java
// backend/src/main/java/com/collabdoc/CollabDocApplication.java
package com.collabdoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CollabDocApplication {
    public static void main(String[] args) {
        SpringApplication.run(CollabDocApplication.class, args);
    }
}
```

**Step 3: Create Rust yrs-bridge project**

```toml
# yrs-bridge/Cargo.toml
[package]
name = "yrs-bridge"
version = "0.1.0"
edition = "2021"

[lib]
name = "yrs_bridge"
crate-type = ["cdylib"]

[dependencies]
yrs = "0.21"
serde_json = "1"
serde = { version = "1", features = ["derive"] }
```

```rust
// yrs-bridge/src/lib.rs (placeholder)
// Will be implemented in Task 2
```

**Step 4: Create React frontend project**

```json
// frontend/package.json
{
  "name": "collabdoc-frontend",
  "private": true,
  "version": "0.0.1",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "@blocknote/core": "^0.22.0",
    "@blocknote/react": "^0.22.0",
    "@blocknote/mantine": "^0.22.0",
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "react-router-dom": "^7.1.0",
    "yjs": "^13.6.0",
    "y-websocket": "^2.1.0"
  },
  "devDependencies": {
    "@types/react": "^19.0.0",
    "@types/react-dom": "^19.0.0",
    "@vitejs/plugin-react": "^4.3.0",
    "typescript": "~5.7.0",
    "vite": "^6.1.0"
  }
}
```

```typescript
// frontend/vite.config.ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      "/api": "http://localhost:8080",
      "/ws": {
        target: "ws://localhost:8080",
        ws: true,
      },
    },
  },
});
```

```json
// frontend/tsconfig.json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "isolatedModules": true,
    "moduleDetection": "force",
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "noUncheckedSideEffectImports": true
  },
  "include": ["src"]
}
```

```html
<!-- frontend/index.html -->
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>CollabDoc</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

```tsx
// frontend/src/main.tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>
);
```

```tsx
// frontend/src/App.tsx
export default function App() {
  return <div><h1>CollabDoc</h1><p>Loading...</p></div>;
}
```

**Step 5: Verify scaffolding**

Run: `docker compose up -d` -- PostgreSQL starts
Run: `cd backend && ./gradlew build -x test` -- Java compiles (will fail on Flyway but that's OK)
Run: `cd yrs-bridge && cargo check` -- Rust compiles
Run: `cd frontend && npm install && npm run build` -- Frontend compiles

**Step 6: Commit**

```bash
git add -A
git commit -m "feat: project scaffolding with Docker, Spring Boot, Rust, React"
```

---

### Task 2: Yrs Rust Bridge Library

**Files:**
- Create: `yrs-bridge/src/lib.rs`

This is a Rust cdylib that wraps the `yrs` crate, exposing C ABI functions for Java to call via FFM. The bridge handles:
- Document lifecycle (create/destroy)
- Yjs sync protocol (state vector, diffs, apply updates)
- Block-level operations (insert/update/delete blocks in XmlFragment)
- JSON serialization of document content

**Step 1: Implement the Rust bridge**

```rust
// yrs-bridge/src/lib.rs
use std::ffi::{c_char, CStr, CString};
use std::slice;
use std::sync::Mutex;
use yrs::updates::decoder::Decode;
use yrs::updates::encoder::Encode;
use yrs::{Doc, GetString, Options, ReadTxn, StateVector, Text, Transact, Update, XmlFragmentRef};

/// Opaque handle wrapping a Yrs Doc.
pub struct YrsDoc {
    doc: Doc,
}

// --- Document Lifecycle ---

/// Create a new Yrs document. Returns a pointer that must be freed with yrs_doc_destroy.
#[no_mangle]
pub extern "C" fn yrs_doc_new() -> *mut YrsDoc {
    let doc = Doc::with_options(Options {
        skip_gc: false,
        ..Default::default()
    });
    Box::into_raw(Box::new(YrsDoc { doc }))
}

/// Destroy a Yrs document.
#[no_mangle]
pub extern "C" fn yrs_doc_destroy(doc: *mut YrsDoc) {
    if !doc.is_null() {
        unsafe {
            let _ = Box::from_raw(doc);
        }
    }
}

// --- Sync Protocol ---

/// Get the state vector of the document (V1 encoded).
/// Returns a pointer to bytes. Caller must free with yrs_free_bytes.
#[no_mangle]
pub extern "C" fn yrs_doc_state_vector(
    doc: *const YrsDoc,
    out_len: *mut u32,
) -> *mut u8 {
    let doc = unsafe { &*doc };
    let txn = doc.doc.transact();
    let sv = txn.state_vector().encode_v1();
    unsafe { *out_len = sv.len() as u32 };
    let ptr = sv.as_ptr() as *mut u8;
    std::mem::forget(sv);
    ptr
}

/// Compute the update diff from a remote state vector.
/// If sv is null, returns the full document state.
/// Returns a pointer to bytes. Caller must free with yrs_free_bytes.
#[no_mangle]
pub extern "C" fn yrs_doc_encode_diff(
    doc: *const YrsDoc,
    sv: *const u8,
    sv_len: u32,
    out_len: *mut u32,
) -> *mut u8 {
    let doc = unsafe { &*doc };
    let txn = doc.doc.transact();

    let state_vector = if sv.is_null() || sv_len == 0 {
        StateVector::default()
    } else {
        let sv_bytes = unsafe { slice::from_raw_parts(sv, sv_len as usize) };
        StateVector::decode_v1(sv_bytes).unwrap_or_default()
    };

    let update = txn.encode_diff_v1(&state_vector);
    unsafe { *out_len = update.len() as u32 };
    let ptr = update.as_ptr() as *mut u8;
    std::mem::forget(update);
    ptr
}

/// Encode the full document state as a single update (for snapshots).
/// Returns a pointer to bytes. Caller must free with yrs_free_bytes.
#[no_mangle]
pub extern "C" fn yrs_doc_encode_state(
    doc: *const YrsDoc,
    out_len: *mut u32,
) -> *mut u8 {
    let doc = unsafe { &*doc };
    let txn = doc.doc.transact();
    let update = txn.encode_state_as_update_v1(&StateVector::default());
    unsafe { *out_len = update.len() as u32 };
    let ptr = update.as_ptr() as *mut u8;
    std::mem::forget(update);
    ptr
}

/// Apply a V1-encoded update to the document.
/// Returns the generated update bytes (to broadcast). Caller must free with yrs_free_bytes.
/// Returns null if the update produces no changes.
#[no_mangle]
pub extern "C" fn yrs_doc_apply_update(
    doc: *mut YrsDoc,
    update: *const u8,
    update_len: u32,
    out_len: *mut u32,
) -> *mut u8 {
    let doc = unsafe { &mut *doc };
    let update_bytes = unsafe { slice::from_raw_parts(update, update_len as usize) };

    let parsed = match Update::decode_v1(update_bytes) {
        Ok(u) => u,
        Err(_) => {
            unsafe { *out_len = 0 };
            return std::ptr::null_mut();
        }
    };

    let mut txn = doc.doc.transact_mut();
    if txn.apply_update(parsed).is_err() {
        unsafe { *out_len = 0 };
        return std::ptr::null_mut();
    }
    drop(txn);

    // Re-encode the update we just applied to broadcast it
    // (clients need the exact bytes they received)
    unsafe { *out_len = update_len };
    let mut copy = vec![0u8; update_len as usize];
    copy.copy_from_slice(update_bytes);
    let ptr = copy.as_mut_ptr();
    std::mem::forget(copy);
    ptr
}

/// Load document from a full state snapshot (V1 encoded).
/// Returns 0 on success, non-zero on error.
#[no_mangle]
pub extern "C" fn yrs_doc_load_state(
    doc: *mut YrsDoc,
    state: *const u8,
    state_len: u32,
) -> i32 {
    if state.is_null() || state_len == 0 {
        return 0;
    }
    let doc = unsafe { &mut *doc };
    let state_bytes = unsafe { slice::from_raw_parts(state, state_len as usize) };

    match Update::decode_v1(state_bytes) {
        Ok(update) => {
            let mut txn = doc.doc.transact_mut();
            match txn.apply_update(update) {
                Ok(_) => 0,
                Err(_) => -1,
            }
        }
        Err(_) => -2,
    }
}

// --- Block Operations ---
// BlockNote/Tiptap/ProseMirror stores content in an XmlFragment named "document-store".
// Each block is an XmlElement child of the fragment.

/// Get all blocks as a JSON string.
/// Returns a C string that must be freed with yrs_free_string.
/// The JSON format matches BlockNote's block structure.
#[no_mangle]
pub extern "C" fn yrs_doc_get_blocks_json(doc: *const YrsDoc) -> *mut c_char {
    let doc = unsafe { &*doc };
    let txn = doc.doc.transact();

    let fragment: XmlFragmentRef = txn.get_or_insert_xml_fragment("document-store");
    let xml_string = fragment.get_string(&txn);

    // Return the XML as a string (we'll parse it to JSON on the Java side for MVP)
    let c_str = CString::new(xml_string).unwrap_or_else(|_| CString::new("").unwrap());
    c_str.into_raw()
}

/// Insert a text block at the given index in the document fragment.
/// block_type: "paragraph", "heading", etc.
/// content: the text content of the block
/// props_json: JSON string of block properties (e.g., {"level": 2})
/// Returns the generated update bytes. Caller must free with yrs_free_bytes.
#[no_mangle]
pub extern "C" fn yrs_doc_insert_block(
    doc: *mut YrsDoc,
    index: u32,
    block_type: *const c_char,
    content: *const c_char,
    props_json: *const c_char,
    out_len: *mut u32,
) -> *mut u8 {
    let doc = unsafe { &mut *doc };
    let block_type_str = unsafe { CStr::from_ptr(block_type) }.to_str().unwrap_or("paragraph");
    let content_str = if content.is_null() {
        ""
    } else {
        unsafe { CStr::from_ptr(content) }.to_str().unwrap_or("")
    };

    // Capture the update generated by this transaction
    let sv_before = {
        let txn = doc.doc.transact();
        txn.state_vector()
    };

    {
        let mut txn = doc.doc.transact_mut();
        let fragment: XmlFragmentRef = txn.get_or_insert_xml_fragment("document-store");

        // Insert an XmlElement for the block
        let elem = fragment.insert(&mut txn, index, yrs::XmlElementPrelim::empty(block_type_str));

        // Insert text content
        if !content_str.is_empty() {
            let text = elem.insert(&mut txn, 0, yrs::XmlTextPrelim::new(""));
            text.insert(&mut txn, 0, content_str);
        }

        // Parse and apply props if provided
        if !props_json.is_null() {
            let props_str = unsafe { CStr::from_ptr(props_json) }.to_str().unwrap_or("{}");
            if let Ok(props) = serde_json::from_str::<serde_json::Value>(props_str) {
                if let Some(obj) = props.as_object() {
                    for (key, value) in obj {
                        let val_str = match value {
                            serde_json::Value::String(s) => s.clone(),
                            other => other.to_string(),
                        };
                        elem.insert_attribute(&mut txn, key.as_str(), val_str.as_str());
                    }
                }
            }
        }
    }

    // Encode the diff
    let txn = doc.doc.transact();
    let update = txn.encode_diff_v1(&sv_before);
    unsafe { *out_len = update.len() as u32 };
    let ptr = update.as_ptr() as *mut u8;
    std::mem::forget(update);
    ptr
}

/// Delete a block at the given index.
/// Returns the generated update bytes. Caller must free with yrs_free_bytes.
#[no_mangle]
pub extern "C" fn yrs_doc_delete_block(
    doc: *mut YrsDoc,
    index: u32,
    out_len: *mut u32,
) -> *mut u8 {
    let doc = unsafe { &mut *doc };

    let sv_before = {
        let txn = doc.doc.transact();
        txn.state_vector()
    };

    {
        let mut txn = doc.doc.transact_mut();
        let fragment: XmlFragmentRef = txn.get_or_insert_xml_fragment("document-store");
        fragment.remove_range(&mut txn, index, 1);
    }

    let txn = doc.doc.transact();
    let update = txn.encode_diff_v1(&sv_before);
    unsafe { *out_len = update.len() as u32 };
    let ptr = update.as_ptr() as *mut u8;
    std::mem::forget(update);
    ptr
}

// --- Memory Management ---

/// Free bytes allocated by this library.
#[no_mangle]
pub extern "C" fn yrs_free_bytes(ptr: *mut u8, len: u32) {
    if !ptr.is_null() && len > 0 {
        unsafe {
            let _ = Vec::from_raw_parts(ptr, len as usize, len as usize);
        }
    }
}

/// Free a C string allocated by this library.
#[no_mangle]
pub extern "C" fn yrs_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}
```

**Step 2: Build the Rust library**

Run: `cd yrs-bridge && cargo build --release`
Expected: Compiles successfully, produces `target/release/libyrs_bridge.dylib` (macOS) or `.so` (Linux)

**Step 3: Commit**

```bash
git add yrs-bridge/
git commit -m "feat: implement Yrs Rust bridge with C ABI for sync and block operations"
```

---

### Task 3: Java FFM Bridge

**Files:**
- Create: `backend/src/main/java/com/collabdoc/yrs/YrsBridge.java`
- Create: `backend/src/main/java/com/collabdoc/yrs/YrsDocument.java`
- Create: `backend/src/test/java/com/collabdoc/yrs/YrsBridgeTest.java`

**Step 1: Implement the Java FFM bridge**

```java
// backend/src/main/java/com/collabdoc/yrs/YrsBridge.java
package com.collabdoc.yrs;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.*;

/**
 * FFM bridge to the native Yrs Rust library.
 * Loads libyrs_bridge and provides method handles for all C ABI functions.
 */
public class YrsBridge implements AutoCloseable {

    private final Arena libraryArena;
    final MethodHandle docNew;
    final MethodHandle docDestroy;
    final MethodHandle docStateVector;
    final MethodHandle docEncodeDiff;
    final MethodHandle docEncodeState;
    final MethodHandle docApplyUpdate;
    final MethodHandle docLoadState;
    final MethodHandle docGetBlocksJson;
    final MethodHandle docInsertBlock;
    final MethodHandle docDeleteBlock;
    final MethodHandle freeBytes;
    final MethodHandle freeString;

    public YrsBridge(String libraryPath) {
        this.libraryArena = Arena.ofShared();
        var linker = Linker.nativeLinker();
        var lookup = SymbolLookup.libraryLookup(Path.of(libraryPath), libraryArena);

        // YrsDoc* yrs_doc_new()
        docNew = linker.downcallHandle(
            lookup.find("yrs_doc_new").orElseThrow(),
            FunctionDescriptor.of(ADDRESS)
        );

        // void yrs_doc_destroy(YrsDoc*)
        docDestroy = linker.downcallHandle(
            lookup.find("yrs_doc_destroy").orElseThrow(),
            FunctionDescriptor.ofVoid(ADDRESS)
        );

        // uint8_t* yrs_doc_state_vector(YrsDoc*, uint32_t*)
        docStateVector = linker.downcallHandle(
            lookup.find("yrs_doc_state_vector").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)
        );

        // uint8_t* yrs_doc_encode_diff(YrsDoc*, uint8_t*, uint32_t, uint32_t*)
        docEncodeDiff = linker.downcallHandle(
            lookup.find("yrs_doc_encode_diff").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        );

        // uint8_t* yrs_doc_encode_state(YrsDoc*, uint32_t*)
        docEncodeState = linker.downcallHandle(
            lookup.find("yrs_doc_encode_state").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)
        );

        // uint8_t* yrs_doc_apply_update(YrsDoc*, uint8_t*, uint32_t, uint32_t*)
        docApplyUpdate = linker.downcallHandle(
            lookup.find("yrs_doc_apply_update").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        );

        // int32_t yrs_doc_load_state(YrsDoc*, uint8_t*, uint32_t)
        docLoadState = linker.downcallHandle(
            lookup.find("yrs_doc_load_state").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
        );

        // char* yrs_doc_get_blocks_json(YrsDoc*)
        docGetBlocksJson = linker.downcallHandle(
            lookup.find("yrs_doc_get_blocks_json").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS)
        );

        // uint8_t* yrs_doc_insert_block(YrsDoc*, uint32_t, char*, char*, char*, uint32_t*)
        docInsertBlock = linker.downcallHandle(
            lookup.find("yrs_doc_insert_block").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
        );

        // uint8_t* yrs_doc_delete_block(YrsDoc*, uint32_t, uint32_t*)
        docDeleteBlock = linker.downcallHandle(
            lookup.find("yrs_doc_delete_block").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
        );

        // void yrs_free_bytes(uint8_t*, uint32_t)
        freeBytes = linker.downcallHandle(
            lookup.find("yrs_free_bytes").orElseThrow(),
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT)
        );

        // void yrs_free_string(char*)
        freeString = linker.downcallHandle(
            lookup.find("yrs_free_string").orElseThrow(),
            FunctionDescriptor.ofVoid(ADDRESS)
        );
    }

    public YrsDocument createDocument() {
        return new YrsDocument(this);
    }

    @Override
    public void close() {
        libraryArena.close();
    }
}
```

```java
// backend/src/main/java/com/collabdoc/yrs/YrsDocument.java
package com.collabdoc.yrs;

import java.lang.foreign.*;

import static java.lang.foreign.ValueLayout.*;

/**
 * Wrapper around a single Yrs document instance.
 * NOT thread-safe -- callers must synchronize externally.
 */
public class YrsDocument implements AutoCloseable {

    private final YrsBridge bridge;
    private MemorySegment docPtr;

    YrsDocument(YrsBridge bridge) {
        this.bridge = bridge;
        try {
            this.docPtr = (MemorySegment) bridge.docNew.invoke();
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create Yrs document", e);
        }
    }

    /** Get the state vector (V1 encoded). */
    public byte[] getStateVector() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = arena.allocate(JAVA_INT);
            MemorySegment ptr = (MemorySegment) bridge.docStateVector.invoke(docPtr, outLen);
            int len = outLen.get(JAVA_INT, 0);
            if (ptr.equals(MemorySegment.NULL) || len == 0) return new byte[0];
            byte[] result = ptr.reinterpret(len).toArray(JAVA_BYTE);
            bridge.freeBytes.invoke(ptr, len);
            return result;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get state vector", e);
        }
    }

    /** Compute update diff from a remote state vector. */
    public byte[] encodeDiff(byte[] stateVector) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment svSeg = stateVector != null && stateVector.length > 0
                ? arena.allocateFrom(JAVA_BYTE, stateVector)
                : MemorySegment.NULL;
            MemorySegment outLen = arena.allocate(JAVA_INT);
            MemorySegment ptr = (MemorySegment) bridge.docEncodeDiff.invoke(
                docPtr, svSeg, stateVector != null ? stateVector.length : 0, outLen
            );
            int len = outLen.get(JAVA_INT, 0);
            if (ptr.equals(MemorySegment.NULL) || len == 0) return new byte[0];
            byte[] result = ptr.reinterpret(len).toArray(JAVA_BYTE);
            bridge.freeBytes.invoke(ptr, len);
            return result;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to encode diff", e);
        }
    }

    /** Encode the full document state as a snapshot. */
    public byte[] encodeState() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = arena.allocate(JAVA_INT);
            MemorySegment ptr = (MemorySegment) bridge.docEncodeState.invoke(docPtr, outLen);
            int len = outLen.get(JAVA_INT, 0);
            if (ptr.equals(MemorySegment.NULL) || len == 0) return new byte[0];
            byte[] result = ptr.reinterpret(len).toArray(JAVA_BYTE);
            bridge.freeBytes.invoke(ptr, len);
            return result;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to encode state", e);
        }
    }

    /** Apply a V1-encoded update. Returns the update bytes (for broadcasting). */
    public byte[] applyUpdate(byte[] update) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment updateSeg = arena.allocateFrom(JAVA_BYTE, update);
            MemorySegment outLen = arena.allocate(JAVA_INT);
            MemorySegment ptr = (MemorySegment) bridge.docApplyUpdate.invoke(
                docPtr, updateSeg, update.length, outLen
            );
            int len = outLen.get(JAVA_INT, 0);
            if (ptr.equals(MemorySegment.NULL) || len == 0) return null;
            byte[] result = ptr.reinterpret(len).toArray(JAVA_BYTE);
            bridge.freeBytes.invoke(ptr, len);
            return result;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to apply update", e);
        }
    }

    /** Load document from a full state snapshot. */
    public void loadState(byte[] state) {
        if (state == null || state.length == 0) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment stateSeg = arena.allocateFrom(JAVA_BYTE, state);
            int result = (int) bridge.docLoadState.invoke(docPtr, stateSeg, state.length);
            if (result != 0) {
                throw new RuntimeException("Failed to load state, error code: " + result);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to load state", e);
        }
    }

    /** Get document content as XML string. */
    public String getBlocksXml() {
        try {
            MemorySegment ptr = (MemorySegment) bridge.docGetBlocksJson.invoke(docPtr);
            if (ptr.equals(MemorySegment.NULL)) return "";
            String result = ptr.reinterpret(Long.MAX_VALUE).getString(0);
            bridge.freeString.invoke(ptr);
            return result;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to get blocks", e);
        }
    }

    /** Insert a block at the given index. Returns the Yjs update to broadcast. */
    public byte[] insertBlock(int index, String blockType, String content, String propsJson) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment typeSeg = arena.allocateFrom(blockType);
            MemorySegment contentSeg = content != null ? arena.allocateFrom(content) : MemorySegment.NULL;
            MemorySegment propsSeg = propsJson != null ? arena.allocateFrom(propsJson) : MemorySegment.NULL;
            MemorySegment outLen = arena.allocate(JAVA_INT);
            MemorySegment ptr = (MemorySegment) bridge.docInsertBlock.invoke(
                docPtr, index, typeSeg, contentSeg, propsSeg, outLen
            );
            int len = outLen.get(JAVA_INT, 0);
            if (ptr.equals(MemorySegment.NULL) || len == 0) return null;
            byte[] result = ptr.reinterpret(len).toArray(JAVA_BYTE);
            bridge.freeBytes.invoke(ptr, len);
            return result;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to insert block", e);
        }
    }

    /** Delete a block at the given index. Returns the Yjs update to broadcast. */
    public byte[] deleteBlock(int index) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = arena.allocate(JAVA_INT);
            MemorySegment ptr = (MemorySegment) bridge.docDeleteBlock.invoke(
                docPtr, index, outLen
            );
            int len = outLen.get(JAVA_INT, 0);
            if (ptr.equals(MemorySegment.NULL) || len == 0) return null;
            byte[] result = ptr.reinterpret(len).toArray(JAVA_BYTE);
            bridge.freeBytes.invoke(ptr, len);
            return result;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to delete block", e);
        }
    }

    @Override
    public void close() {
        if (docPtr != null && !docPtr.equals(MemorySegment.NULL)) {
            try {
                bridge.docDestroy.invoke(docPtr);
            } catch (Throwable e) {
                throw new RuntimeException("Failed to destroy document", e);
            }
            docPtr = MemorySegment.NULL;
        }
    }
}
```

**Step 2: Write a test**

```java
// backend/src/test/java/com/collabdoc/yrs/YrsBridgeTest.java
package com.collabdoc.yrs;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YrsBridgeTest {

    static YrsBridge bridge;

    @BeforeAll
    static void setUp() {
        String os = System.getProperty("os.name").toLowerCase();
        String libName = os.contains("mac") ? "libyrs_bridge.dylib" : "libyrs_bridge.so";
        String libPath = System.getProperty("yrs.bridge.lib",
            "../yrs-bridge/target/release/" + libName);
        bridge = new YrsBridge(libPath);
    }

    @AfterAll
    static void tearDown() {
        bridge.close();
    }

    @Test
    void createAndDestroyDocument() {
        try (var doc = bridge.createDocument()) {
            assertNotNull(doc);
        }
    }

    @Test
    void emptyDocumentStateVector() {
        try (var doc = bridge.createDocument()) {
            byte[] sv = doc.getStateVector();
            assertNotNull(sv);
            assertTrue(sv.length > 0);
        }
    }

    @Test
    void insertBlockAndEncode() {
        try (var doc = bridge.createDocument()) {
            byte[] update = doc.insertBlock(0, "paragraph", "Hello world", null);
            assertNotNull(update);
            assertTrue(update.length > 0);

            String xml = doc.getBlocksXml();
            assertNotNull(xml);
        }
    }

    @Test
    void syncTwoDocuments() {
        try (var doc1 = bridge.createDocument();
             var doc2 = bridge.createDocument()) {

            // Insert content into doc1
            doc1.insertBlock(0, "paragraph", "Hello", null);

            // Sync doc1 -> doc2
            byte[] sv2 = doc2.getStateVector();
            byte[] diff = doc1.encodeDiff(sv2);
            doc2.applyUpdate(diff);

            // Both documents should have the same state
            byte[] state1 = doc1.encodeState();
            byte[] state2 = doc2.encodeState();
            assertArrayEquals(state1, state2);
        }
    }

    @Test
    void loadStateFromSnapshot() {
        byte[] snapshot;
        try (var doc1 = bridge.createDocument()) {
            doc1.insertBlock(0, "heading", "Title", "{\"level\": 1}");
            doc1.insertBlock(1, "paragraph", "Content", null);
            snapshot = doc1.encodeState();
        }

        try (var doc2 = bridge.createDocument()) {
            doc2.loadState(snapshot);
            String xml = doc2.getBlocksXml();
            assertNotNull(xml);
        }
    }
}
```

**Step 3: Build Rust and run test**

Run: `cd yrs-bridge && cargo build --release`
Run: `cd backend && ./gradlew test --tests "com.collabdoc.yrs.YrsBridgeTest" -Dyrs.bridge.lib=../yrs-bridge/target/release/libyrs_bridge.dylib`
Expected: All tests pass

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/yrs/ backend/src/test/java/com/collabdoc/yrs/
git commit -m "feat: Java FFM bridge to Yrs Rust library with sync and block operations"
```

---

### Task 4: Database Layer

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Create: `backend/src/main/java/com/collabdoc/model/Document.java`
- Create: `backend/src/main/java/com/collabdoc/model/DocumentUpdate.java`
- Create: `backend/src/main/java/com/collabdoc/model/DocumentSnapshot.java`
- Create: `backend/src/main/java/com/collabdoc/repository/DocumentRepository.java`
- Create: `backend/src/main/java/com/collabdoc/repository/DocumentUpdateRepository.java`
- Create: `backend/src/main/java/com/collabdoc/repository/DocumentSnapshotRepository.java`

Add Flyway dependency to `backend/build.gradle.kts`:

```kotlin
// Add to dependencies block:
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
```

**Step 1: Create Flyway migration**

```sql
-- backend/src/main/resources/db/migration/V1__init.sql
CREATE TABLE documents (
    id          UUID PRIMARY KEY,
    title       VARCHAR(500) NOT NULL DEFAULT 'Untitled',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_updates (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    update_data BYTEA NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_updates_doc_id ON document_updates(doc_id);

CREATE TABLE document_snapshots (
    doc_id      UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    state_data  BYTEA NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Step 2: Create JPA entities**

```java
// backend/src/main/java/com/collabdoc/model/Document.java
package com.collabdoc.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "documents")
public class Document {
    @Id
    private UUID id;

    private String title;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Document() {}

    public Document(String title) {
        this.id = UUID.randomUUID();
        this.title = title;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

```java
// backend/src/main/java/com/collabdoc/model/DocumentUpdate.java
package com.collabdoc.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_updates")
public class DocumentUpdate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id")
    private UUID docId;

    @Column(name = "update_data")
    private byte[] updateData;

    @Column(name = "created_at")
    private Instant createdAt;

    protected DocumentUpdate() {}

    public DocumentUpdate(UUID docId, byte[] updateData) {
        this.docId = docId;
        this.updateData = updateData;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getDocId() { return docId; }
    public byte[] getUpdateData() { return updateData; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
// backend/src/main/java/com/collabdoc/model/DocumentSnapshot.java
package com.collabdoc.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "document_snapshots")
public class DocumentSnapshot {
    @Id
    @Column(name = "doc_id")
    private UUID docId;

    @Column(name = "state_data")
    private byte[] stateData;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected DocumentSnapshot() {}

    public DocumentSnapshot(UUID docId, byte[] stateData) {
        this.docId = docId;
        this.stateData = stateData;
        this.updatedAt = Instant.now();
    }

    public UUID getDocId() { return docId; }
    public byte[] getStateData() { return stateData; }
    public void setStateData(byte[] stateData) {
        this.stateData = stateData;
        this.updatedAt = Instant.now();
    }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

**Step 3: Create repositories**

```java
// backend/src/main/java/com/collabdoc/repository/DocumentRepository.java
package com.collabdoc.repository;

import com.collabdoc.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {
}
```

```java
// backend/src/main/java/com/collabdoc/repository/DocumentUpdateRepository.java
package com.collabdoc.repository;

import com.collabdoc.model.DocumentUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface DocumentUpdateRepository extends JpaRepository<DocumentUpdate, Long> {
    List<DocumentUpdate> findByDocIdOrderByIdAsc(UUID docId);
    void deleteByDocId(UUID docId);
}
```

```java
// backend/src/main/java/com/collabdoc/repository/DocumentSnapshotRepository.java
package com.collabdoc.repository;

import com.collabdoc.model.DocumentSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DocumentSnapshotRepository extends JpaRepository<DocumentSnapshot, UUID> {
}
```

**Step 4: Verify migration runs**

Run: `docker compose up -d` (ensure PostgreSQL is running)
Run: `cd backend && ./gradlew bootRun` (should start and run Flyway migration)
Expected: Application starts, tables created in PostgreSQL

**Step 5: Commit**

```bash
git add backend/
git commit -m "feat: database schema with Flyway migration and JPA entities"
```

---

### Task 5: Document Service & Yrs Document Manager

**Files:**
- Create: `backend/src/main/java/com/collabdoc/config/YrsConfig.java`
- Create: `backend/src/main/java/com/collabdoc/service/YrsDocumentManager.java`
- Create: `backend/src/main/java/com/collabdoc/service/DocumentService.java`

**Step 1: Create Yrs configuration**

```java
// backend/src/main/java/com/collabdoc/config/YrsConfig.java
package com.collabdoc.config;

import com.collabdoc.yrs.YrsBridge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class YrsConfig {
    @Bean(destroyMethod = "close")
    public YrsBridge yrsBridge(@Value("${collabdoc.yrs-bridge.library-path}") String libraryPath) {
        return new YrsBridge(libraryPath);
    }
}
```

**Step 2: Implement YrsDocumentManager**

```java
// backend/src/main/java/com/collabdoc/service/YrsDocumentManager.java
package com.collabdoc.service;

import com.collabdoc.repository.DocumentSnapshotRepository;
import com.collabdoc.repository.DocumentUpdateRepository;
import com.collabdoc.model.DocumentSnapshot;
import com.collabdoc.model.DocumentUpdate;
import com.collabdoc.yrs.YrsBridge;
import com.collabdoc.yrs.YrsDocument;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages in-memory Yrs document instances, one per active document.
 * Handles loading from DB, persisting updates, and providing
 * document references for WebSocket sync and Agent API.
 */
@Service
public class YrsDocumentManager {

    private final YrsBridge bridge;
    private final DocumentUpdateRepository updateRepository;
    private final DocumentSnapshotRepository snapshotRepository;
    private final ConcurrentHashMap<UUID, YrsDocument> activeDocuments = new ConcurrentHashMap<>();

    public YrsDocumentManager(YrsBridge bridge,
                              DocumentUpdateRepository updateRepository,
                              DocumentSnapshotRepository snapshotRepository) {
        this.bridge = bridge;
        this.updateRepository = updateRepository;
        this.snapshotRepository = snapshotRepository;
    }

    /** Get or load a Yrs document. Loads from DB snapshot + incremental updates. */
    public synchronized YrsDocument getOrLoadDocument(UUID docId) {
        return activeDocuments.computeIfAbsent(docId, id -> {
            var doc = bridge.createDocument();

            // Load snapshot if exists
            snapshotRepository.findById(id).ifPresent(snapshot ->
                doc.loadState(snapshot.getStateData())
            );

            // Apply incremental updates on top of snapshot
            var updates = updateRepository.findByDocIdOrderByIdAsc(id);
            for (var update : updates) {
                doc.applyUpdate(update.getUpdateData());
            }

            return doc;
        });
    }

    /** Apply an update from a WebSocket client. Persists and returns the update bytes. */
    public byte[] applyClientUpdate(UUID docId, byte[] update) {
        var doc = getOrLoadDocument(docId);
        synchronized (doc) {
            byte[] result = doc.applyUpdate(update);
            if (result != null) {
                updateRepository.save(new DocumentUpdate(docId, update));
            }
            return result;
        }
    }

    /** Insert a block via Agent API. Returns the Yjs update to broadcast. */
    public byte[] insertBlock(UUID docId, int index, String blockType, String content, String propsJson) {
        var doc = getOrLoadDocument(docId);
        synchronized (doc) {
            byte[] update = doc.insertBlock(index, blockType, content, propsJson);
            if (update != null) {
                updateRepository.save(new DocumentUpdate(docId, update));
            }
            return update;
        }
    }

    /** Delete a block via Agent API. Returns the Yjs update to broadcast. */
    public byte[] deleteBlock(UUID docId, int index) {
        var doc = getOrLoadDocument(docId);
        synchronized (doc) {
            byte[] update = doc.deleteBlock(index);
            if (update != null) {
                updateRepository.save(new DocumentUpdate(docId, update));
            }
            return update;
        }
    }

    /** Get the state vector for sync protocol. */
    public byte[] getStateVector(UUID docId) {
        var doc = getOrLoadDocument(docId);
        synchronized (doc) {
            return doc.getStateVector();
        }
    }

    /** Compute diff from a remote state vector. */
    public byte[] encodeDiff(UUID docId, byte[] remoteStateVector) {
        var doc = getOrLoadDocument(docId);
        synchronized (doc) {
            return doc.encodeDiff(remoteStateVector);
        }
    }

    /** Get document content as XML. */
    public String getBlocksXml(UUID docId) {
        var doc = getOrLoadDocument(docId);
        synchronized (doc) {
            return doc.getBlocksXml();
        }
    }

    /** Create a snapshot and clean up incremental updates. */
    public void createSnapshot(UUID docId) {
        var doc = getOrLoadDocument(docId);
        synchronized (doc) {
            byte[] state = doc.encodeState();
            snapshotRepository.save(new DocumentSnapshot(docId, state));
            updateRepository.deleteByDocId(docId);
        }
    }

    /** Unload a document from memory. */
    public void unloadDocument(UUID docId) {
        var doc = activeDocuments.remove(docId);
        if (doc != null) {
            doc.close();
        }
    }
}
```

**Step 3: Implement DocumentService**

```java
// backend/src/main/java/com/collabdoc/service/DocumentService.java
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

    public Document createDocument(String title) {
        return documentRepository.save(new Document(title));
    }

    public Optional<Document> getDocument(UUID id) {
        return documentRepository.findById(id);
    }

    public List<Document> listDocuments() {
        return documentRepository.findAll();
    }

    public Optional<Document> updateTitle(UUID id, String title) {
        return documentRepository.findById(id).map(doc -> {
            doc.setTitle(title);
            doc.setUpdatedAt(Instant.now());
            return documentRepository.save(doc);
        });
    }

    public void deleteDocument(UUID id) {
        documentRepository.deleteById(id);
    }
}
```

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/config/ backend/src/main/java/com/collabdoc/service/
git commit -m "feat: document service and Yrs document manager with persistence"
```

---

### Task 6: WebSocket Handler (Yjs Sync Protocol)

**Files:**
- Create: `backend/src/main/java/com/collabdoc/config/WebSocketConfig.java`
- Create: `backend/src/main/java/com/collabdoc/websocket/YjsWebSocketHandler.java`
- Create: `backend/src/main/java/com/collabdoc/websocket/YjsSyncProtocol.java`

**Step 1: Implement the Yjs sync protocol message parser**

The y-websocket protocol uses these message types:
- Byte 0: MSG_SYNC (0), Byte 1: sub-type (0=step1, 1=step2, 2=update)
- Byte 0: MSG_AWARENESS (1)

```java
// backend/src/main/java/com/collabdoc/websocket/YjsSyncProtocol.java
package com.collabdoc.websocket;

import java.nio.ByteBuffer;

/**
 * Yjs/y-websocket sync protocol constants and message helpers.
 *
 * Wire format: [msgType: varint] [subType: varint] [payload: bytes]
 * For sync messages: msgType=0, subType=0(step1)/1(step2)/2(update)
 * For awareness: msgType=1
 */
public final class YjsSyncProtocol {

    public static final int MSG_SYNC = 0;
    public static final int MSG_AWARENESS = 1;

    public static final int MSG_SYNC_STEP1 = 0;
    public static final int MSG_SYNC_STEP2 = 1;
    public static final int MSG_SYNC_UPDATE = 2;

    private YjsSyncProtocol() {}

    /** Encode a sync step 1 message (state vector). */
    public static byte[] encodeSyncStep1(byte[] stateVector) {
        // Format: [0] [0] [varint length] [stateVector bytes]
        byte[] lenBytes = encodeVarint(stateVector.length);
        byte[] msg = new byte[2 + lenBytes.length + stateVector.length];
        msg[0] = MSG_SYNC;
        msg[1] = MSG_SYNC_STEP1;
        System.arraycopy(lenBytes, 0, msg, 2, lenBytes.length);
        System.arraycopy(stateVector, 0, msg, 2 + lenBytes.length, stateVector.length);
        return msg;
    }

    /** Encode a sync step 2 message (update). */
    public static byte[] encodeSyncStep2(byte[] update) {
        byte[] lenBytes = encodeVarint(update.length);
        byte[] msg = new byte[2 + lenBytes.length + update.length];
        msg[0] = MSG_SYNC;
        msg[1] = MSG_SYNC_STEP2;
        System.arraycopy(lenBytes, 0, msg, 2, lenBytes.length);
        System.arraycopy(update, 0, msg, 2 + lenBytes.length, update.length);
        return msg;
    }

    /** Encode a sync update message. */
    public static byte[] encodeSyncUpdate(byte[] update) {
        byte[] lenBytes = encodeVarint(update.length);
        byte[] msg = new byte[2 + lenBytes.length + update.length];
        msg[0] = MSG_SYNC;
        msg[1] = MSG_SYNC_UPDATE;
        System.arraycopy(lenBytes, 0, msg, 2, lenBytes.length);
        System.arraycopy(update, 0, msg, 2 + lenBytes.length, update.length);
        return msg;
    }

    /** Read a varint from a ByteBuffer. */
    public static int readVarint(ByteBuffer buf) {
        int result = 0;
        int shift = 0;
        while (buf.hasRemaining()) {
            int b = buf.get() & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
        }
        return result;
    }

    /** Encode an integer as a varint. */
    public static byte[] encodeVarint(int value) {
        if (value < 0) throw new IllegalArgumentException("Negative value");
        byte[] buf = new byte[5]; // max 5 bytes for 32-bit varint
        int pos = 0;
        while (value > 0x7F) {
            buf[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    /** Read payload bytes (length-prefixed) from buffer. */
    public static byte[] readPayload(ByteBuffer buf) {
        int len = readVarint(buf);
        byte[] data = new byte[len];
        buf.get(data);
        return data;
    }
}
```

**Step 2: Implement the WebSocket handler**

```java
// backend/src/main/java/com/collabdoc/websocket/YjsWebSocketHandler.java
package com.collabdoc.websocket;

import com.collabdoc.service.YrsDocumentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler implementing the y-websocket sync protocol.
 * Each document is a "room" -- all sessions in the same room are synced.
 */
public class YjsWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(YjsWebSocketHandler.class);

    private final YrsDocumentManager docManager;

    // docId -> set of sessions
    private final ConcurrentHashMap<UUID, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();
    // sessionId -> docId
    private final ConcurrentHashMap<String, UUID> sessionDocs = new ConcurrentHashMap<>();

    public YjsWebSocketHandler(YrsDocumentManager docManager) {
        this.docManager = docManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // Extract document ID from the URL path: /ws/{docId}
        String path = session.getUri().getPath();
        String docIdStr = path.substring(path.lastIndexOf('/') + 1);
        UUID docId;
        try {
            docId = UUID.fromString(docIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid document ID in WebSocket path: {}", docIdStr);
            try { session.close(); } catch (IOException ignored) {}
            return;
        }

        // Ensure document is loaded in memory
        docManager.getOrLoadDocument(docId);

        // Add session to room
        rooms.computeIfAbsent(docId, k -> ConcurrentHashMap.newKeySet()).add(session);
        sessionDocs.put(session.getId(), docId);

        log.info("WebSocket connected: session={}, doc={}", session.getId(), docId);

        // Send sync step 1 (server's state vector) to the client
        try {
            byte[] sv = docManager.getStateVector(docId);
            byte[] msg = YjsSyncProtocol.encodeSyncStep1(sv);
            session.sendMessage(new BinaryMessage(msg));
        } catch (Exception e) {
            log.error("Failed to send initial sync", e);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        UUID docId = sessionDocs.get(session.getId());
        if (docId == null) return;

        ByteBuffer buf = ByteBuffer.wrap(message.getPayload().array());
        if (!buf.hasRemaining()) return;

        int msgType = buf.get() & 0xFF;

        if (msgType == YjsSyncProtocol.MSG_SYNC) {
            handleSyncMessage(session, docId, buf);
        } else if (msgType == YjsSyncProtocol.MSG_AWARENESS) {
            // Broadcast awareness messages to all other sessions in the room
            broadcastToOthers(docId, session, message.getPayload().array());
        }
    }

    private void handleSyncMessage(WebSocketSession session, UUID docId, ByteBuffer buf) {
        if (!buf.hasRemaining()) return;
        int subType = buf.get() & 0xFF;

        switch (subType) {
            case YjsSyncProtocol.MSG_SYNC_STEP1 -> {
                // Client sends its state vector; respond with diff
                byte[] clientSv = YjsSyncProtocol.readPayload(buf);
                byte[] diff = docManager.encodeDiff(docId, clientSv);
                byte[] response = YjsSyncProtocol.encodeSyncStep2(diff);
                sendToSession(session, response);
            }
            case YjsSyncProtocol.MSG_SYNC_STEP2, YjsSyncProtocol.MSG_SYNC_UPDATE -> {
                // Client sends an update; apply and broadcast
                byte[] update = YjsSyncProtocol.readPayload(buf);
                byte[] applied = docManager.applyClientUpdate(docId, update);
                if (applied != null) {
                    // Broadcast the update to all OTHER clients
                    byte[] broadcastMsg = YjsSyncProtocol.encodeSyncUpdate(update);
                    broadcastToOthers(docId, session, broadcastMsg);
                }
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID docId = sessionDocs.remove(session.getId());
        if (docId != null) {
            Set<WebSocketSession> sessions = rooms.get(docId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    rooms.remove(docId);
                    // Optionally create snapshot and unload document
                    docManager.createSnapshot(docId);
                    docManager.unloadDocument(docId);
                }
            }
        }
        log.info("WebSocket disconnected: session={}, doc={}", session.getId(), docId);
    }

    /** Broadcast a Yjs update to all WebSocket sessions of a document (from Agent API). */
    public void broadcastUpdate(UUID docId, byte[] update) {
        byte[] msg = YjsSyncProtocol.encodeSyncUpdate(update);
        Set<WebSocketSession> sessions = rooms.get(docId);
        if (sessions != null) {
            for (WebSocketSession s : sessions) {
                sendToSession(s, msg);
            }
        }
    }

    private void broadcastToOthers(UUID docId, WebSocketSession sender, byte[] data) {
        Set<WebSocketSession> sessions = rooms.get(docId);
        if (sessions == null) return;
        for (WebSocketSession s : sessions) {
            if (!s.getId().equals(sender.getId()) && s.isOpen()) {
                sendToSession(s, data);
            }
        }
    }

    private void sendToSession(WebSocketSession session, byte[] data) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new BinaryMessage(data));
            }
        } catch (IOException e) {
            log.warn("Failed to send WebSocket message to session {}", session.getId(), e);
        }
    }
}
```

**Step 3: Configure WebSocket**

```java
// backend/src/main/java/com/collabdoc/config/WebSocketConfig.java
package com.collabdoc.config;

import com.collabdoc.service.YrsDocumentManager;
import com.collabdoc.websocket.YjsWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final YrsDocumentManager docManager;

    public WebSocketConfig(YrsDocumentManager docManager) {
        this.docManager = docManager;
    }

    @Bean
    public YjsWebSocketHandler yjsWebSocketHandler() {
        return new YjsWebSocketHandler(docManager);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(yjsWebSocketHandler(), "/ws/*")
                .setAllowedOrigins("*");
    }
}
```

**Step 4: Commit**

```bash
git add backend/src/main/java/com/collabdoc/websocket/ backend/src/main/java/com/collabdoc/config/WebSocketConfig.java
git commit -m "feat: WebSocket handler implementing Yjs sync protocol"
```

---

### Task 7: REST API Controllers

**Files:**
- Create: `backend/src/main/java/com/collabdoc/controller/DocumentController.java`
- Create: `backend/src/main/java/com/collabdoc/controller/BlockController.java`
- Create: `backend/src/main/java/com/collabdoc/config/CorsConfig.java`

**Step 1: Implement Document REST controller**

```java
// backend/src/main/java/com/collabdoc/controller/DocumentController.java
package com.collabdoc.controller;

import com.collabdoc.model.Document;
import com.collabdoc.service.DocumentService;
import org.springframework.http.ResponseEntity;
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
    public List<Document> listDocuments() {
        return documentService.listDocuments();
    }

    @PostMapping
    public Document createDocument(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "Untitled");
        return documentService.createDocument(title);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Document> getDocument(@PathVariable UUID id) {
        return documentService.getDocument(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Document> updateDocument(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String title = body.get("title");
        return documentService.updateTitle(id, title)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable UUID id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }
}
```

**Step 2: Implement Block REST controller (Agent API)**

```java
// backend/src/main/java/com/collabdoc/controller/BlockController.java
package com.collabdoc.controller;

import com.collabdoc.service.YrsDocumentManager;
import com.collabdoc.websocket.YjsWebSocketHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs/{docId}/blocks")
public class BlockController {

    private final YrsDocumentManager docManager;
    private final YjsWebSocketHandler wsHandler;

    public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler) {
        this.docManager = docManager;
        this.wsHandler = wsHandler;
    }

    /** Get document content as XML string. */
    @GetMapping
    public ResponseEntity<Map<String, String>> getBlocks(@PathVariable UUID docId) {
        String xml = docManager.getBlocksXml(docId);
        return ResponseEntity.ok(Map.of("content", xml));
    }

    /** Insert a block at the given index. */
    @PostMapping
    public ResponseEntity<Map<String, String>> insertBlock(
            @PathVariable UUID docId,
            @RequestBody Map<String, Object> body) {
        int index = ((Number) body.getOrDefault("index", 0)).intValue();
        String blockType = (String) body.getOrDefault("type", "paragraph");
        String content = (String) body.get("content");
        String propsJson = body.containsKey("props")
            ? new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body.get("props")).toString()
            : null;

        byte[] update = docManager.insertBlock(docId, index, blockType, content, propsJson);
        if (update != null) {
            wsHandler.broadcastUpdate(docId, update);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    /** Delete a block at the given index. */
    @DeleteMapping("/{index}")
    public ResponseEntity<Map<String, String>> deleteBlock(
            @PathVariable UUID docId,
            @PathVariable int index) {
        byte[] update = docManager.deleteBlock(docId, index);
        if (update != null) {
            wsHandler.broadcastUpdate(docId, update);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
```

**Step 3: CORS configuration**

```java
// backend/src/main/java/com/collabdoc/config/CorsConfig.java
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
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH");
            }
        };
    }
}
```

**Step 4: Add Jackson dependency** (already included in spring-boot-starter-web)

**Step 5: Verify backend compiles**

Run: `cd backend && ./gradlew compileJava`
Expected: Compiles successfully

**Step 6: Commit**

```bash
git add backend/src/main/java/com/collabdoc/controller/ backend/src/main/java/com/collabdoc/config/CorsConfig.java
git commit -m "feat: REST API controllers for documents and agent block operations"
```

---

### Task 8: Frontend - Document List & Router

**Files:**
- Modify: `frontend/src/App.tsx`
- Create: `frontend/src/pages/DocumentList.tsx`
- Create: `frontend/src/pages/EditorPage.tsx`
- Create: `frontend/src/api.ts`

**Step 1: Create API helper**

```typescript
// frontend/src/api.ts
const API_BASE = "/api";

export interface DocumentMeta {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export async function listDocuments(): Promise<DocumentMeta[]> {
  const res = await fetch(`${API_BASE}/docs`);
  return res.json();
}

export async function createDocument(title: string): Promise<DocumentMeta> {
  const res = await fetch(`${API_BASE}/docs`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ title }),
  });
  return res.json();
}

export async function deleteDocument(id: string): Promise<void> {
  await fetch(`${API_BASE}/docs/${id}`, { method: "DELETE" });
}
```

**Step 2: Create Document List page**

```tsx
// frontend/src/pages/DocumentList.tsx
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { listDocuments, createDocument, deleteDocument, DocumentMeta } from "../api";

export default function DocumentList() {
  const [docs, setDocs] = useState<DocumentMeta[]>([]);
  const navigate = useNavigate();

  useEffect(() => {
    listDocuments().then(setDocs);
  }, []);

  const handleCreate = async () => {
    const doc = await createDocument("Untitled");
    navigate(`/doc/${doc.id}`);
  };

  const handleDelete = async (id: string) => {
    await deleteDocument(id);
    setDocs(docs.filter((d) => d.id !== id));
  };

  return (
    <div style={{ maxWidth: 800, margin: "0 auto", padding: 24 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1>Documents</h1>
        <button onClick={handleCreate} style={{ padding: "8px 16px", fontSize: 16 }}>
          + New Document
        </button>
      </div>
      <div style={{ marginTop: 16 }}>
        {docs.length === 0 && <p>No documents yet. Create one to get started.</p>}
        {docs.map((doc) => (
          <div
            key={doc.id}
            style={{
              display: "flex",
              justifyContent: "space-between",
              padding: "12px 16px",
              borderBottom: "1px solid #eee",
              cursor: "pointer",
            }}
          >
            <div onClick={() => navigate(`/doc/${doc.id}`)}>
              <strong>{doc.title}</strong>
              <div style={{ fontSize: 12, color: "#888" }}>
                {new Date(doc.updatedAt).toLocaleString()}
              </div>
            </div>
            <button onClick={() => handleDelete(doc.id)} style={{ color: "red" }}>
              Delete
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}
```

**Step 3: Create Editor page placeholder**

```tsx
// frontend/src/pages/EditorPage.tsx
import { useParams } from "react-router-dom";

export default function EditorPage() {
  const { docId } = useParams<{ docId: string }>();
  return (
    <div style={{ maxWidth: 900, margin: "0 auto", padding: 24 }}>
      <h2>Editor: {docId}</h2>
      <p>BlockNote editor will go here (Task 9)</p>
    </div>
  );
}
```

**Step 4: Set up router in App.tsx**

```tsx
// frontend/src/App.tsx
import { BrowserRouter, Routes, Route } from "react-router-dom";
import DocumentList from "./pages/DocumentList";
import EditorPage from "./pages/EditorPage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<DocumentList />} />
        <Route path="/doc/:docId" element={<EditorPage />} />
      </Routes>
    </BrowserRouter>
  );
}
```

**Step 5: Verify build**

Run: `cd frontend && npm install && npm run build`
Expected: Compiles without errors

**Step 6: Commit**

```bash
git add frontend/
git commit -m "feat: frontend scaffold with document list page and router"
```

---

### Task 9: BlockNote Editor with Yjs Collaboration

**Files:**
- Modify: `frontend/src/pages/EditorPage.tsx`
- Create: `frontend/src/components/CollaborativeEditor.tsx`
- Create: `frontend/src/hooks/useCollaboration.ts`

**Step 1: Create the Yjs collaboration hook**

```typescript
// frontend/src/hooks/useCollaboration.ts
import { useMemo, useEffect, useState } from "react";
import * as Y from "yjs";
import { WebsocketProvider } from "y-websocket";

const COLORS = [
  "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4",
  "#FFEAA7", "#DDA0DD", "#98D8C8", "#F7DC6F",
];

function randomName(): string {
  const adjectives = ["Swift", "Calm", "Bold", "Keen", "Warm"];
  const nouns = ["Fox", "Owl", "Bear", "Wolf", "Deer"];
  return `${adjectives[Math.floor(Math.random() * adjectives.length)]} ${nouns[Math.floor(Math.random() * nouns.length)]}`;
}

export function useCollaboration(docId: string) {
  const [synced, setSynced] = useState(false);

  const { doc, provider, fragment, user } = useMemo(() => {
    const doc = new Y.Doc();
    const wsUrl = `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`;
    const provider = new WebsocketProvider(wsUrl, docId, doc);
    const fragment = doc.getXmlFragment("document-store");
    const user = {
      name: randomName(),
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
    };
    return { doc, provider, fragment, user };
  }, [docId]);

  useEffect(() => {
    const onSync = (isSynced: boolean) => setSynced(isSynced);
    provider.on("sync", onSync);
    return () => {
      provider.off("sync", onSync);
      provider.destroy();
      doc.destroy();
    };
  }, [provider, doc]);

  return { doc, provider, fragment, user, synced };
}
```

**Step 2: Create the collaborative editor component**

```tsx
// frontend/src/components/CollaborativeEditor.tsx
import "@blocknote/mantine/style.css";
import { useCreateBlockNote } from "@blocknote/react";
import { BlockNoteView } from "@blocknote/mantine";
import { useCollaboration } from "../hooks/useCollaboration";

interface CollaborativeEditorProps {
  docId: string;
}

export default function CollaborativeEditor({ docId }: CollaborativeEditorProps) {
  const { provider, fragment, user, synced } = useCollaboration(docId);

  const editor = useCreateBlockNote({
    collaboration: {
      provider,
      fragment,
      user,
    },
  });

  if (!synced) {
    return <div style={{ padding: 24, color: "#888" }}>Connecting...</div>;
  }

  return (
    <div style={{ minHeight: "70vh" }}>
      <BlockNoteView editor={editor} />
    </div>
  );
}
```

**Step 3: Wire up the editor page**

```tsx
// frontend/src/pages/EditorPage.tsx
import { useParams, Link } from "react-router-dom";
import CollaborativeEditor from "../components/CollaborativeEditor";

export default function EditorPage() {
  const { docId } = useParams<{ docId: string }>();

  if (!docId) return <p>No document ID</p>;

  return (
    <div style={{ maxWidth: 900, margin: "0 auto", padding: 24 }}>
      <div style={{ marginBottom: 16 }}>
        <Link to="/" style={{ textDecoration: "none", color: "#666" }}>
          &larr; Back to documents
        </Link>
      </div>
      <CollaborativeEditor docId={docId} />
    </div>
  );
}
```

**Step 4: Verify build**

Run: `cd frontend && npm run build`
Expected: Compiles without errors

**Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: BlockNote collaborative editor with Yjs WebSocket sync"
```

---

### Task 10: End-to-End Integration & Smoke Test

**Step 1: Build all components**

```bash
# Build Rust bridge
cd yrs-bridge && cargo build --release && cd ..

# Start PostgreSQL
docker compose up -d

# Build and start backend
cd backend && ./gradlew bootRun &

# Start frontend dev server
cd frontend && npm run dev &
```

**Step 2: Smoke test checklist**

1. Open http://localhost:3000 -- document list loads
2. Click "+ New Document" -- new document created, redirected to editor
3. Type some text in the editor -- text appears
4. Open same URL in a second browser tab -- both tabs show same content
5. Type in one tab -- changes appear in the other tab in real-time
6. Test Agent API:
   ```bash
   # Create a document
   curl -X POST http://localhost:8080/api/docs -H "Content-Type: application/json" -d '{"title":"Agent Test"}'

   # Insert a block (use the returned document ID)
   curl -X POST http://localhost:8080/api/docs/{docId}/blocks -H "Content-Type: application/json" -d '{"index":0,"type":"paragraph","content":"Hello from Agent!"}'

   # Get blocks
   curl http://localhost:8080/api/docs/{docId}/blocks
   ```
7. Open the Agent-created document in the browser -- content appears
8. Edit in browser -- Agent API reflects changes

**Step 3: Fix any issues found during smoke testing**

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat: end-to-end integration of collaborative document editor"
```

---

## Execution Dependencies

```
Task 1 (Scaffolding) ──────┐
                            ├── Task 4 (Database) ──────┐
Task 2 (Rust Bridge) ──┐   │                            │
                        ├── Task 3 (Java FFM) ──────────┼── Task 5 (Services) ── Task 6 (WebSocket) ── Task 7 (REST API) ── Task 10 (Integration)
                        │                               │
Task 8 (Frontend) ──────┼───────────────────────────────┼── Task 9 (BlockNote) ──────────────────────────────────────────── Task 10
                        │
                        └── (can run in parallel with Tasks 4-7)
```

**Parallelizable:**
- Task 2 (Rust) + Task 4 (Database) + Task 8 (Frontend) can all run in parallel after Task 1
- Task 3 depends on Task 2
- Tasks 5-7 are sequential
- Task 9 depends on Task 8
- Task 10 depends on everything
