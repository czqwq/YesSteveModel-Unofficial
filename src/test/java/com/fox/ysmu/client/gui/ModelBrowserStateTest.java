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
import java.util.function.Predicate;

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

    /**
     * Filter with nothing locked: the authorization predicate says "no model is restricted and ungranted", which is
     * what every test but the auth ones means. The auth tests call {@code state.filter} directly with a real one.
     */
    private void filter(String query, Predicate<String> starred) {
        state.filter(query, path -> false, starred);
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

        filter("", path -> false);

        assertEquals(Collections.singletonList("steve"), state.models());
        assertEquals(Collections.singletonList("wine_fox/"), state.packs());
        assertEquals("wine_fox", state.packName("wine_fox/"));
    }

    @Test
    void enteringFolderShowsItsModelsAndSubfolders() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid", "wine_fox/02_new_year", "wine_fox/2024/party"));

        state.enterPack("wine_fox/");
        filter("", path -> false);

        assertEquals(Arrays.asList("wine_fox/01_taisho_maid", "wine_fox/02_new_year"), state.models());
        assertEquals(Collections.singletonList("wine_fox/2024/"), state.packs());
    }

    @Test
    void nestedFoldersAreSynthesisedFromTheModels() {
        rebuild(Collections.singletonList("a/b/c"));

        filter("", path -> false);
        assertEquals(Collections.singletonList("a/"), state.packs());
        assertTrue(state.models().isEmpty());

        state.enterPack("a/");
        filter("", path -> false);
        assertEquals(Collections.singletonList("a/b/"), state.packs());
        assertTrue(state.models().isEmpty());

        state.enterPack("a/b/");
        filter("", path -> false);
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
        filter("", path -> false);

        assertEquals(1, state.maxPage());
        assertEquals(1, state.page());
    }

    @Test
    void hashPrefixMatchesFoldersOnly() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid"));

        filter("#wine", path -> false);

        assertTrue(state.models().isEmpty());
        assertEquals(Collections.singletonList("wine_fox/"), state.packs());
    }

    @Test
    void plainSearchFlattensAcrossFoldersAndHidesFolders() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid"));
        state.enterPack("wine_fox/");

        filter("steve", path -> false);

        assertEquals(Collections.singletonList("steve"), state.models());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void starCategoryIsFlatAndHasNoFolders() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid"));
        state.category(Category.STAR);

        filter("", "wine_fox/01_taisho_maid"::equals);

        assertEquals(Collections.singletonList("wine_fox/01_taisho_maid"), state.models());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void sameStarredModelIsHiddenWhenThePredicateSaysSo() {
        rebuild(Arrays.asList("steve", "wine_fox/01_taisho_maid"));
        state.category(Category.STAR);

        filter("", path -> path.equals("steve"));

        assertEquals(Collections.singletonList("steve"), state.models());
    }

    @Test
    void authCategoryHidesRestrictedAndUngrantedModelsAndKeepsTheOthers() {
        rebuild(Arrays.asList("steve", "wine_fox/granted", "wine_fox/locked"));
        state.category(Category.AUTH);

        // Upstream's rule is `entry.authorizationRequired() && !authorized.test(hash)`
        // (CatalogBrowserState.java:108-109); this class is handed the folded answer because it holds display paths and
        // no access information. `wine_fox/granted` is a restricted model the player holds a grant for, i.e. needAuth
        // is false for it, and `steve` is public.
        state.filter("", path -> path.equals("wine_fox/locked"), path -> false);

        assertEquals(Arrays.asList("steve", "wine_fox/granted"), state.models());
        // Every category but ALL is flat: upstream drops the folder list in the same branch
        // (CatalogBrowserState.java:125).
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void authRuleOnlyAppliesInsideTheAuthCategory() {
        rebuild(Arrays.asList("locked_model", "steve"));

        state.filter("", path -> path.equals("locked_model"), path -> false);

        // The all category still lists a locked model - upstream darkens its tile instead of hiding it - so the auth
        // rule must not leak into it.
        assertEquals(Arrays.asList("locked_model", "steve"), state.models());
    }

    @Test
    void starCategoryShowsALockedModelThatIsStarred() {
        rebuild(Arrays.asList("locked_model", "steve"));
        state.category(Category.STAR);

        state.filter("", path -> path.equals("locked_model"), "locked_model"::equals);

        // Upstream evaluates the star rule independently of the auth rule, so a locked model the player starred is
        // listed; the tile draws it dark and unclickable.
        assertEquals(Collections.singletonList("locked_model"), state.models());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void authCategoryStillAppliesTheSearchQuery() {
        rebuild(Arrays.asList("locked_model", "steve"));
        state.category(Category.AUTH);

        state.filter("steve", path -> path.equals("locked_model"), path -> false);

        assertEquals(Collections.singletonList("steve"), state.models());
    }

    @Test
    void folderThatDisappearedSendsTheViewBackToTheRoot() {
        rebuild(Collections.singletonList("gone/model"));
        state.enterPack("gone/");
        filter("", path -> false);
        assertEquals("gone/", state.currentPack());
        assertEquals(Collections.singletonList("gone/model"), state.models());

        rebuild(Collections.singletonList("other"));

        assertEquals("", state.currentPack());
        filter("", path -> false);
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

        filter("", path -> false);
        assertEquals(11, state.models().size() + state.packs().size());
        assertEquals(1, state.maxPage());

        state.page(1);
        rebuild(Collections.singletonList("model_0"));
        filter("", path -> false);

        assertEquals(0, state.maxPage());
        assertEquals(0, state.page());
    }

    @Test
    void searchKeepsTheAlphabeticalPathOrder() {
        rebuild(Arrays.asList("b_pack/z", "a_pack/y"));

        filter("_pack", path -> false);

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

        filter("", path -> false);

        assertEquals(Collections.singletonList("wine_fox/"), state.packs());
        assertEquals("酒狐与小伙伴", state.packName("wine_fox/"));
    }

    @Test
    void packWithoutAnyModelsIsStillBrowsable() {
        state.rebuild(Collections.emptyList(), names("empty_pack/", "Empty Pack"), Collections.emptyMap());

        filter("", path -> false);
        assertEquals(Collections.singletonList("empty_pack/"), state.packs());
        assertEquals("Empty Pack", state.packName("empty_pack/"));

        state.enterPack("empty_pack/");
        filter("", path -> false);
        assertTrue(state.models().isEmpty());
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void folderSearchMatchesManifestNameAndDescription() {
        state.rebuild(
            Collections.singletonList("wine_fox/01_taisho_maid"),
            names("wine_fox/", "酒狐与小伙伴"),
            names("wine_fox/", "可爱酒狐和她的小伙伴们"));

        filter("#酒狐", path -> false);
        assertEquals(Collections.singletonList("wine_fox/"), state.packs());

        filter("#可爱", path -> false);
        assertEquals(Collections.singletonList("wine_fox/"), state.packs());

        filter("#Wine Fox", path -> false);
        assertTrue(state.packs().isEmpty());
    }

    @Test
    void blankManifestNameFallsBackToTheFolderSegment() {
        state.rebuild(
            Collections.singletonList("a/b/x"),
            names("a/", "", "a/b/", "   "),
            Collections.emptyMap());

        filter("", path -> false);
        assertEquals("a", state.packName("a/"));

        state.enterPack("a/");
        filter("", path -> false);
        assertEquals("b", state.packName("a/b/"));
    }
}
