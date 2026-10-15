package com.fox.ysmu.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The path arithmetic the folder-aware model selection GUI is built on. */
class ModelPathUtilTest {

    @Test
    void parentHierarchyKeepsTheTrailingSlash() {
        assertEquals("", ModelPathUtil.parentHierarchy("steve"));
        assertEquals("wine_fox/", ModelPathUtil.parentHierarchy("wine_fox/01_taisho_maid"));
        assertEquals("a/b/", ModelPathUtil.parentHierarchy("a/b/c"));
        assertEquals("", ModelPathUtil.parentHierarchy(""));
        assertEquals("", ModelPathUtil.parentHierarchy(null));
    }

    @Test
    void fileNameDropsTheFolderPart() {
        assertEquals("steve", ModelPathUtil.fileName("steve"));
        assertEquals("01_taisho_maid", ModelPathUtil.fileName("wine_fox/01_taisho_maid"));
        assertEquals("c", ModelPathUtil.fileName("a/b/c"));
        assertEquals("", ModelPathUtil.fileName(""));
        assertEquals("", ModelPathUtil.fileName(null));
    }

    @Test
    void lastFolderNameLabelsAFolderTile() {
        assertEquals("wine_fox", ModelPathUtil.lastFolderName("wine_fox/"));
        assertEquals("2024", ModelPathUtil.lastFolderName("wine_fox/2024/"));
        assertEquals("wine_fox", ModelPathUtil.lastFolderName("wine_fox"));
        assertEquals("", ModelPathUtil.lastFolderName(""));
        assertEquals("", ModelPathUtil.lastFolderName(null));
    }

    @Test
    void isDirectChildSeparatesChildrenFromGrandchildren() {
        assertTrue(ModelPathUtil.isDirectChild("", "wine_fox/"));
        assertFalse(ModelPathUtil.isDirectChild("", "wine_fox/2024/"));
        assertTrue(ModelPathUtil.isDirectChild("wine_fox/", "wine_fox/2024/"));
        assertFalse(ModelPathUtil.isDirectChild("wine_fox/", "wine_fox/2024/spring/"));
        // A folder is never its own child, and an unrelated prefix must not match.
        assertFalse(ModelPathUtil.isDirectChild("wine_fox/", "wine_fox/"));
        assertFalse(ModelPathUtil.isDirectChild("wine_fox/", "wine_fox_2/"));
    }
}
