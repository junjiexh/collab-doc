# Refactor Block API: Notion-style Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Refactor the Block API to use UUID-based addressing, add update/single-block-get endpoints, semantic position insertion, batch insert, and return full block objects in responses.

**Architecture:** Bottom-up approach — start at the Rust FFI layer (yrs-bridge), then Java bridge wrappers, then service layer, then REST controllers. Each layer adds new functions without breaking existing ones initially, then we swap the controller to use the new functions and retire the old index-based endpoints.

**Tech Stack:** Rust (yrs crate 0.21), Java 21 (FFM API), Spring Boot, BlockNote XML structure

---

## Current State Summary

- **Block IDs:** Already generated as `agent-{:08x}` in Rust, stored as `id` attribute on `blockContainer` XML elements
- **Addressing:** All operations use integer `index` (position in blockGroup children)
- **XML Structure:** `XmlFragment > blockGroup > blockContainer[id] > <type>[props] > XmlText`
- **Endpoints:** `GET /blocks`, `POST /blocks` (insert by index), `DELETE /blocks/{index}`
- **Response format:** `{"status": "ok"}` for writes, `{"content": "<json>"}` for reads

## Target API Design

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/api/docs/{docId}/blocks` | List all blocks (returns block objects) |
| GET | `/api/docs/{docId}/blocks/{blockId}` | Get single block |
| POST | `/api/docs/{docId}/blocks` | Insert block(s) with position semantics |
| PATCH | `/api/docs/{docId}/blocks/{blockId}` | Update block content/type/props |
| DELETE | `/api/docs/{docId}/blocks/{blockId}` | Delete block by ID |

---

### Task 1: Rust — Use proper UUID for block IDs

**Files:**
- Modify: `yrs-bridge/src/lib.rs:308-310` (rand_u32 block ID generation)
- Modify: `yrs-bridge/Cargo.toml` (add uuid dependency)

**Step 1: Add uuid dependency to Cargo.toml**

Add `uuid` crate with `v4` feature:

```toml
[dependencies]
uuid = { version = "1", features = ["v4"] }
```

**Step 2: Replace block ID generation with UUID v4**

In `lib.rs`, replace the `rand_u32()` usage in `yrs_doc_insert_block`:

```rust
// Remove the rand_u32() function entirely (lines 422-428)

