package com.fox.ysmu.model.catalog;

/**
 * Whether one model may be selected by every player, or only by a player holding a grant for it - the port of
 * upstream's {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/model/catalog/snapshot/CatalogAccess.java:3-6}.
 * <p>
 * It is the value the access decision is made on, and the value upstream puts on the wire per catalog entry
 * ({@code network/forge/SessionCollectionPublication.java:755-757} writes it as
 * {@code CATALOG_ACCESS_PUBLIC}/{@code CATALOG_ACCESS_AUTHORIZED}), which is how a client can draw a locked tile
 * without knowing which directory on the server a model came from.
 * <p>
 * Derivation, exactly upstream's ({@code model/catalog/snapshot/CatalogRecord.java:32-33}): a model is
 * {@link #AUTHORIZED} if and only if its effective (winning) source root is {@link CatalogRootKind#AUTH}.
 */
public enum CatalogAccess {
    PUBLIC,
    AUTHORIZED
}
