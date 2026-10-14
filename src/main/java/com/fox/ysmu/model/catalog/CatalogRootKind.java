package com.fox.ysmu.model.catalog;

/**
 * The classification of one server-side scan root, the port of upstream's
 * {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/model/catalog/source/CatalogRootKind.java:3-24}.
 * <p>
 * Upstream's own javadoc calls it "scanner-local root classification; never appears in source-neutral catalogs or
 * the unstable protocol", and the same holds here: the port has no catalog object, so the only place a root kind
 * is stored is {@code model/ServerModelManager}'s registered-source table, and the only thing derived from it is
 * the {@linkplain AccessPolicy access policy} that becomes a model's {@link CatalogAccess}.
 * <p>
 * The three roots map one-to-one onto the port's three scan directories: {@code builtin} is
 * {@code config/ysmu/builtin} (the extracted {@code assets/ysmu/builtin} tree), {@code custom} is
 * {@code config/ysmu/custom}, and {@code auth} is {@code config/ysmu/auth} - the root this port gained when the
 * Auth mechanism was ported. Upstream's {@code AssetPaths.authModelsRoot()} is {@code <game>/ysm/auth}
 * ({@code AssetPaths.java:32,100-107}); this port keeps every model root under {@code config/ysmu/}, which is
 * where its two existing roots already live.
 */
public enum CatalogRootKind {

    BUILTIN("builtin", AccessPolicy.PUBLIC),
    CUSTOM("custom", AccessPolicy.PUBLIC),
    AUTH("auth", AccessPolicy.SESSION_AUTHORIZED);

    private final String namespace;
    private final AccessPolicy accessPolicy;

    CatalogRootKind(String namespace, AccessPolicy accessPolicy) {
        this.namespace = namespace;
        this.accessPolicy = accessPolicy;
    }

    /** The directory name this root is scanned from; upstream's field of the same name. */
    public String namespace() {
        return namespace;
    }

    /** Whether a model from this root is public or needs a per-player grant; upstream's field of the same name. */
    public AccessPolicy accessPolicy() {
        return accessPolicy;
    }
}