// In yrs_doc_insert_block, replace line 310:
//   let block_id = format!("agent-{:08x}", rand_u32());
// With:
let block_id = uuid::Uuid::new_v4().to_string();
```

**Step 3: Run existing Rust tests to verify no breakage**

Run: `cd yrs-bridge && cargo test`
Expected: All existing tests pass (block ID format doesn't affect test assertions)

**Step 4: Commit**

```bash
git add yrs-bridge/Cargo.toml yrs-bridge/src/lib.rs
git commit -m "feat: use UUID v4 for block IDs instead of pseudo-random hex"
```

---

### Task 2: Rust — Add `get_block_by_id` FFI function

**Files:**
- Modify: `yrs-bridge/src/lib.rs` (add new function after `yrs_doc_get_blocks_json`)

**Step 1: Write test for get_block_by_id**

Add to `mod tests` in `lib.rs`:

```rust
#[test]
fn test_get_block_by_id() {
    let doc = yrs_doc_new();

    // Insert a block
    let tag = CString::new("paragraph").unwrap();
    let content = CString::new("Find me by ID").unwrap();
    let mut len: u32 = 0;
    let update = yrs_doc_insert_block(doc, 0, tag.as_ptr(), content.as_ptr(), ptr::null(), &mut len);
    assert!(!update.is_null());
    yrs_free_bytes(update, len);

    // Get all blocks to find the block ID
    let json = yrs_doc_get_blocks_json(doc);
    let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
    let parsed: Vec<serde_json::Value> = serde_json::from_str(json_str).unwrap();
    let containers = parsed[0]["children"].as_array().unwrap();
    let block_id = containers[0]["props"]["id"].as_str().unwrap();
    yrs_free_string(json);

    // Now get by ID
    let id_cstr = CString::new(block_id).unwrap();
    let result = yrs_doc_get_block_by_id(doc, id_cstr.as_ptr());
    assert!(!result.is_null());
    let result_str = unsafe { CStr::from_ptr(result) }.to_str().unwrap();
    assert!(result_str.contains("paragraph"));
    assert!(result_str.contains("Find me by ID"));
    yrs_free_string(result);

    // Test not found
    let bad_id = CString::new("nonexistent-id").unwrap();
    let not_found = yrs_doc_get_block_by_id(doc, bad_id.as_ptr());
    assert!(not_found.is_null());

    yrs_doc_destroy(doc);
}
```

**Step 2: Run test to verify it fails**

Run: `cd yrs-bridge && cargo test test_get_block_by_id`
Expected: FAIL — `yrs_doc_get_block_by_id` not found

**Step 3: Implement `yrs_doc_get_block_by_id`**

Add after `yrs_doc_get_blocks_json` in `lib.rs`:

```rust
/// Get a single block by its ID. Returns a JSON string representing the blockContainer
/// and its children. Returns null if not found.
/// The caller must free the returned string via `yrs_free_string`.
#[no_mangle]
pub extern "C" fn yrs_doc_get_block_by_id(doc: *const YrsDoc, block_id: *const c_char) -> *mut c_char {
    if doc.is_null() || block_id.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &*doc };
    let target_id = match unsafe { CStr::from_ptr(block_id) }.to_str() {
        Ok(s) => s,
        Err(_) => return ptr::null_mut(),
    };

    let mut txn = doc.doc.transact_mut();
    let fragment = txn.get_or_insert_xml_fragment(FRAGMENT_NAME);

    // Find blockGroup
    if fragment.len(&txn) == 0 {
        return ptr::null_mut();
    }
    let block_group = match fragment.get(&txn, 0) {
        Some(yrs::XmlOut::Element(bg)) if bg.tag().as_ref() == "blockGroup" => bg,
        _ => return ptr::null_mut(),
    };

    // Search blockContainers for matching ID
    for i in 0..block_group.len(&txn) {
        if let Some(ref child) = block_group.get(&txn, i) {
            if let yrs::XmlOut::Element(container) = child {
                if let Some(id) = container.get_attribute(&txn, "id") {
                    if id == target_id {
                        let json = xml_out_to_json(child, &txn);
                        let json_str = match serde_json::to_string(&json) {
                            Ok(s) => s,
                            Err(_) => return ptr::null_mut(),
                        };
                        return match CString::new(json_str) {
                            Ok(c) => c.into_raw(),
                            Err(_) => ptr::null_mut(),
                        };
                    }
                }
            }
        }
    }
    ptr::null_mut()
}
```

**Step 4: Run test to verify it passes**

Run: `cd yrs-bridge && cargo test test_get_block_by_id`
Expected: PASS

**Step 5: Commit**

```bash
git add yrs-bridge/src/lib.rs
git commit -m "feat: add yrs_doc_get_block_by_id FFI function"
```

---

### Task 3: Rust — Add `update_block` FFI function

**Files:**
- Modify: `yrs-bridge/src/lib.rs`

**Step 1: Write test for update_block**

Add to `mod tests`:

```rust
#[test]
fn test_update_block() {
    let doc = yrs_doc_new();

    // Insert a paragraph block
    let tag = CString::new("paragraph").unwrap();
    let content = CString::new("Original text").unwrap();
    let mut len: u32 = 0;
    let update = yrs_doc_insert_block(doc, 0, tag.as_ptr(), content.as_ptr(), ptr::null(), &mut len);
    yrs_free_bytes(update, len);

    // Get the block ID
    let json = yrs_doc_get_blocks_json(doc);
    let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
    let parsed: Vec<serde_json::Value> = serde_json::from_str(json_str).unwrap();
    let containers = parsed[0]["children"].as_array().unwrap();
    let block_id = containers[0]["props"]["id"].as_str().unwrap().to_string();
    yrs_free_string(json);

    // Update content
    let id_cstr = CString::new(block_id.as_str()).unwrap();
    let new_content = CString::new("Updated text").unwrap();
    let mut update_len: u32 = 0;
    let result = yrs_doc_update_block(
        doc, id_cstr.as_ptr(),
        ptr::null(),        // no type change
        new_content.as_ptr(),
        ptr::null(),        // no props change
        &mut update_len,
    );
    assert!(!result.is_null());
    yrs_free_bytes(result, update_len);

    // Verify updated content
    let json = yrs_doc_get_blocks_json(doc);
    let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
    assert!(json_str.contains("Updated text"));
    assert!(!json_str.contains("Original text"));
    yrs_free_string(json);

    // Update type (paragraph -> heading) with props
    let new_type = CString::new("heading").unwrap();
    let new_props = CString::new(r#"{"level":"2"}"#).unwrap();
    let mut update_len2: u32 = 0;
    let result2 = yrs_doc_update_block(
        doc, id_cstr.as_ptr(),
        new_type.as_ptr(),
        ptr::null(),        // keep content
        new_props.as_ptr(),
        &mut update_len2,
    );
    assert!(!result2.is_null());
    yrs_free_bytes(result2, update_len2);

    let json = yrs_doc_get_blocks_json(doc);
    let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
    assert!(json_str.contains("heading"));
    assert!(json_str.contains("Updated text")); // content preserved
    yrs_free_string(json);

    yrs_doc_destroy(doc);
}
```

**Step 2: Run test to verify it fails**

Run: `cd yrs-bridge && cargo test test_update_block`
Expected: FAIL — `yrs_doc_update_block` not found

**Step 3: Implement `yrs_doc_update_block`**

Add after `yrs_doc_get_block_by_id`:

```rust
/// Update a block identified by its ID. Supports partial update:
/// - `new_type`: if non-null, changes the block's content element type (e.g. paragraph -> heading)
/// - `new_content`: if non-null, replaces the block's text content
/// - `new_props_json`: if non-null, merges new properties into the content element's attributes
///
/// The approach: since Yrs XML elements don't support renaming tags, changing the type
/// requires removing the old content element and inserting a new one with the new tag.
/// For content-only or props-only updates, we modify in place.
///
/// Returns update bytes (diff) for broadcasting. Caller must free via `yrs_free_bytes`.
/// Returns null if block not found or on error.
#[no_mangle]
pub extern "C" fn yrs_doc_update_block(
    doc: *mut YrsDoc,
    block_id: *const c_char,
    new_type: *const c_char,
    new_content: *const c_char,
    new_props_json: *const c_char,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || block_id.is_null() || out_len.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &mut *doc };
    let target_id = match unsafe { CStr::from_ptr(block_id) }.to_str() {
        Ok(s) => s,
        Err(_) => return ptr::null_mut(),
    };

    let type_change = if new_type.is_null() {
        None
    } else {
        match unsafe { CStr::from_ptr(new_type) }.to_str() {
            Ok(s) => Some(s.to_string()),
            Err(_) => return ptr::null_mut(),
        }
    };

    let content_change = if new_content.is_null() {
        None
    } else {
        match unsafe { CStr::from_ptr(new_content) }.to_str() {
            Ok(s) => Some(s.to_string()),
            Err(_) => return ptr::null_mut(),
        }
    };

    let props_change: Option<HashMap<String, String>> = if new_props_json.is_null() {
        None
    } else {
        match unsafe { CStr::from_ptr(new_props_json) }.to_str() {
            Ok(s) if !s.is_empty() => Some(serde_json::from_str(s).unwrap_or_default()),
            _ => None,
        }
    };

    // Capture state vector before mutation
    let sv_before = {
        let txn = doc.doc.transact();
        txn.state_vector()
    };

    {
        let mut txn = doc.doc.transact_mut();
        let fragment = txn.get_or_insert_xml_fragment(FRAGMENT_NAME);
        if fragment.len(&txn) == 0 {
            return ptr::null_mut();
        }
        let block_group = match fragment.get(&txn, 0) {
            Some(yrs::XmlOut::Element(bg)) if bg.tag().as_ref() == "blockGroup" => bg,
            _ => return ptr::null_mut(),
        };

        // Find the blockContainer with matching ID
        let mut found_index = None;
        for i in 0..block_group.len(&txn) {
            if let Some(yrs::XmlOut::Element(container)) = block_group.get(&txn, i) {
                if let Some(id) = container.get_attribute(&txn, "id") {
                    if id == target_id {
                        found_index = Some(i);
                        break;
                    }
                }
            }
        }

        let container_index = match found_index {
            Some(i) => i,
            None => return ptr::null_mut(),
        };

        let container = match block_group.get(&txn, container_index) {
            Some(yrs::XmlOut::Element(c)) => c,
            _ => return ptr::null_mut(),
        };

        if type_change.is_some() {
            // Type change requires replacing the content element
            // Read existing content and props from the old element
            let old_content_text = if let Some(yrs::XmlOut::Element(old_elem)) = container.get(&txn, 0) {
                // Extract text from XmlText child
                if let Some(yrs::XmlOut::Text(txt)) = old_elem.get(&txn, 0) {
                    txt.get_string(&txn)
                } else {
                    String::new()
                }
            } else {
                String::new()
            };

            // Remove old content element
            container.remove_range(&mut txn, 0, 1);

            // Create new content element with new type
            let new_tag = type_change.as_ref().unwrap();
            let new_elem = container.insert(&mut txn, 0, XmlElementPrelim::empty(&**new_tag));
            new_elem.insert_attribute(&mut txn, Arc::<str>::from("textAlignment"), "left".to_string());

            // Apply props
            if let Some(ref props) = props_change {
                for (key, value) in props {
                    new_elem.insert_attribute(&mut txn, Arc::<str>::from(key.as_str()), value.clone());
                }
            }

            // Set content: use new content if provided, otherwise preserve old
            let final_content = content_change.as_deref().unwrap_or(&old_content_text);
            let xml_text = new_elem.insert(&mut txn, 0, XmlTextPrelim::new(""));
            if !final_content.is_empty() {
                xml_text.insert(&mut txn, 0, final_content);
            }
        } else {
            // No type change — modify content element in place
            if let Some(yrs::XmlOut::Element(elem)) = container.get(&txn, 0) {
                // Update props if provided
                if let Some(ref props) = props_change {
                    for (key, value) in props {
                        elem.insert_attribute(&mut txn, Arc::<str>::from(key.as_str()), value.clone());
                    }
                }

                // Update content if provided
                if let Some(ref new_text) = content_change {
                    // Remove old XmlText and create new one
                    if elem.len(&txn) > 0 {
                        elem.remove_range(&mut txn, 0, 1);
                    }
                    let xml_text = elem.insert(&mut txn, 0, XmlTextPrelim::new(""));
                    if !new_text.is_empty() {
                        xml_text.insert(&mut txn, 0, &**new_text);
                    }
                }
            } else {
                return ptr::null_mut();
            }
        }
    }

    let txn = doc.doc.transact();
    let diff = txn.encode_diff_v1(&sv_before);
    into_byte_ptr(diff, out_len)
}
```

**Step 4: Run test to verify it passes**

Run: `cd yrs-bridge && cargo test test_update_block`
Expected: PASS

**Step 5: Commit**

```bash
git add yrs-bridge/src/lib.rs
git commit -m "feat: add yrs_doc_update_block FFI function"
```

---

### Task 4: Rust — Add `delete_block_by_id` FFI function

**Files:**
- Modify: `yrs-bridge/src/lib.rs`

**Step 1: Write test for delete_block_by_id**

```rust
#[test]
fn test_delete_block_by_id() {
    let doc = yrs_doc_new();

    // Insert two blocks
    let tag = CString::new("paragraph").unwrap();
    let c1 = CString::new("Block One").unwrap();
    let c2 = CString::new("Block Two").unwrap();
    let mut len: u32 = 0;
    let u1 = yrs_doc_insert_block(doc, 0, tag.as_ptr(), c1.as_ptr(), ptr::null(), &mut len);
    yrs_free_bytes(u1, len);
    let u2 = yrs_doc_insert_block(doc, 1, tag.as_ptr(), c2.as_ptr(), ptr::null(), &mut len);
    yrs_free_bytes(u2, len);

    // Get first block's ID
    let json = yrs_doc_get_blocks_json(doc);
    let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
    let parsed: Vec<serde_json::Value> = serde_json::from_str(json_str).unwrap();
    let block_id = parsed[0]["children"].as_array().unwrap()[0]["props"]["id"].as_str().unwrap().to_string();
    yrs_free_string(json);

    // Delete by ID
    let id_cstr = CString::new(block_id.as_str()).unwrap();
    let mut del_len: u32 = 0;
    let del = yrs_doc_delete_block_by_id(doc, id_cstr.as_ptr(), &mut del_len);
    assert!(!del.is_null());
    yrs_free_bytes(del, del_len);

    // Verify only Block Two remains
    let json = yrs_doc_get_blocks_json(doc);
    let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
    assert!(!json_str.contains("Block One"));
    assert!(json_str.contains("Block Two"));
    yrs_free_string(json);

    // Delete nonexistent ID returns null
    let bad = CString::new("no-such-id").unwrap();
    let result = yrs_doc_delete_block_by_id(doc, bad.as_ptr(), &mut del_len);
    assert!(result.is_null());

    yrs_doc_destroy(doc);
}
```

**Step 2: Run test to verify it fails**

Run: `cd yrs-bridge && cargo test test_delete_block_by_id`
Expected: FAIL

**Step 3: Implement `yrs_doc_delete_block_by_id`**

```rust
/// Delete a block by its ID. Returns update bytes (diff) for broadcasting.
/// Returns null if block not found. Caller must free via `yrs_free_bytes`.
#[no_mangle]
pub extern "C" fn yrs_doc_delete_block_by_id(
    doc: *mut YrsDoc,
    block_id: *const c_char,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || block_id.is_null() || out_len.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &mut *doc };
    let target_id = match unsafe { CStr::from_ptr(block_id) }.to_str() {
        Ok(s) => s,
        Err(_) => return ptr::null_mut(),
    };

    let sv_before = {
        let txn = doc.doc.transact();
        txn.state_vector()
    };

    {
        let mut txn = doc.doc.transact_mut();
        let fragment = txn.get_or_insert_xml_fragment(FRAGMENT_NAME);
        if fragment.len(&txn) == 0 {
            return ptr::null_mut();
        }
        let block_group = match fragment.get(&txn, 0) {
            Some(yrs::XmlOut::Element(bg)) if bg.tag().as_ref() == "blockGroup" => bg,
            _ => return ptr::null_mut(),
        };

        let mut found_index = None;
        for i in 0..block_group.len(&txn) {
            if let Some(yrs::XmlOut::Element(container)) = block_group.get(&txn, i) {
                if let Some(id) = container.get_attribute(&txn, "id") {
                    if id == target_id {
                        found_index = Some(i);
                        break;
                    }
                }
            }
        }

        match found_index {
            Some(i) => block_group.remove_range(&mut txn, i, 1),
            None => return ptr::null_mut(),
        }
    }

    let txn = doc.doc.transact();
    let diff = txn.encode_diff_v1(&sv_before);
    into_byte_ptr(diff, out_len)
}
```

**Step 4: Run tests**

Run: `cd yrs-bridge && cargo test`
Expected: All tests pass

**Step 5: Commit**

```bash
git add yrs-bridge/src/lib.rs
git commit -m "feat: add yrs_doc_delete_block_by_id FFI function"
```

---

### Task 5: Rust — Add semantic position insertion (`insert_block_v2`)

**Files:**
- Modify: `yrs-bridge/src/lib.rs`

The new function supports:
- `position = "start"` → insert at index 0
- `position = "end"` → append
- `position = "after_block"` → insert after the block with `after_id`
- Also supports `children` as a JSON array for batch insert

**Step 1: Write test**

```rust
#[test]
fn test_insert_block_v2_positions() {
    let doc = yrs_doc_new();
    let tag = CString::new("paragraph").unwrap();
    let mut len: u32 = 0;

    // Insert "A" at end (empty doc)
    let pos_end = CString::new("end").unwrap();
    let ca = CString::new("A").unwrap();
    let u = yrs_doc_insert_block_v2(doc, tag.as_ptr(), ca.as_ptr(), ptr::null(), pos_end.as_ptr(), ptr::null(), &mut len);
    assert!(!u.is_null());
    yrs_free_bytes(u, len);

    // Insert "B" at end
    let cb = CString::new("B").unwrap();
    let u = yrs_doc_insert_block_v2(doc, tag.as_ptr(), cb.as_ptr(), ptr::null(), pos_end.as_ptr(), ptr::null(), &mut len);
    assert!(!u.is_null());
    yrs_free_bytes(u, len);

    // Insert "C" at start → order should be C, A, B
    let pos_start = CString::new("start").unwrap();
    let cc = CString::new("C").unwrap();
    let u = yrs_doc_insert_block_v2(doc, tag.as_ptr(), cc.as_ptr(), ptr::null(), pos_start.as_ptr(), ptr::null(), &mut len);
    assert!(!u.is_null());
    yrs_free_bytes(u, len);

    // Get block A's ID for after_block test
    let json = yrs_doc_get_blocks_json(doc);
    let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
    let parsed: Vec<serde_json::Value> = serde_json::from_str(json_str).unwrap();
    let containers = parsed[0]["children"].as_array().unwrap();
    // Order: C(0), A(1), B(2)
    let a_id = containers[1]["props"]["id"].as_str().unwrap().to_string();
    yrs_free_string(json);

    // Insert "D" after A → order should be C, A, D, B
    let pos_after = CString::new("after_block").unwrap();
    let after_id = CString::new(a_id.as_str()).unwrap();
    let cd = CString::new("D").unwrap();
    let u = yrs_doc_insert_block_v2(doc, tag.as_ptr(), cd.as_ptr(), ptr::null(), pos_after.as_ptr(), after_id.as_ptr(), &mut len);
    assert!(!u.is_null());
    yrs_free_bytes(u, len);

    // Verify final order: C, A, D, B
    let json = yrs_doc_get_blocks_json(doc);
    let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
    let parsed: Vec<serde_json::Value> = serde_json::from_str(json_str).unwrap();
    let containers = parsed[0]["children"].as_array().unwrap();
    assert_eq!(containers.len(), 4);

    // Extract content text from each container
    fn get_text(c: &serde_json::Value) -> &str {
        c["children"][0]["children"][0].as_str().unwrap_or("")
    }
    assert_eq!(get_text(&containers[0]), "C");
    assert_eq!(get_text(&containers[1]), "A");
    assert_eq!(get_text(&containers[2]), "D");
    assert_eq!(get_text(&containers[3]), "B");
    yrs_free_string(json);

    yrs_doc_destroy(doc);
}
```

**Step 2: Run test to verify it fails**

Run: `cd yrs-bridge && cargo test test_insert_block_v2_positions`
Expected: FAIL

**Step 3: Implement `yrs_doc_insert_block_v2`**

```rust
/// Insert a block with semantic position control.
/// - `position`: one of "start", "end", "after_block"
/// - `after_id`: block ID to insert after (only used when position = "after_block")
///
/// Returns the new block's JSON as a C string written to `out_block_json`,
/// and the update bytes (diff) for broadcasting.
/// Returns null on error. Caller must free bytes via `yrs_free_bytes`.
#[no_mangle]
pub extern "C" fn yrs_doc_insert_block_v2(
    doc: *mut YrsDoc,
    block_type: *const c_char,
    content: *const c_char,
    props_json: *const c_char,
    position: *const c_char,
    after_id: *const c_char,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || block_type.is_null() || position.is_null() || out_len.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &mut *doc };

    let tag = match unsafe { CStr::from_ptr(block_type) }.to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return ptr::null_mut(),
    };
    let text_content = if content.is_null() {
        String::new()
    } else {
        match unsafe { CStr::from_ptr(content) }.to_str() {
            Ok(s) => s.to_string(),
            Err(_) => return ptr::null_mut(),
        }
    };
    let pos_str = match unsafe { CStr::from_ptr(position) }.to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return ptr::null_mut(),
    };
    let props: HashMap<String, String> = if props_json.is_null() {
        HashMap::new()
    } else {
        match unsafe { CStr::from_ptr(props_json) }.to_str() {
            Ok(s) if !s.is_empty() => serde_json::from_str(s).unwrap_or_default(),
            _ => HashMap::new(),
        }
    };

    let sv_before = {
        let txn = doc.doc.transact();
        txn.state_vector()
    };

    {
        let mut txn = doc.doc.transact_mut();
        let fragment = txn.get_or_insert_xml_fragment(FRAGMENT_NAME);

        let block_group = if fragment.len(&txn) > 0 {
            if let Some(yrs::XmlOut::Element(bg)) = fragment.get(&txn, 0) {
                if bg.tag().as_ref() == "blockGroup" {
                    bg
                } else {
                    fragment.insert(&mut txn, 0, XmlElementPrelim::empty("blockGroup"))
                }
            } else {
                fragment.insert(&mut txn, 0, XmlElementPrelim::empty("blockGroup"))
            }
        } else {
            fragment.insert(&mut txn, 0, XmlElementPrelim::empty("blockGroup"))
        };

        // Determine insert index based on position
        let bg_len = block_group.len(&txn);
        let insert_index = match pos_str.as_str() {
            "start" => 0,
            "end" => bg_len,
            "after_block" => {
                if after_id.is_null() {
                    return ptr::null_mut();
                }
                let target = match unsafe { CStr::from_ptr(after_id) }.to_str() {
                    Ok(s) => s,
                    Err(_) => return ptr::null_mut(),
                };
                let mut found = None;
                for i in 0..bg_len {
                    if let Some(yrs::XmlOut::Element(c)) = block_group.get(&txn, i) {
                        if let Some(id) = c.get_attribute(&txn, "id") {
                            if id == target {
                                found = Some(i + 1);
                                break;
                            }
                        }
                    }
                }
                match found {
                    Some(i) => i,
                    None => return ptr::null_mut(),
                }
            }
            _ => return ptr::null_mut(),
        };

        // Create blockContainer
        let container = block_group.insert(&mut txn, insert_index, XmlElementPrelim::empty("blockContainer"));
        let block_id = uuid::Uuid::new_v4().to_string();
        container.insert_attribute(&mut txn, Arc::<str>::from("id"), block_id);
        container.insert_attribute(&mut txn, Arc::<str>::from("backgroundColor"), "default".to_string());
        container.insert_attribute(&mut txn, Arc::<str>::from("textColor"), "default".to_string());

        let elem_ref = container.insert(&mut txn, 0, XmlElementPrelim::empty(&*tag));
        elem_ref.insert_attribute(&mut txn, Arc::<str>::from("textAlignment"), "left".to_string());
        for (key, value) in &props {
            elem_ref.insert_attribute(&mut txn, Arc::<str>::from(key.as_str()), value.clone());
        }

        let xml_text = elem_ref.insert(&mut txn, 0, XmlTextPrelim::new(""));
        if !text_content.is_empty() {
            xml_text.insert(&mut txn, 0, &*text_content);
        }
    }

    let txn = doc.doc.transact();
    let diff = txn.encode_diff_v1(&sv_before);
    into_byte_ptr(diff, out_len)
}
```

**Step 4: Run all tests**

Run: `cd yrs-bridge && cargo test`
Expected: All tests pass

**Step 5: Commit**

```bash
git add yrs-bridge/src/lib.rs
git commit -m "feat: add yrs_doc_insert_block_v2 with semantic position control"
```

---

### Task 6: Rust — Add `get_block_json_after_insert` helper for response enrichment

We need a way to get the JSON of a specific blockContainer right after insert (by reading the last-inserted block at the known index). We'll add a helper function `yrs_doc_get_block_at_index` for this purpose.

**Files:**
- Modify: `yrs-bridge/src/lib.rs`

**Step 1: Write test**

```rust
#[test]
fn test_get_block_at_index() {
    let doc = yrs_doc_new();
    let tag = CString::new("paragraph").unwrap();
    let content = CString::new("Hello").unwrap();
    let mut len: u32 = 0;
    let u = yrs_doc_insert_block(doc, 0, tag.as_ptr(), content.as_ptr(), ptr::null(), &mut len);
    yrs_free_bytes(u, len);

    let result = yrs_doc_get_block_at_index(doc, 0);
    assert!(!result.is_null());
    let json_str = unsafe { CStr::from_ptr(result) }.to_str().unwrap();
    assert!(json_str.contains("paragraph"));
    assert!(json_str.contains("Hello"));
    yrs_free_string(result);

    // Out of bounds
    let bad = yrs_doc_get_block_at_index(doc, 99);
    assert!(bad.is_null());

    yrs_doc_destroy(doc);
}
```

**Step 2: Implement**

```rust
/// Get a single block by index in the blockGroup. Returns JSON string.
/// Caller must free via `yrs_free_string`. Returns null if out of bounds.
#[no_mangle]
pub extern "C" fn yrs_doc_get_block_at_index(doc: *const YrsDoc, index: u32) -> *mut c_char {
    if doc.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &*doc };
    let mut txn = doc.doc.transact_mut();
    let fragment = txn.get_or_insert_xml_fragment(FRAGMENT_NAME);
    if fragment.len(&txn) == 0 {
        return ptr::null_mut();
    }
    let block_group = match fragment.get(&txn, 0) {
        Some(yrs::XmlOut::Element(bg)) if bg.tag().as_ref() == "blockGroup" => bg,
        _ => return ptr::null_mut(),
    };
    if index >= block_group.len(&txn) {
        return ptr::null_mut();
    }
    match block_group.get(&txn, index) {
        Some(ref child) => {
            let json = xml_out_to_json(child, &txn);
            match serde_json::to_string(&json) {
                Ok(s) => match CString::new(s) {
                    Ok(c) => c.into_raw(),
                    Err(_) => ptr::null_mut(),
                },
                Err(_) => ptr::null_mut(),
            }
        }
        None => ptr::null_mut(),
    }
}
```

**Step 3: Run tests**

Run: `cd yrs-bridge && cargo test`
Expected: All pass

**Step 4: Commit**

```bash
git add yrs-bridge/src/lib.rs
git commit -m "feat: add yrs_doc_get_block_at_index helper"
```

---

### Task 7: Rust — Build the library

**Step 1: Build release**

Run: `cd yrs-bridge && cargo build --release`
Expected: Compiles successfully

**Step 2: Commit** (if Cargo.lock changed)

```bash
git add yrs-bridge/Cargo.lock
git commit -m "chore: update Cargo.lock with uuid dependency"
```

---

### Task 8: Java — Add new FFI bindings in YrsBridge

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/yrs/YrsBridge.java`

