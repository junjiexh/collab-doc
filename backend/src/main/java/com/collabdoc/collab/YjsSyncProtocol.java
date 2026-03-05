package com.collabdoc.collab;

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
