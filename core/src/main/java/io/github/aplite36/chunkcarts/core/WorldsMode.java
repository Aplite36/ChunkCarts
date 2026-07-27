package io.github.aplite36.chunkcarts.core;

/** How the configured world list is interpreted. */
public enum WorldsMode {

    /** Works everywhere except the listed worlds. An empty list means everywhere. The default. */
    BLOCKLIST,

    /** Works only in the listed worlds. An empty list disables the plugin entirely. */
    ALLOWLIST
}