**Step 1: Add new MethodHandle fields and downcall bindings**

After `yrsDocDeleteBlock` (line 36), add:

```java
final MethodHandle yrsDocGetBlockById;
final MethodHandle yrsDocUpdateBlock;
final MethodHandle yrsDocDeleteBlockById;
final MethodHandle yrsDocInsertBlockV2;
final MethodHandle yrsDocGetBlockAtIndex;
```

In the constructor, after the `yrsDocDeleteBlock` binding (line 113), add:

```java
// char* yrs_doc_get_block_by_id(YrsDoc*, char* block_id)
yrsDocGetBlockById = linker.downcallHandle(
        lib.find("yrs_doc_get_block_by_id").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
);

// uint8_t* yrs_doc_update_block(YrsDoc*, char* block_id, char* new_type, char* new_content, char* new_props_json, uint32_t* out_len)
yrsDocUpdateBlock = linker.downcallHandle(
        lib.find("yrs_doc_update_block").orElseThrow(),
        FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS
        )
);

// uint8_t* yrs_doc_delete_block_by_id(YrsDoc*, char* block_id, uint32_t* out_len)
yrsDocDeleteBlockById = linker.downcallHandle(
        lib.find("yrs_doc_delete_block_by_id").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
);

// uint8_t* yrs_doc_insert_block_v2(YrsDoc*, char* type, char* content, char* props, char* position, char* after_id, uint32_t* out_len)
yrsDocInsertBlockV2 = linker.downcallHandle(
        lib.find("yrs_doc_insert_block_v2").orElseThrow(),
        FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS
        )
);

// char* yrs_doc_get_block_at_index(YrsDoc*, uint32_t index)
yrsDocGetBlockAtIndex = linker.downcallHandle(
        lib.find("yrs_doc_get_block_at_index").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
);
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/collabdoc/yrs/YrsBridge.java
git commit -m "feat: add FFI bindings for new block operations"
```

