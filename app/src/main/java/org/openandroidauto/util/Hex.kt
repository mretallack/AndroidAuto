package org.openandroidauto.util

/**
 * Hex helpers for logging payloads we don't (yet) understand.
 *
 * Rationale: when the HU sends something we do not handle, we must record
 * *what* it sent — not merely that something arrived — so it can be
 * investigated offline. See docs/connection-stability.md §6.1b.
 */

/** Space-separated lowercase hex, all bytes. */
fun ByteArray.hex(): String = joinToString(" ") { "%02x".format(it) }

/**
 * Space-separated lowercase hex, capped at [max] bytes with a `…(+N more)`
 * suffix. Use for large payloads (e.g. video) to avoid flooding the log.
 */
fun ByteArray.hex(max: Int): String =
    if (size <= max) hex()
    else take(max).joinToString(" ") { "%02x".format(it) } +
        " …(+${size - max} more, total=${size})"
