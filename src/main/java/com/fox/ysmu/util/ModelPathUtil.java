package com.fox.ysmu.util;

/**
 * Path arithmetic for model display paths.
 * <p>
 * A model display path is the model's directory relative to {@code config/ysmu/custom}, with {@code /} as the
 * separator, for example {@code wine_fox/01_taisho_maid} or {@code steve}. It is what
 * {@link ModelIdUtil#getModelDisplayName(net.minecraft.util.ResourceLocation)} produces: model ids encode the
 * whole path into one {@code ResourceLocation} segment, so the folder structure is only visible again after
 * decoding.
 * <p>
 * A <b>hierarchy</b> is a folder path including its trailing slash: {@code ""} is the root, {@code wine_fox/} is
 * a top-level folder and {@code a/b/} is nested. That trailing slash is what lets the model selection GUI tell a
 * direct child ({@code a/}) apart from a grandchild ({@code a/b/}) with a single {@code startsWith} test.
 * <p>
 * This class is deliberately free of Minecraft imports so the folder rules can be unit tested.
 */
public final class ModelPathUtil {

    private ModelPathUtil() {}

    /** The folder a model lives in, including its trailing slash; {@code ""} when the model sits at the root. */
    public static String parentHierarchy(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            return "";
        }
        int slash = modelPath.lastIndexOf('/');
        return slash < 0 ? "" : modelPath.substring(0, slash + 1);
    }

    /** The last path segment, that is the file name without any folder part. */
    public static String fileName(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            return "";
        }
        int slash = modelPath.lastIndexOf('/');
        return slash < 0 ? modelPath : modelPath.substring(slash + 1);
    }

    /** The last segment of a hierarchy, used as the label of a folder tile: {@code a/b/} becomes {@code b}. */
    public static String lastFolderName(String hierarchy) {
        if (hierarchy == null || hierarchy.isEmpty()) {
            return "";
        }
        String trimmed = hierarchy.endsWith("/") ? hierarchy.substring(0, hierarchy.length() - 1) : hierarchy;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    /**
     * Whether {@code candidate} is a folder directly inside {@code parent}, mirroring upstream's
     * {@code CatalogBrowserState.directChild}: the candidate must start with the parent and the remainder may only
     * end in a slash. {@code parent=""} matches {@code a/} but not {@code a/b/}.
     */
    public static boolean isDirectChild(String parent, String candidate) {
        if (parent == null || candidate == null || parent.equals(candidate) || !candidate.startsWith(parent)) {
            return false;
        }
        String remainder = candidate.substring(parent.length());
        return remainder.indexOf('/') == remainder.length() - 1;
    }
}