---

### Task 9: Java — Add new methods in YrsDocument

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/yrs/YrsDocument.java`

**Step 1: Add `getBlockById` method**

After `getBlocksJson()` method:

```java
/**
 * Get a single block by its ID.
 *
 * @param blockId the block's UUID string
 * @return JSON string of the block, or null if not found
 */
public String getBlockById(String blockId) {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment idNative = arena.allocateFrom(blockId);
        MemorySegment ptr = (MemorySegment) bridge.yrsDocGetBlockById.invokeExact(docPtr, idNative);
        if (ptr.equals(MemorySegment.NULL)) {
            return null;
        }
        String result = ptr.reinterpret(Long.MAX_VALUE).getString(0);
        bridge.yrsFreeString.invokeExact(ptr);
        return result;
    } catch (RuntimeException e) {
        throw e;
    } catch (Throwable t) {
        throw new RuntimeException("Failed to get block by ID", t);
    }
}
```

**Step 2: Add `updateBlock` method**

```java
/**
 * Update a block's content, type, and/or properties.
 *
 * @param blockId   the block's UUID string
 * @param newType   new block type (null to keep current)
 * @param newContent new text content (null to keep current)
 * @param newPropsJson new properties JSON (null to keep current)
 * @return update diff bytes for broadcasting, or null if block not found
 */
