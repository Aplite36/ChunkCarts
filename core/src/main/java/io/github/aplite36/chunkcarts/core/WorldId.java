package io.github.aplite36.chunkcarts.core;

import java.util.Objects;

/**
 * Opaque identifier for a world, so {@code core} never touches a platform world type.
 *
 * <p>The adapter owns the mapping and it must be <em>stable across a restart</em>, because
 * a journey's origin is persisted (SPEC §7) and a restored journey compares its origin
 * against a freshly-resolved id. An identifier derived from load order would silently
 * relocate every restored origin.
 */
public record WorldId(String value) {

    public WorldId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("world id must not be blank");
        }
    }
}
