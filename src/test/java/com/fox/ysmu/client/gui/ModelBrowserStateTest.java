package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.gui.ModelBrowserState.Category;

/**
 * Covers the folder rules the model selection GUI relies on, ported from upstream's {@code CatalogBrowserState}.
 * <p>
 * The current folder and the per-folder page are static (so reopening the GUI resumes browsing), therefore every test
 * starts by returning to the root and uses its own folder names; otherwise one test's leftover page could be observed
 * by the next one.
 */
class ModelBrowserStateTest {

    private ModelBrowserState state;

    @BeforeEach
    void resetToRoot() {
        state = new ModelBrowserState();
        state.enterPack("");
        state.category(Category.ALL);
    }

    /** Rebuild with model paths only: no pack manifest was announced, so folders are derived from the paths. */
    private void rebuild(Collection<String> modelPaths) {
        state.rebuild(modelPaths, Collections.emptyMap(), Collections.emptyMap());
    }

    private static Map<String, String> names(String... hierarchyThenName) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < hierarchyThenName.length; i += 2) {
            map.put(hierarchyThenName[i], hierarchyThenName[i + 1]);
        }
        return map;
    }

    @Test
    void rootShowsOnlyDirectChildFoldersAndRootModels() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid", "wine_fox/02_new_year", "wine_fox/2024/party"));

        state.filter("", path -> false);

        assertEquals(Collections.singletonList("steve"), state.models());
        assertEquals(Collections.singletonList("wine_fox/"), state.packs());
        assertEquals("wine_fox", state.packName("wine_fox/"));
    }

    @Test
    void enteringFolderShowsItsModelsAndSubfolders() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid", "wine_fox/02_new_year", "wine_fox/2024/party"));

        state.enterPack("wine_fox/");
        state.filter("", path -> false);

        assertEquals(Arrays.asList("wine_fox/01_taisho_maid", "wine_fox/02_new_year"), state.models());
        assertEquals(Collections.singletonList("wine_fox/2024/"), state.packs());
    }

    @Test
    void nestedFoldersAreSynthesisedFromTheModels() {
        rebuild(Collections.singletonList("a/b/c"));

        state.filter("", path -> false);
        assertEquals(Collections.singletonList("a/"), state.packs());
        assertTrue(state.models().isEmpty());

        state.enterPack("a/");
        state.filter("", path -> false);
        assertEquals(Collections.singletonList("a/b/"), state.packs());
        assertTrue(state.models().isEmpty());

        state.enterPack("a/b/");
        state.filter("", path -> false);
        assertEquals(Collections.singletonList("a/b/c"), state.models());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void backToParentWalksUpOneSegmentAndForgetsTheFolderPage() {
        rebuild(Arrays.asList("q/r/s", "q/t"));

        state.enterPack("q/r/");
        state.page(3);
        state.backToParent();
        assertEquals("q/", state.currentPack());

        state.enterPack("q/r/");
        assertEquals(0, state.page());

        state.backToParent();
        state.backToParent();
        assertEquals("", state.currentPack());
    }

    @Test
    void pageIsRememberedWhileAFolderStaysOpen() {
        // Eleven models give the folder two pages, otherwise the page is clamped back to zero by filter().
        Set<String> many = new HashSet<>();
        for (int i = 0; i < 11; i++) {
            many.add("z/model_" + i);
        }
        rebuild(many);

        state.enterPack("z/");
        state.page(1);
        state.filter("", path -> false);

        assertEquals(1, state.maxPage());
        assertEquals(1, state.page());
    }

    @Test
    void hashPrefixMatchesFoldersOnly() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid"));

        state.filter("#wine", path -> false);

        assertTrue(state.models().isEmpty());
        assertEquals(Collections.singletonList("wine_fox/"), state.packs());
    }

    @Test
    void plainSearchFlattensAcrossFoldersAndHidesFolders() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid"));
        state.enterPack("wine_fox/");

        state.filter("steve", path -> false);

        assertEquals(Collections.singletonList("steve"), state.models());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void starCategoryIsFlatAndHasNoFolders() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid"));
        state.category(Category.STAR);

        state.filter("", "wine_fox/01_taisho_maid"::equals);

        assertEquals(Collections.singletonList("wine_fox/01_taisho_maid"), state.models());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void sameStarredModelIsHiddenWhenThePredicateSaysSo() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid"));
        state.category(Category.STAR);

        state.filter("", path -> path.equals("steve"));

        assertEquals(Collections.singletonList("steve"), state.models());
    }

    @Test
    void folderThatDisappearedSendsTheViewBackToTheRoot() {
        rebuild(Collections.singletonList("gone/model"));
        state.enterPack("gone/");
        state.filter("", path -> false);
        assertEquals("gone/", state.currentPack());
        assertEquals(Collections.singletonList("gone/model"), state.models());

        rebuild(Collections.singletonList("other"));

        assertEquals("", state.currentPack());
        state.filter("", path -> false);
        assertEquals(Collections.singletonList("other"), state.models());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void maxPageCountsFoldersAndModelsAndClampsAStalePage() {
        Set<String> many = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            many.add("model_" + i);
        }
        many.add("pack_/inner");
        rebuild(many);

        state.filter("", path -> false);
        assertEquals(11, state.models().size() + state.packs().size());
        assertEquals(1, state.maxPage());

        state.page(1);
        rebuild(Collections.singletonList("model_0"));
        state.filter("", path -> false);

        assertEquals(0, state.maxPage());
        assertEquals(0, state.page());
    }

    @Test
    void searchKeepsTheAlphabeticalPathOrder() {
        rebuild(Arrays.asList("b_pack/z", "a_pack/y"));

        state.filter("_pack", path -> false);

        List<String> models = state.models();
        assertEquals(Arrays.asList("a_pack/y", "b_pack/z"), models);
        assertFalse(models.isEmpty());
    }

    @Test
    void packManifestNameLabelsTheFolder() {
        state.rebuild(
            Collections.singletonList("wine_fox/01_taisho_maid"),
            names("wine_fox/", "酒狐与小伙伴"),
            Collections.emptyMap());

        state.filter("", path -> false);

        assertEquals(Collections.singletonList("wine_fox/"), state.packs());
        assertEquals("酒狐与小伙伴", state.packName("wine_fox/"));
    }

    @Test
    void packWithoutAnyModelsIsStillBrowsable() {
        state.rebuild(Collections.emptyList(), names("empty_pack/", "Empty Pack"), Collections.emptyMap());

        state.filter("", path -> false);
        assertEquals(Collections.singletonList("empty_pack/"), state.packs());
        assertEquals("Empty Pack", state.packName("empty_pack/"));

        state.enterPack("empty_pack/");
        state.filter("", path -> false);
        assertTrue(state.models().isEmpty());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void folderSearchMatchesManifestNameAndDescription() {
        state.rebuild(
            Collections.singletonList("wine_fox/01_taisho_maid"),
            names("wine_fox/", "酒狐与小伙伴"),
            names("wine_fox/", "可爱酒狐和她的小伙伴们"));

        state.filter("#酒狐", path -> false);
        assertEquals(Collections.singletonList("wine_fox/"), state.packs());

        state.filter("#可爱", path -> false);
        assertEquals(Collections.singletonList("wine_fox/"), state.packs());

        state.filter("#Wine Fox", path -> false);
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void blankManifestNameFallsBackToTheFolderSegment() {
        state.rebuild(
            Collections.singletonList("a/b/x"),
            names("a/", "", "a/b/", "   "),
            Collections.emptyMap());

        state.filter("", path -> false);
        assertEquals("a", state.packName("a/"));

        state.enterPack("a/");
        state.filter("", path -> false);
        assertEquals("b", state.packName("a/b/"));
    }
}