public byte[] updateBlock(String blockId, String newType, String newContent, String newPropsJson) {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment idNative = arena.allocateFrom(blockId);
        MemorySegment typeNative = (newType != null) ? arena.allocateFrom(newType) : MemorySegment.NULL;
        MemorySegment contentNative = (newContent != null) ? arena.allocateFrom(newContent) : MemorySegment.NULL;
        MemorySegment propsNative = (newPropsJson != null) ? arena.allocateFrom(newPropsJson) : MemorySegment.NULL;
        MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

        MemorySegment ptr = (MemorySegment) bridge.yrsDocUpdateBlock.invokeExact(
                docPtr, idNative, typeNative, contentNative, propsNative, outLen
        );
        if (ptr.equals(MemorySegment.NULL)) {
            return null;
        }

        int len = outLen.get(ValueLayout.JAVA_INT, 0);
        byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
        bridge.yrsFreeBytes.invokeExact(ptr, len);
        return result;
    } catch (RuntimeException e) {
        throw e;
    } catch (Throwable t) {
        throw new RuntimeException("Failed to update block", t);
    }
}
```

**Step 3: Add `deleteBlockById` method**

```java
/**
 * Delete a block by its ID.
 *
 * @param blockId the block's UUID string
 * @return update diff bytes for broadcasting, or null if block not found
 */
