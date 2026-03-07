//! Yrs bridge library - exposes C ABI functions for Java FFM integration.
//!
//! This cdylib wraps the `yrs` crate (v0.21), providing:
//! - Document lifecycle (create/destroy)
//! - Yjs sync protocol (state vector, diffs, apply updates)
//! - Block-level operations (insert/delete blocks in XmlFragment)
//! - Memory management (free bytes/strings)

use std::collections::HashMap;
use std::ffi::{c_char, CStr, CString};
use std::ptr;
use std::sync::Arc;

use yrs::updates::decoder::Decode;
use yrs::updates::encoder::Encode;
use yrs::{
    Doc, GetString, ReadTxn, StateVector, Text, Transact, Update, WriteTxn,
    XmlElementPrelim, XmlFragment, XmlTextPrelim, Xml,
};

/// The XML fragment root name used for storing document blocks.
/// This matches BlockNote's convention.
const FRAGMENT_NAME: &str = "document-store";

/// Wrapper around a `yrs::Doc` that is handed out as an opaque pointer to FFI callers.
pub struct YrsDoc {
    doc: Doc,
}

// ---------------------------------------------------------------------------
// Doc lifecycle
// ---------------------------------------------------------------------------

/// Create a new Yrs document. The caller owns the returned pointer and must
/// eventually free it with `yrs_doc_destroy`.
#[no_mangle]
pub extern "C" fn yrs_doc_new() -> *mut YrsDoc {
    let yrs_doc = YrsDoc { doc: Doc::new() };
    Box::into_raw(Box::new(yrs_doc))
}

/// Destroy a Yrs document previously created with `yrs_doc_new`.
/// Passing a null pointer is a safe no-op.
#[no_mangle]
pub extern "C" fn yrs_doc_destroy(doc: *mut YrsDoc) {
    if !doc.is_null() {
        unsafe {
            drop(Box::from_raw(doc));
        }
    }
}

// ---------------------------------------------------------------------------
// Sync protocol
// ---------------------------------------------------------------------------

/// Encode the current state vector of the document. Returns a pointer to a
/// heap-allocated byte array and writes its length to `out_len`.
/// The caller must free the returned bytes via `yrs_free_bytes`.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn yrs_doc_state_vector(
    doc: *const YrsDoc,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || out_len.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &*doc };
    let txn = doc.doc.transact();
    let sv = txn.state_vector();
    let encoded = sv.encode_v1();
    into_byte_ptr(encoded, out_len)
}

/// Compute a diff between the document's current state and the provided state
/// vector `sv`. Returns the diff as a byte array (length written to `out_len`).
/// The caller must free the returned bytes via `yrs_free_bytes`.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn yrs_doc_encode_diff(
    doc: *const YrsDoc,
    sv: *const u8,
    sv_len: u32,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || sv.is_null() || out_len.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &*doc };
    let sv_bytes = unsafe { std::slice::from_raw_parts(sv, sv_len as usize) };

    let state_vector = match StateVector::decode_v1(sv_bytes) {
        Ok(v) => v,
        Err(_) => return ptr::null_mut(),
    };

    let txn = doc.doc.transact();
    let diff = txn.encode_diff_v1(&state_vector);
    into_byte_ptr(diff, out_len)
}

/// Encode the full document state as an update (relative to an empty state vector).
/// The caller must free the returned bytes via `yrs_free_bytes`.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn yrs_doc_encode_state(
    doc: *const YrsDoc,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || out_len.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &*doc };
    let txn = doc.doc.transact();
    let state = txn.encode_state_as_update_v1(&StateVector::default());
    into_byte_ptr(state, out_len)
}

/// Apply a binary update to the document and return a copy of the update bytes
/// for broadcasting to other clients. The caller must free the returned bytes
/// via `yrs_free_bytes`. Returns null on error.
#[no_mangle]
pub extern "C" fn yrs_doc_apply_update(
    doc: *mut YrsDoc,
    update: *const u8,
    update_len: u32,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || update.is_null() || out_len.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &mut *doc };
    let update_bytes = unsafe { std::slice::from_raw_parts(update, update_len as usize) };

    let decoded = match Update::decode_v1(update_bytes) {
        Ok(u) => u,
        Err(_) => return ptr::null_mut(),
    };

    {
        let mut txn = doc.doc.transact_mut();
        if txn.apply_update(decoded).is_err() {
            return ptr::null_mut();
        }
    }

    // Return a copy of the input update bytes for broadcasting
    let copy = update_bytes.to_vec();
    into_byte_ptr(copy, out_len)
}

