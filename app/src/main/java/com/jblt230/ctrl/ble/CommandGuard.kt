package com.jblt230.ctrl.ble

/**
 * Hard interception point: every outbound frame must pass through here.
 * Kept in sync with protocol-spec/DANGEROUS_COMMANDS.md.
 *
 * Uses stderr instead of android.util.Log so the guard is testable on a plain
 * JVM (Log.w throws "not mocked" in local unit tests).
 */
class CommandGuard(private val blacklist: Set<Int>) {

    class BlockedCommandException(cmd: Int) :
        SecurityException("CommandGuard BLOCK: 0x%02X is on the dangerous-command blacklist".format(cmd))

    fun isBlocked(frame: ByteArray): Boolean {
        if (frame.size < 2) return true
        if ((frame[0].toInt() and 0xFF) != FrameCodec.HEADER) return true
        return (frame[1].toInt() and 0xFF) in blacklist
    }

    /** Throws instead of silently dropping: a blocked write must never be mistaken for success. */
    fun assertAllowed(frame: ByteArray) {
        val cmd = frame.getOrNull(1)?.toInt()?.and(0xFF) ?: -1
        if (isBlocked(frame)) {
            System.err.println("$TAG BLOCK 0x%02X — refused before GATT write".format(cmd))
            throw BlockedCommandException(cmd)
        }
    }

    /**
     * Validates raw continuation packets of a multi-part BES 0x41 EQ transfer
     * (`<seqIndex: 0x00..0x0F> <chunkBytes...>`). These packets intentionally omit
     * the 0xAA header (matching EqCmdData.getPayload), so only small sequence
     * indices preceded by a guard-approved 0x41 header are accepted here.
     */
    fun assertAllowedEqChunk(chunk: ByteArray, expectedSeqIndex: Int) {
        if (chunk.size < 2 || chunk.size > 80) {
            throw BlockedCommandException(-1)
        }
        val seq = chunk[0].toInt() and 0xFF
        if (seq != expectedSeqIndex || seq !in 0x00..0x0F) {
            throw BlockedCommandException(-1)
        }
    }

    /** Unresolved command families have no frame and must never be sent. */
    fun assertResolvable(frameHex: String?, requiresResolution: Boolean) {
        if (requiresResolution || frameHex == null) {
            System.err.println("$TAG BLOCK unresolved command family — no frame to send")
            throw BlockedCommandException(-1)
        }
    }

    companion object {
        private const val TAG = "CommandGuard"
    }
}