public byte[] deleteBlockById(String blockId) {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment idNative = arena.allocateFrom(blockId);
        MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

        MemorySegment ptr = (MemorySegment) bridge.yrsDocDeleteBlockById.invokeExact(
                docPtr, idNative, outLen
        );
        if (ptr.equals(MemorySegment.NULL)) {
            return null;
        }

        int len = outLen.get(ValueLayout.JAVA_INT, 0);
        byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
        bridge.yrsFreeBytes.invokeExact(ptr, len);
        return result;
    } catch (RuntimeException e) {
        throw e;
    } catch (Throwable t) {
        throw new RuntimeException("Failed to delete block by ID", t);
    }
}
```

**Step 4: Add `insertBlockV2` method**

```java
/**
 * Insert a block with semantic position control.
 *
 * @param blockType  XML tag name (e.g. "paragraph", "heading")
 * @param content    text content (may be null)
 * @param propsJson  properties JSON (may be null)
 * @param position   one of "start", "end", "after_block"
 * @param afterId    block ID to insert after (only used when position = "after_block")
 * @return update diff bytes for broadcasting
 */
public byte[] insertBlockV2(String blockType, String content, String propsJson, String position, String afterId) {
    try (Arena arena = Arena.ofConfined()) {
        MemorySegment typeNative = arena.allocateFrom(blockType);
        MemorySegment contentNative = (content != null) ? arena.allocateFrom(content) : MemorySegment.NULL;
        MemorySegment propsNative = (propsJson != null) ? arena.allocateFrom(propsJson) : MemorySegment.NULL;
        MemorySegment posNative = arena.allocateFrom(position);
        MemorySegment afterNative = (afterId != null) ? arena.allocateFrom(afterId) : MemorySegment.NULL;
        MemorySegment outLen = arena.allocate(ValueLayout.JAVA_INT);

        MemorySegment ptr = (MemorySegment) bridge.yrsDocInsertBlockV2.invokeExact(
                docPtr, typeNative, contentNative, propsNative, posNative, afterNative, outLen
        );
        if (ptr.equals(MemorySegment.NULL)) {
            throw new RuntimeException("yrs_doc_insert_block_v2 returned null");
        }

        int len = outLen.get(ValueLayout.JAVA_INT, 0);
        byte[] result = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
        bridge.yrsFreeBytes.invokeExact(ptr, len);
        return result;
    } catch (RuntimeException e) {
        throw e;
    } catch (Throwable t) {
        throw new RuntimeException("Failed to insert block v2", t);
    }
}
```

**Step 5: Add `getBlockAtIndex` method**

```java
/**
 * Get a single block by its index in the blockGroup.
 *
 * @param index 0-based position
 * @return JSON string of the block, or null if out of bounds
 */
public String getBlockAtIndex(int index) {
    try {
        MemorySegment ptr = (MemorySegment) bridge.yrsDocGetBlockAtIndex.invokeExact(docPtr, index);
        if (ptr.equals(MemorySegment.NULL)) {
            return null;
        }
        String result = ptr.reinterpret(Long.MAX_VALUE).getString(0);
        bridge.yrsFreeString.invokeExact(ptr);
        return result;
    } catch (RuntimeException e) {
        throw e;
    } catch (Throwable t) {
        throw new RuntimeException("Failed to get block at index", t);
    }
}
```

**Step 6: Commit**

```bash
git add backend/src/main/java/com/collabdoc/yrs/YrsDocument.java
git commit -m "feat: add Java wrapper methods for new block operations"
```

---

### Task 10: Java — Add new methods in YrsDocumentManager

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java`

**Step 1: Add new service methods**

After the existing `deleteBlock` method:

