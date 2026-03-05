package com.collabdoc.collab;

import com.collabdoc.yrs.YrsBridge;
import com.collabdoc.yrs.YrsDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Get document content as JSON. */
    public String getBlocksJson(UUID docId) {
        var doc = getOrLoadDocument(docId);
        synchronized (doc) {
            return doc.getBlocksJson();
        }
    }

    /** Create a snapshot and clean up incremental updates. */
    @Transactional
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
