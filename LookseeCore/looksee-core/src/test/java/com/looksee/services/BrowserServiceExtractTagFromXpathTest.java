package com.looksee.services;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Pins the xpath shapes that {@link BrowserService#extractTagFromXpath} must
 * handle correctly. These are the shapes Look-see's own xpath generators
 * (extractAllUniqueElementXpaths, generateXpath) emit and the shapes stored
 * in {@code ElementState.getXpath()}. Phase 3d depends on this parity for
 * remote-mode behavior to match local-mode WebElement.getTagName().
 */
class BrowserServiceExtractTagFromXpathTest {

    private static String extract(String xpath) {
        return BrowserService.extractTagFromXpath(xpath);
    }

    @Test void simpleTag()              { assertEquals("div",  extract("//div")); }
    @Test void indexedTag()             { assertEquals("li",   extract("//body/header/nav/ul/li[3]")); }
    @Test void attributePredicate()     { assertEquals("a",    extract("//a[@id='signin']")); }
    @Test void multipleAttrPredicate()  { assertEquals("input", extract("//input[@type='text'][@name='q']")); }
    @Test void namespacedTag()          { assertEquals("rect", extract("//svg:rect[1]")); }
    @Test void nestedAbsolutePath()     { assertEquals("span", extract("/html/body/div[1]/p/span")); }
    @Test void rootedTag()              { assertEquals("html", extract("/html")); }
    @Test void unprefixedTag()          { assertEquals("body", extract("body")); }
    @Test void parenthesizedIndexedTag(){ assertEquals("form", extract("(//form)[1]")); }
    @Test void parenthesizedPredicateTag() {
        assertEquals("div", extract("(//div[@id='x'])[2]"));
    }
    @Test void emptyXpath_returnsEmpty(){ assertEquals("",     extract("")); }
    @Test void nullXpath_returnsEmpty() { assertEquals("",     extract(null)); }

    // --- Quoted-slash regressions (PR #46 review) ------------------------
    // A naive lastIndexOf('/') misclassifies tags when predicates contain
    // quoted slashes. The implementation must respect bracket + quote state.

    @Test void singleQuotedSlashInPredicate() {
        assertEquals("a", extract("//a[contains(@title,'foo/bar')]"));
    }

    @Test void doubleQuotedSlashInPredicate() {
        assertEquals("div", extract("//div[@onclick=\"go('foo/bar')\"]"));
    }

    @Test void slashInHrefPredicateOnLastSegment() {
        assertEquals("a", extract("//html/body/a[contains(@href,'/path/to/page')]"));
    }

    @Test void nestedBracketsWithSlash() {
        assertEquals("input", extract("//input[@data-x=\"a[b/c]d\"]"));
    }

    @Test void slashInsideMidPathPredicate() {
        // Slash inside a predicate on a non-tail segment must not affect the tail.
        assertEquals("span", extract("//div[contains(@class,'x/y')]/span"));
    }
}