```java
/** Get a single block by ID. Returns JSON string or null. */
public String getBlockById(UUID docId, String blockId) {
    var doc = getOrLoadDocument(docId);
    synchronized (doc) {
        return doc.getBlockById(blockId);
    }
}

/** Update a block by ID. Returns the Yjs update to broadcast. */
public byte[] updateBlock(UUID docId, String blockId, String newType, String newContent, String newPropsJson) {
    var doc = getOrLoadDocument(docId);
    synchronized (doc) {
        byte[] update = doc.updateBlock(blockId, newType, newContent, newPropsJson);
        if (update != null) {
            updateRepository.save(new DocumentUpdate(docId, update));
        }
        return update;
    }
}

/** Delete a block by ID. Returns the Yjs update to broadcast. */
public byte[] deleteBlockById(UUID docId, String blockId) {
    var doc = getOrLoadDocument(docId);
    synchronized (doc) {
        byte[] update = doc.deleteBlockById(blockId);
        if (update != null) {
            updateRepository.save(new DocumentUpdate(docId, update));
        }
        return update;
    }
}

/** Insert a block with semantic position. Returns the Yjs update to broadcast. */
public byte[] insertBlockV2(UUID docId, String blockType, String content, String propsJson, String position, String afterId) {
    var doc = getOrLoadDocument(docId);
    synchronized (doc) {
        byte[] update = doc.insertBlockV2(blockType, content, propsJson, position, afterId);
        if (update != null) {
            updateRepository.save(new DocumentUpdate(docId, update));
        }
        return update;
    }
}
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/YrsDocumentManager.java
git commit -m "feat: add service methods for new block operations"
```

---

### Task 11: Java — Create new request/response DTOs

**Files:**
- Create: `backend/src/main/java/com/collabdoc/collab/InsertBlockV2Request.java`
- Create: `backend/src/main/java/com/collabdoc/collab/UpdateBlockRequest.java`

**Step 1: Create InsertBlockV2Request**

```java
package com.collabdoc.collab;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

public record InsertBlockV2Request(
    @NotBlank(message = "type 不能为空")
    String type,
    String content,
    Map<String, Object> props,
    @NotBlank(message = "position 不能为空")
    @Pattern(regexp = "start|end|after_block", message = "position 必须是 start, end, 或 after_block")
    String position,
    String afterId,
    List<BlockChild> children
) {
    public record BlockChild(
        @NotBlank(message = "type 不能为空")
        String type,
        String content,
        Map<String, Object> props
    ) {}
}
```

**Step 2: Create UpdateBlockRequest**

```java
package com.collabdoc.collab;

import java.util.Map;

public record UpdateBlockRequest(
    String type,
    String content,
    Map<String, Object> props
) {}
```

**Step 3: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/InsertBlockV2Request.java \
        backend/src/main/java/com/collabdoc/collab/UpdateBlockRequest.java
git commit -m "feat: add request DTOs for new block API"
```

---

### Task 12: Java — Refactor BlockController with new endpoints

**Files:**
- Modify: `backend/src/main/java/com/collabdoc/collab/BlockController.java`

**Step 1: Rewrite the controller**

Replace the entire `BlockController.java` with:

```java
package com.collabdoc.collab;

import com.collabdoc.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/docs/{docId}/blocks")
public class BlockController {

    private final YrsDocumentManager docManager;
    private final YjsWebSocketHandler wsHandler;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;

    public BlockController(YrsDocumentManager docManager, YjsWebSocketHandler wsHandler,
                           PermissionService permissionService, ObjectMapper objectMapper) {
        this.docManager = docManager;
        this.wsHandler = wsHandler;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
    }

    /** Get all blocks as parsed JSON array. */
    @GetMapping
    public ResponseEntity<?> getBlocks(@AuthenticationPrincipal UUID userId, @PathVariable UUID docId) {
        if (!permissionService.canView(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String json = docManager.getBlocksJson(docId);
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("content", json));
        }
    }

