package com.fox.ysmu.client.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import com.fox.ysmu.util.ModelPathUtil;

/**
 * Folder (upstream calls it "pack") browsing state for the model selection GUI, ported from the 1.20 development
 * tree's {@code com.elfmcys.ysm.client.gui.CatalogBrowserState}.
 * <p>
 * A model's display path is its directory relative to {@code config/ysmu/custom} ({@code wine_fox/01_taisho_maid} or
 * {@code steve}); see {@link ModelPathUtil}. The folder a model lives in is shown as one tile instead of listing the
 * model with its whole path, so the grid groups a pack's models the way the upstream catalog does.
 * <p>
 * The rules this class implements are upstream's, in order:
 * <ul>
 * <li>Folders come from the server's pack list when it announced one (manifest name and description included), and are
 * otherwise derived from the models themselves: every parent hierarchy of every model becomes a folder, including the
 * intermediate ones ({@code a/b/c} yields the folders {@code a/} and {@code a/b/}). Upstream calls the derived ones
 * synthetic packs.</li>
 * <li>With an empty query, the "all models" category shows only the folders and models that are <b>direct children</b>
 * of the current folder. The star category shows every starred model flat, because it is a "show me my collection" view
 * rather than a place in the tree.</li>
 * <li>With a non-empty query, models from every folder are matched by their display path and folders are hidden, except
 * for a query starting with {@code #}, which matches folder names only.</li>
 * <li>The page number is remembered per folder, so going back into a folder returns to the page the user left.</li>
 * </ul>
 * The current folder and the per-folder page live in static fields on purpose, like both upstream generations: closing
 * and reopening the GUI returns the user to the folder they were browsing.
 * <p>
 * No Minecraft types are used here, which keeps the folder rules unit testable.
 */
public final class ModelBrowserState {

    /** A query with this prefix matches folder names only, mirroring upstream's {@code PACK_SEARCH_PREFIX}. */
    private static final String PACK_SEARCH_PREFIX = "#";

    private static final Map<String, Integer> PAGE_BY_PACK = new HashMap<>();
    private static String currentPack = "";

    /** Folders by hierarchy ({@code "wine_fox/"}); the value is the label shown on the tile. */
    private final Map<String, String> allPacks = new LinkedHashMap<>();

    /** Descriptions from the pack manifests, used only to match folder searches. */
    private final Map<String, String> packDescriptions = new LinkedHashMap<>();

    private List<String> allModels = Collections.emptyList();
    private List<String> models = Collections.emptyList();
    private List<String> packs = Collections.emptyList();
    private Category category = Category.ALL;
    private int maxPage;

    /**
     * Replaces the known folders and models.
     * <p>
     * Folders come from two sources. The authoritative one is the server's pack list ({@code packNames} /
     * {@code packDescriptions}); the other is derived from the models themselves, so a folder that exists only
     * because models live under it is still browsable. The derived folder gets the hierarchy's last segment as its
     * label, exactly like upstream's synthetic packs, and never overrides a real pack's own name.
     *
     * @param modelPaths       display paths of every model the client has; may be {@code null}
     * @param packNames        folder hierarchy to manifest name for the server's packs; may be {@code null}
     * @param packDescriptions folder hierarchy to manifest description; may be {@code null}
     */
    public void rebuild(Collection<String> modelPaths, Map<String, String> packNames,
        Map<String, String> packDescriptions) {
        allPacks.clear();
        this.packDescriptions.clear();
        if (packNames != null) {
            for (Map.Entry<String, String> entry : packNames.entrySet()) {
                String hierarchy = entry.getKey();
                if (hierarchy == null || hierarchy.isEmpty()) {
                    continue;
                }
                String name = entry.getValue();
                allPacks.put(hierarchy, name == null || name.trim()
                    .isEmpty() ? ModelPathUtil.lastFolderName(hierarchy) : name);
            }
        }
        if (packDescriptions != null) {
            this.packDescriptions.putAll(packDescriptions);
        }

        List<String> known = new ArrayList<>();
        if (modelPaths != null) {
            known.addAll(modelPaths);
        }
        // Upstream sorts models by display path while filtering; doing it once here keeps the order stable between
        // filters (folders do not reorder models) and groups a folder's models together while searching.
        Collections.sort(known);
        for (String path : known) {
            addSyntheticPacks(ModelPathUtil.parentHierarchy(path));
        }
        allModels = known;
        if (!currentPack.isEmpty() && !allPacks.containsKey(currentPack)) {
            currentPack = "";
        }
    }

    /**
     * Applies the search query, the category and the current folder, producing the two lists the grid draws.
     *
     * @param query  raw text of the search box; may be {@code null}
     * @param starred decides whether a model belongs to the star category; may be {@code null}
     */
    public void filter(String query, Predicate<String> starred) {
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ENGLISH);

