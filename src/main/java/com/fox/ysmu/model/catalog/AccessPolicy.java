package com.fox.ysmu.model.catalog;

/**
 * How a scan root's models may be accessed, the port of upstream's
 * {@code tmp/YesSteveModel-dev-1.20/src/main/java/com/elfmcys/ysm/model/catalog/source/AccessPolicy.java:1-7}.
 * <p>
 * Upstream keeps three constants; this port declares the same three with the same names and order, because the
 * value is evidence of what was read and not invented. Only two of them are in use:
 * <ul>
 * <li>{@link #PUBLIC} - every player may select the model, and (unless {@code RestrictedAuth} is on) its bytes may
 * be sent to any client that asks. Upstream's {@code BUILTIN} and {@code CUSTOM} roots are both public
 * ({@code CatalogRootKind.java:5-6}).</li>
 * <li>{@link #SESSION_AUTHORIZED} - only a player holding a grant for the model may select it
 * ({@code model/session/server/ServerModelSession.java:317-322}), and with {@code RestrictedAuth} on only such a
 * player may receive its runtime assets ({@code :205-221}). Upstream's {@code AUTH} root
 * ({@code CatalogRootKind.java:7}).</li>
 * <li>{@link #LOCAL_ONLY} - declared for parity. Upstream's own {@code CatalogRootKind} does not use it either:
 * grep for {@code LOCAL_ONLY} over its {@code src/main/java} finds only the declaration. This port has no
 * local-only root, so nothing reads it; it is carried so the enum can be compared against upstream constant by
 * constant.</li>
 * </ul>
 */
public enum AccessPolicy {
    PUBLIC,
    SESSION_AUTHORIZED,
    LOCAL_ONLY
}