/// Load a full state snapshot into the document. Typically used on startup to
/// restore persisted state. Returns 0 on success, -1 on error.
#[no_mangle]
pub extern "C" fn yrs_doc_load_state(
    doc: *mut YrsDoc,
    state: *const u8,
    state_len: u32,
) -> i32 {
    if doc.is_null() || state.is_null() {
        return -1;
    }
    let doc = unsafe { &mut *doc };
    let state_bytes = unsafe { std::slice::from_raw_parts(state, state_len as usize) };

    let decoded = match Update::decode_v1(state_bytes) {
        Ok(u) => u,
        Err(_) => return -1,
    };

    let mut txn = doc.doc.transact_mut();
    match txn.apply_update(decoded) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

// ---------------------------------------------------------------------------
// Block operations
// ---------------------------------------------------------------------------

/// Get all blocks in the document as a JSON string. Each block is serialized
/// from the XmlFragment children. Returns a C string that must be freed via
/// `yrs_free_string`. Returns null on error.
#[no_mangle]
pub extern "C" fn yrs_doc_get_blocks_json(doc: *const YrsDoc) -> *mut c_char {
    if doc.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &*doc };
    let txn = doc.doc.transact();

    // Get or lazily initialize the xml fragment
    // Note: We need a mutable transaction to call get_or_insert, but for reading
    // we'll drop and recreate. However get_or_insert needs transact_mut.
    // We drop the read txn first.
    drop(txn);

    let mut txn = doc.doc.transact_mut();
    let fragment = txn.get_or_insert_xml_fragment(FRAGMENT_NAME);
    let len = fragment.len(&txn);

    let mut blocks = Vec::new();
    for i in 0..len {
        if let Some(child) = fragment.get(&txn, i) {
            let block_json = xml_out_to_json(&child, &txn);
            blocks.push(block_json);
        }
    }

    let json_str = match serde_json::to_string(&blocks) {
        Ok(s) => s,
        Err(_) => return ptr::null_mut(),
    };

    match CString::new(json_str) {
        Ok(c) => c.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

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

/// Insert a new block at the given index in the document's XmlFragment.
/// - `block_type`: the XML tag name (e.g. "paragraph", "heading")
/// - `content`: text content for the block
/// - `props_json`: optional JSON object string with block properties/attributes
///
/// Returns the update bytes (diff) that should be broadcast to other clients.
/// The caller must free via `yrs_free_bytes`. Returns null on error.
#[no_mangle]
pub extern "C" fn yrs_doc_insert_block(
    doc: *mut YrsDoc,
    index: u32,
    block_type: *const c_char,
    content: *const c_char,
    props_json: *const c_char,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || block_type.is_null() || out_len.is_null() {
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

    let props: HashMap<String, String> = if props_json.is_null() {
        HashMap::new()
    } else {
        match unsafe { CStr::from_ptr(props_json) }.to_str() {
            Ok(s) => {
                if s.is_empty() {
                    HashMap::new()
                } else {
                    serde_json::from_str(s).unwrap_or_default()
                }
            }
            Err(_) => HashMap::new(),
        }
    };

    // Capture state vector before mutation
    let sv_before = {
        let txn = doc.doc.transact();
        txn.state_vector()
    };

    // Perform the mutation using BlockNote's expected structure:
    //   XmlFragment "document-store"
    //     └── blockGroup
    //         └── blockContainer [id, backgroundColor, textColor]
    //             └── <blockType> [textAlignment, ...props]
    //                 └── XmlText "content"
    {
        let mut txn = doc.doc.transact_mut();
        let fragment = txn.get_or_insert_xml_fragment(FRAGMENT_NAME);

        // Find or create the blockGroup (first child of fragment)
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

        // Clamp index to blockGroup children count
        let bg_len = block_group.len(&txn);
        let safe_index = index.min(bg_len);

        // Create blockContainer with required BlockNote attributes
        let container = block_group.insert(&mut txn, safe_index, XmlElementPrelim::empty("blockContainer"));
        let block_id = uuid::Uuid::new_v4().to_string();
        container.insert_attribute(&mut txn, Arc::<str>::from("id"), block_id);
        container.insert_attribute(&mut txn, Arc::<str>::from("backgroundColor"), "default".to_string());
        container.insert_attribute(&mut txn, Arc::<str>::from("textColor"), "default".to_string());

        // Create the content element (paragraph, heading, etc.) inside container
        let elem_ref = container.insert(&mut txn, 0, XmlElementPrelim::empty(&*tag));
        elem_ref.insert_attribute(&mut txn, Arc::<str>::from("textAlignment"), "left".to_string());

        // Set additional attributes from props_json
        for (key, value) in &props {
            elem_ref.insert_attribute(&mut txn, Arc::<str>::from(key.as_str()), value.clone());
        }

        // Add text content as an XmlText child
        let xml_text = elem_ref.insert(&mut txn, 0, XmlTextPrelim::new(""));
        if !text_content.is_empty() {
            xml_text.insert(&mut txn, 0, &*text_content);
        }
    }

    // Encode the diff (just the changes we made)
    let txn = doc.doc.transact();
    let diff = txn.encode_diff_v1(&sv_before);
    into_byte_ptr(diff, out_len)
}

/// Delete a block at the given index in the document's XmlFragment.
/// Returns the update bytes (diff) for broadcasting.
/// The caller must free via `yrs_free_bytes`. Returns null on error.
#[no_mangle]
pub extern "C" fn yrs_doc_delete_block(
    doc: *mut YrsDoc,
    index: u32,
    out_len: *mut u32,
) -> *mut u8 {
    if doc.is_null() || out_len.is_null() {
        return ptr::null_mut();
    }
    let doc = unsafe { &mut *doc };

    // Capture state vector before mutation
    let sv_before = {
        let txn = doc.doc.transact();
        txn.state_vector()
    };

    // Perform the deletion (remove blockContainer from blockGroup)
    {
        let mut txn = doc.doc.transact_mut();
        let fragment = txn.get_or_insert_xml_fragment(FRAGMENT_NAME);

        // Find the blockGroup
        let block_group = if fragment.len(&txn) > 0 {
            if let Some(yrs::XmlOut::Element(bg)) = fragment.get(&txn, 0) {
                if bg.tag().as_ref() == "blockGroup" {
                    bg
                } else {
                    return ptr::null_mut();
                }
            } else {
                return ptr::null_mut();
            }
        } else {
            return ptr::null_mut();
        };

        // Validate index against blockGroup children count
        let bg_len = block_group.len(&txn);
        if index >= bg_len {
            return ptr::null_mut();
        }

        block_group.remove_range(&mut txn, index, 1);
    }

    // Encode the diff
    let txn = doc.doc.transact();
    let diff = txn.encode_diff_v1(&sv_before);
    into_byte_ptr(diff, out_len)
}

// ---------------------------------------------------------------------------
// Memory management
// ---------------------------------------------------------------------------

/// Free a byte array previously returned by one of the `yrs_doc_*` functions.
/// Passing a null pointer is a safe no-op.
#[no_mangle]
pub extern "C" fn yrs_free_bytes(ptr: *mut u8, len: u32) {
    if !ptr.is_null() {
        unsafe {
            let _ = Vec::from_raw_parts(ptr, len as usize, len as usize);
        }
    }
}

/// Free a C string previously returned by `yrs_doc_get_blocks_json`.
/// Passing a null pointer is a safe no-op.
#[no_mangle]
pub extern "C" fn yrs_free_string(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            let _ = CString::from_raw(ptr);
        }
    }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/// Move a `Vec<u8>` onto the heap and return a raw pointer + length.
/// The caller is responsible for freeing via `yrs_free_bytes`.
fn into_byte_ptr(data: Vec<u8>, out_len: *mut u32) -> *mut u8 {
    let len = data.len();
    unsafe {
        *out_len = len as u32;
    }
    // Convert Vec into a boxed slice so we have a stable pointer
    let mut boxed = data.into_boxed_slice();
    let ptr = boxed.as_mut_ptr();
    // Leak the boxed slice so the caller can free it later
    std::mem::forget(boxed);
    ptr
}

/// Convert an XmlOut node to a JSON value for serialization.
fn xml_out_to_json<T: ReadTxn>(node: &yrs::XmlOut, txn: &T) -> serde_json::Value {
    match node {
        yrs::XmlOut::Element(elem) => {
            let mut obj = serde_json::Map::new();
            obj.insert(
                "type".to_string(),
                serde_json::Value::String(elem.tag().to_string()),
            );

            // Collect attributes
            let mut attrs = serde_json::Map::new();
            for (key, value) in elem.attributes(txn) {
                attrs.insert(key.to_string(), serde_json::Value::String(value));
            }
            if !attrs.is_empty() {
                obj.insert("props".to_string(), serde_json::Value::Object(attrs));
            }

            // Collect children content
            let children: Vec<serde_json::Value> = elem
                .children(txn)
                .map(|child| xml_out_to_json(&child, txn))
                .collect();
            let has_children = !children.is_empty();
            if has_children {
                obj.insert("children".to_string(), serde_json::Value::Array(children));
            }

            // If the element has no structured children, try to extract text content
            if !has_children {
                // Try to get text content from xml string
                let text = elem.get_string(txn);
                // Strip the wrapping tags to get just the inner text
                let tag: &str = elem.tag();
                let open_tag_end = format!("<{}>", tag);
                let close_tag = format!("</{}>", tag);
                if let Some(inner) = text
                    .strip_prefix(&open_tag_end)
                    .and_then(|s| s.strip_suffix(&close_tag))
                {
                    if !inner.is_empty() {
                        obj.insert(
                            "content".to_string(),
                            serde_json::Value::String(inner.to_string()),
                        );
                    }
                }
            }

            serde_json::Value::Object(obj)
        }
        yrs::XmlOut::Text(text) => {
            let s = text.get_string(txn);
            serde_json::Value::String(s)
        }
        yrs::XmlOut::Fragment(frag) => {
            let mut children = Vec::new();
            for child in frag.children(txn) {
                children.push(xml_out_to_json(&child, txn));
            }
            serde_json::Value::Array(children)
        }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    #[test]
    fn test_doc_lifecycle() {
        let doc = yrs_doc_new();
        assert!(!doc.is_null());
        yrs_doc_destroy(doc);
    }

    #[test]
    fn test_destroy_null_is_safe() {
        yrs_doc_destroy(ptr::null_mut());
    }

    #[test]
    fn test_state_vector_roundtrip() {
        let doc = yrs_doc_new();
        let mut len: u32 = 0;
        let sv = yrs_doc_state_vector(doc, &mut len);
        assert!(!sv.is_null());
        assert!(len > 0);
        yrs_free_bytes(sv, len);
        yrs_doc_destroy(doc);
    }

    #[test]
    fn test_encode_state() {
        let doc = yrs_doc_new();
        let mut len: u32 = 0;
        let state = yrs_doc_encode_state(doc, &mut len);
        assert!(!state.is_null());
        assert!(len > 0);
        yrs_free_bytes(state, len);
        yrs_doc_destroy(doc);
    }

    #[test]
    fn test_sync_between_docs() {
        let doc1 = yrs_doc_new();
        let doc2 = yrs_doc_new();

        // Insert a block into doc1
        let tag = CString::new("paragraph").unwrap();
        let content = CString::new("Hello, world!").unwrap();
        let mut update_len: u32 = 0;
        let update = yrs_doc_insert_block(
            doc1,
            0,
            tag.as_ptr(),
            content.as_ptr(),
            ptr::null(),
            &mut update_len,
        );
        assert!(!update.is_null());

        // Encode state from doc1
        let mut state_len: u32 = 0;
        let state = yrs_doc_encode_state(doc1, &mut state_len);
        assert!(!state.is_null());

        // Apply to doc2
        let result = yrs_doc_load_state(doc2, state, state_len);
        assert_eq!(result, 0);

        // Read blocks from doc2
        let json = yrs_doc_get_blocks_json(doc2);
        assert!(!json.is_null());
        let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
        assert!(json_str.contains("paragraph"));
        assert!(json_str.contains("Hello, world!") || json_str.contains("Hello"));

        yrs_free_string(json);
        yrs_free_bytes(state, state_len);
        yrs_free_bytes(update, update_len);
        yrs_doc_destroy(doc1);
        yrs_doc_destroy(doc2);
    }

    #[test]
    fn test_insert_and_delete_block() {
        let doc = yrs_doc_new();

        // Insert two blocks
        let tag1 = CString::new("paragraph").unwrap();
        let content1 = CString::new("First block").unwrap();
        let mut len1: u32 = 0;
        let update1 = yrs_doc_insert_block(
            doc,
            0,
            tag1.as_ptr(),
            content1.as_ptr(),
            ptr::null(),
            &mut len1,
        );
        assert!(!update1.is_null());

        let tag2 = CString::new("heading").unwrap();
        let content2 = CString::new("Second block").unwrap();
        let props = CString::new(r#"{"level":"2"}"#).unwrap();
        let mut len2: u32 = 0;
        let update2 = yrs_doc_insert_block(
            doc,
            1,
            tag2.as_ptr(),
            content2.as_ptr(),
            props.as_ptr(),
            &mut len2,
        );
        assert!(!update2.is_null());

        // Check we have blockGroup with 2 blockContainers
        let json = yrs_doc_get_blocks_json(doc);
        let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
        let parsed: Vec<serde_json::Value> = serde_json::from_str(json_str).unwrap();
        assert_eq!(parsed.len(), 1); // one blockGroup
        assert_eq!(parsed[0]["type"], "blockGroup");
        let containers = parsed[0]["children"].as_array().unwrap();
        assert_eq!(containers.len(), 2);
        yrs_free_string(json);

        // Delete first block (blockContainer at index 0 inside blockGroup)
        let mut del_len: u32 = 0;
        let del_update = yrs_doc_delete_block(doc, 0, &mut del_len);
        assert!(!del_update.is_null());

        // Check we have 1 blockContainer left
        let json = yrs_doc_get_blocks_json(doc);
        let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
        let parsed: Vec<serde_json::Value> = serde_json::from_str(json_str).unwrap();
        let containers = parsed[0]["children"].as_array().unwrap();
        assert_eq!(containers.len(), 1);
        // Remaining container should hold the heading
        assert!(json_str.contains("heading"));
        yrs_free_string(json);

        yrs_free_bytes(update1, len1);
        yrs_free_bytes(update2, len2);
        yrs_free_bytes(del_update, del_len);
        yrs_doc_destroy(doc);
    }

    #[test]
    fn test_apply_update() {
        let doc1 = yrs_doc_new();
        let doc2 = yrs_doc_new();

        // Get doc2's state vector
        let mut sv_len: u32 = 0;
        let sv = yrs_doc_state_vector(doc2, &mut sv_len);
        assert!(!sv.is_null());

        // Insert a block in doc1
        let tag = CString::new("paragraph").unwrap();
        let content = CString::new("Synced content").unwrap();
        let mut ins_len: u32 = 0;
        let _ins = yrs_doc_insert_block(
            doc1,
            0,
            tag.as_ptr(),
            content.as_ptr(),
            ptr::null(),
            &mut ins_len,
        );

        // Get diff from doc1 relative to doc2's state vector
        let mut diff_len: u32 = 0;
        let diff = yrs_doc_encode_diff(doc1, sv, sv_len, &mut diff_len);
        assert!(!diff.is_null());

        // Apply diff to doc2
        let mut applied_len: u32 = 0;
        let applied = yrs_doc_apply_update(doc2, diff, diff_len, &mut applied_len);
        assert!(!applied.is_null());
        assert_eq!(applied_len, diff_len);

        // Verify doc2 has the block
        let json = yrs_doc_get_blocks_json(doc2);
        let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
        assert!(json_str.contains("paragraph"));

        yrs_free_string(json);
        yrs_free_bytes(applied, applied_len);
        yrs_free_bytes(diff, diff_len);
        yrs_free_bytes(sv, sv_len);
        yrs_free_bytes(_ins, ins_len);
        yrs_doc_destroy(doc1);
        yrs_doc_destroy(doc2);
    }

    #[test]
    fn test_free_null_is_safe() {
        yrs_free_bytes(ptr::null_mut(), 0);
        yrs_free_string(ptr::null_mut());
    }

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

    #[test]
    fn test_get_blocks_empty_doc() {
        let doc = yrs_doc_new();
        let json = yrs_doc_get_blocks_json(doc);
        assert!(!json.is_null());
        let json_str = unsafe { CStr::from_ptr(json) }.to_str().unwrap();
        assert_eq!(json_str, "[]");
        yrs_free_string(json);
        yrs_doc_destroy(doc);
    }
}