        List<String> visibleModels = new ArrayList<>();
        for (String path : allModels) {
            if (isVisible(path, search, starred)) {
                visibleModels.add(path);
            }
        }
        models = visibleModels;

        if (category != Category.ALL || (!search.isEmpty() && !search.startsWith(PACK_SEARCH_PREFIX))) {
            packs = Collections.emptyList();
        } else {
            String packSearch = search.startsWith(PACK_SEARCH_PREFIX) ? search.substring(1) : search;
            List<String> visiblePacks = new ArrayList<>();
            for (String hierarchy : allPacks.keySet()) {
                boolean matches = search.isEmpty() ? ModelPathUtil.isDirectChild(currentPack, hierarchy)
                    : packMatches(hierarchy, packSearch);
                if (matches) {
                    visiblePacks.add(hierarchy);
                }
            }
            Collections.sort(visiblePacks);
            packs = visiblePacks;
        }

        maxPage = Math.max(0, (models.size() + packs.size() - 1) / 10);
        if (page() > maxPage) {
            resetPage();
        }
    }

    private boolean isVisible(String path, String search, Predicate<String> starred) {
        if (category == Category.STAR && (starred == null || !starred.test(path))) {
            return false;
        }
        if (search.isEmpty()) {
            // Inside a folder the grid shows that folder's content; the star view is flat by design.
            return category != Category.ALL || ModelPathUtil.parentHierarchy(path).equals(currentPack);
        }
        return matchesPath(path, search);
    }

    private static boolean matchesPath(String path, String search) {
        if (search.startsWith(PACK_SEARCH_PREFIX)) {
            // A folder-only search does not list the models inside the matching folders.
            return false;
        }
        return path.toLowerCase(Locale.ENGLISH)
            .contains(search);
    }

    private boolean packMatches(String hierarchy, String search) {
        if (hierarchy.toLowerCase(Locale.ENGLISH)
            .contains(search)) {
            return true;
        }
        String name = allPacks.get(hierarchy);
        if (name != null && name.toLowerCase(Locale.ENGLISH)
            .contains(search)) {
            return true;
        }
        String description = packDescriptions.get(hierarchy);
        return description != null && description.toLowerCase(Locale.ENGLISH)
            .contains(search);
    }

    /** Adds every hierarchy prefix of {@code hierarchy}, so intermediate folders are browsable. */
    private void addSyntheticPacks(String hierarchy) {
        StringBuilder current = new StringBuilder();
        int start = 0;
        while (start < hierarchy.length()) {
            int slash = hierarchy.indexOf('/', start);
            if (slash < 0) {
                break;
            }
            String part = hierarchy.substring(start, slash);
            start = slash + 1;
            if (part.isEmpty()) {
                continue;
            }
            current.append(part)
                .append('/');
            String value = current.toString();
            if (!allPacks.containsKey(value)) {
                allPacks.put(value, part);
            }
        }
    }

    public List<String> models() {
        return models;
    }

    public List<String> packs() {
        return packs;
    }

    /** The label of a folder tile: the last segment of its hierarchy. */
    public String packName(String hierarchy) {
        String name = allPacks.get(hierarchy);
        return name == null ? ModelPathUtil.lastFolderName(hierarchy) : name;
    }

    public String currentPack() {
        return currentPack;
    }

    /** Opens a folder. Callers reset the page through the per-folder page map. */
    public void enterPack(String hierarchy) {
        currentPack = hierarchy == null ? "" : hierarchy;
        resetPage();
    }

    /** Moves to the parent folder and forgets the page this folder was left on, like upstream. */
    public void backToParent() {
        String trimmed = currentPack.endsWith("/") ? currentPack.substring(0, currentPack.length() - 1) : currentPack;
        int slash = trimmed.lastIndexOf('/');
        String previous = currentPack;
        currentPack = slash < 0 ? "" : trimmed.substring(0, slash + 1);
        PAGE_BY_PACK.remove(previous);
    }

    public Category category() {
        return category;
    }

    public void category(Category category) {
        this.category = category == null ? Category.ALL : category;
        resetPage();
    }

    public int page() {
        Integer stored = PAGE_BY_PACK.get(currentPack);
        return stored == null ? 0 : stored;
    }

    public void page(int page) {
        PAGE_BY_PACK.put(currentPack, page);
    }

    public void resetPage() {
        page(0);
    }

    public int maxPage() {
        return maxPage;
    }

    /** Which slice of the models the grid shows; only {@link #ALL} has folders. */
    public enum Category {
        ALL,
        STAR
    }
}