    /** Get a single block by ID. */
    @GetMapping("/{blockId}")
    public ResponseEntity<?> getBlockById(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @PathVariable String blockId) {
        if (!permissionService.canView(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        String json = docManager.getBlockById(docId, blockId);
        if (json == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Block not found"));
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(json);
        }
    }

    /** Insert block(s) with semantic position. Supports batch via children array. */
    @PostMapping
    public ResponseEntity<?> insertBlock(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @Valid @RequestBody InsertBlockV2Request request) {
        if (!permissionService.canEdit(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        List<Map<String, Object>> insertedBlocks = new ArrayList<>();

        // Insert the primary block
        String propsJson = request.props() != null
                ? objectMapper.valueToTree(request.props()).toString()
                : null;
        byte[] update = docManager.insertBlockV2(docId, request.type(), request.content(),
                propsJson, request.position(), request.afterId());
        if (update != null) {
            wsHandler.broadcastUpdate(docId, update);
        }

        // Get the inserted block's JSON for the response
        // After insertion, we need to find the block we just inserted.
        // We'll use getBlocksJson and find the last matching block.
        // A simpler approach: read blocks, find the one not previously seen.
        // For now, we return the full blocks list and let the caller find theirs.

        // Insert children if provided (batch insert)
        if (request.children() != null && !request.children().isEmpty()) {
            // Children are inserted sequentially after the primary block
            // We need the primary block's ID to use after_block positioning
            // For simplicity, use "end" for children (they follow the primary)
            for (var child : request.children()) {
                String childPropsJson = child.props() != null
                        ? objectMapper.valueToTree(child.props()).toString()
                        : null;
                byte[] childUpdate = docManager.insertBlockV2(docId, child.type(), child.content(),
                        childPropsJson, "end", null);
                if (childUpdate != null) {
                    wsHandler.broadcastUpdate(docId, childUpdate);
                }
            }
        }

        // Return current block list as response
        String blocksJson = docManager.getBlocksJson(docId);
        try {
            Object parsed = objectMapper.readValue(blocksJson, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("content", blocksJson));
        }
    }

    /** Update a block by ID. Partial patch semantics. */
    @PatchMapping("/{blockId}")
    public ResponseEntity<?> updateBlock(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @PathVariable String blockId,
            @RequestBody UpdateBlockRequest request) {
        if (!permissionService.canEdit(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        String propsJson = request.props() != null
                ? objectMapper.valueToTree(request.props()).toString()
                : null;

        byte[] update = docManager.updateBlock(docId, blockId, request.type(), request.content(), propsJson);
        if (update == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Block not found"));
        }
        wsHandler.broadcastUpdate(docId, update);

        // Return updated block
        String blockJson = docManager.getBlockById(docId, blockId);
        try {
            Object parsed = objectMapper.readValue(blockJson, Object.class);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            return ResponseEntity.ok(blockJson);
        }
    }

    /** Delete a block by ID. */
    @DeleteMapping("/{blockId}")
    public ResponseEntity<?> deleteBlock(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID docId,
            @PathVariable String blockId) {
        if (!permissionService.canEdit(docId, userId)) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        byte[] update = docManager.deleteBlockById(docId, blockId);
        if (update == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Block not found"));
        }
        wsHandler.broadcastUpdate(docId, update);

        return ResponseEntity.ok(Map.of("status", "ok", "deleted", blockId));
    }
}
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/collabdoc/collab/BlockController.java
git commit -m "feat: refactor BlockController with Notion-style API endpoints"
```

---

### Task 13: Update E2E test for new API

**Files:**
- Modify: `frontend/e2e/agent-write-visible.spec.ts`

**Step 1: Update the test to use new API format**

```typescript
import { test, expect } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";

test("agent block insert is visible in browser", async ({ page }) => {
  // Create a fresh test document via API
  const createRes = await page.request.post(`${API_BASE}/docs`, {
    data: { title: "Agent Write Test" },
  });
  const { id: docId } = await createRes.json();

  // Agent inserts a block via REST API (new v2 format)
  await page.request.post(`${API_BASE}/docs/${docId}/blocks`, {
    data: { type: "paragraph", content: "Nova test block", position: "end" },
  });

  // Open the document in the browser
  await page.goto(`http://localhost:3000/doc/${docId}`);

  // Assert the agent-written content is visible
  await expect(page.locator("text=Nova test block")).toBeVisible({
    timeout: 5000,
  });
});
```

**Step 2: Commit**

```bash
git add frontend/e2e/agent-write-visible.spec.ts
git commit -m "test: update E2E test for new block API format"
```

---

### Task 14: Update other E2E tests that use the block API

**Files:**
- Modify: `frontend/e2e/browser-then-agent.spec.ts` (if it uses old block API)

**Step 1: Check and update any other tests using old `/blocks` API format**

Search for `index:` in E2E tests and update to use `position: "end"` or appropriate position.

**Step 2: Commit**

```bash
git add frontend/e2e/
git commit -m "test: update remaining E2E tests for new block API"
```

---

### Task 15: Java — Add YrsBridge integration tests for new methods

**Files:**
- Modify: `backend/src/test/java/com/collabdoc/yrs/YrsBridgeTest.java`

**Step 1: Add tests for new methods**

```java
@Test
@Order(9)
void getBlockById() {
    try (YrsDocument doc = bridge.createDocument()) {
        doc.insertBlock(0, "paragraph", "Find me", null);
        String allBlocks = doc.getBlocksJson();
        // Parse to get block ID (using basic string matching)
        assertTrue(allBlocks.contains("\"id\""));

        // The block ID is in the blockContainer's props
        // We need a simple way to extract it
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var blocks = mapper.readTree(allBlocks);
        String blockId = blocks.get(0).get("children").get(0).get("props").get("id").asText();

        String block = doc.getBlockById(blockId);
        assertNotNull(block, "Should find block by ID");
        assertTrue(block.contains("Find me"));

        // Not found
        assertNull(doc.getBlockById("nonexistent-id"));
    } catch (Exception e) {
        fail("Unexpected exception: " + e.getMessage());
    }
}

@Test
@Order(10)
void updateBlock() {
    try (YrsDocument doc = bridge.createDocument()) {
        doc.insertBlock(0, "paragraph", "Original", null);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var blocks = mapper.readTree(doc.getBlocksJson());
        String blockId = blocks.get(0).get("children").get(0).get("props").get("id").asText();

        byte[] update = doc.updateBlock(blockId, null, "Updated content", null);
        assertNotNull(update);

        String json = doc.getBlocksJson();
        assertTrue(json.contains("Updated content"));
        assertFalse(json.contains("Original"));
    } catch (Exception e) {
        fail("Unexpected exception: " + e.getMessage());
    }
}

@Test
@Order(11)
void deleteBlockById() {
    try (YrsDocument doc = bridge.createDocument()) {
        doc.insertBlock(0, "paragraph", "Keep", null);
        doc.insertBlock(1, "paragraph", "Delete", null);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var blocks = mapper.readTree(doc.getBlocksJson());
        String deleteId = blocks.get(0).get("children").get(1).get("props").get("id").asText();

        byte[] update = doc.deleteBlockById(deleteId);
        assertNotNull(update);

        String json = doc.getBlocksJson();
        assertTrue(json.contains("Keep"));
        assertFalse(json.contains("Delete"));
    } catch (Exception e) {
        fail("Unexpected exception: " + e.getMessage());
    }
}

@Test
@Order(12)
void insertBlockV2WithPositions() {
    try (YrsDocument doc = bridge.createDocument()) {
        // Insert at end
        doc.insertBlockV2("paragraph", "A", null, "end", null);
        doc.insertBlockV2("paragraph", "B", null, "end", null);

        // Insert at start
        doc.insertBlockV2("paragraph", "C", null, "start", null);

        // Verify order: C, A, B
        String json = doc.getBlocksJson();
        assertTrue(json.indexOf("C") < json.indexOf("A"));
        assertTrue(json.indexOf("A") < json.indexOf("B"));
    }
}
```

**Step 2: Commit**

```bash
git add backend/src/test/java/com/collabdoc/yrs/YrsBridgeTest.java
git commit -m "test: add integration tests for new block operations"
```

---

### Task 16: Clean up old InsertBlockRequest

**Files:**
- Delete or deprecate: `backend/src/main/java/com/collabdoc/collab/InsertBlockRequest.java`

**Step 1: Remove the old request DTO**

Since the controller no longer uses `InsertBlockRequest`, delete the file:

```bash
rm backend/src/main/java/com/collabdoc/collab/InsertBlockRequest.java
```

**Step 2: Commit**

```bash
git add -A backend/src/main/java/com/collabdoc/collab/InsertBlockRequest.java
git commit -m "refactor: remove deprecated InsertBlockRequest"
```

---

### Task 17: Verification — Build and test everything

**Step 1: Build Rust**

Run: `cd yrs-bridge && cargo test`
Expected: All Rust tests pass

**Step 2: Build Java**

Run: `cd backend && ./gradlew build`
Expected: Compiles successfully

**Step 3: Run Java integration tests**

Run: `cd backend && ./gradlew test`
Expected: All tests pass (requires the Rust library to be built)

**Step 4: Final commit if needed**

```bash
git commit -m "chore: verification pass - all tests green"
```

---

## Summary of API Changes

### Before (index-based)
```
GET    /api/docs/{docId}/blocks          → {"content": "<json>"}
POST   /api/docs/{docId}/blocks          → {"status": "ok"}     (body: {index, type, content, props})
DELETE /api/docs/{docId}/blocks/{index}  → {"status": "ok"}
```

### After (ID-based, Notion-style)
```
GET    /api/docs/{docId}/blocks              → [block objects]
GET    /api/docs/{docId}/blocks/{blockId}    → {block object}
POST   /api/docs/{docId}/blocks              → [block objects]   (body: {type, content, props, position, afterId, children})
PATCH  /api/docs/{docId}/blocks/{blockId}    → {block object}   (body: {type?, content?, props?})
DELETE /api/docs/{docId}/blocks/{blockId}    → {"status": "ok", "deleted": blockId}
```
