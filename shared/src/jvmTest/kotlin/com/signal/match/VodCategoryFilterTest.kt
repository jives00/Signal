package com.signal.match

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VodCategoryFilterTest {
    private val filter = VodCategoryFilter(showOnly = listOf("EN - *", "ENGLISH *", "NETFLIX *", "SKY"), hide = listOf("*ASIA*", "ENGLISH SERIES (TR)"))

    @Test fun showOnlyKeepsMatchesAndHidesTheRest() {
        assertFalse(filter.hides("EN - ACTION"))
        assertFalse(filter.hides("en - kids"), "ignores case")
        assertFalse(filter.hides("NETFLIX MOVIES 4K 3840P"))
        assertFalse(filter.hides("SKY"))
        assertTrue(filter.hides("SKY SPORTS"), "a pattern without * matches the whole name")
        assertTrue(filter.hides("ES - PELÍCULAS"))
        assertTrue(filter.hides("أفلام عربية"))
        assertTrue(filter.hides("FRANCE SÉRIES"), "a category added later hides unless it looks like one to keep")
    }

    @Test fun hideWinsOverShowOnly() {
        assertTrue(filter.hides("NETFLIX ASIA"))
        assertTrue(filter.hides("ENGLISH SERIES (TR)"), "brackets are plain text")
        assertFalse(filter.hides("ENGLISH SERIES"))
    }

    @Test fun hideAloneKeepsEverythingElse() {
        val f = VodCategoryFilter(hide = listOf("FOR ADULTS", "?? - *"))
        assertTrue(f.hides("FOR ADULTS"))
        assertTrue(f.hides("ES - PELÍCULAS"), "? is one character")
        assertFalse(f.hides("PT/BR - FILMES"))
        assertFalse(f.hides("NETFLIX MOVIES"))
    }

    @Test fun anUnreadableSettingKeepsEverything() {
        assertEquals(VodCategoryFilter.NONE, VodCategoryFilter.parse("not json"))
        assertEquals(VodCategoryFilter.NONE, VodCategoryFilter.parse(null))
        assertFalse(VodCategoryFilter.NONE.hides("anything"))
        assertEquals(filter, VodCategoryFilter.parse("""{"show_only":["EN - *","ENGLISH *","NETFLIX *","SKY"],"hide":["*ASIA*","ENGLISH SERIES (TR)"]}"""))
    }

    @Test fun oneCategoryHiddenOrShownWinsOverThePatterns() {
        val shown = filter.withHidden("ES - PELÍCULAS", hide = false)
        assertFalse(shown.hides("es - películas"), "shown by name, ignoring case")
        assertTrue(shown.hides("FR - FILMS"))
        val hidden = shown.withHidden("EN - ACTION", hide = true).withHidden("ES - PELÍCULAS", hide = true)
        assertTrue(hidden.hides("EN - ACTION"))
        assertTrue(hidden.hides("ES - PELÍCULAS"))
        assertEquals(emptyList(), hidden.withHidden("EN - ACTION", hide = false).hidden.filter { it == "EN - ACTION" }, "unhiding takes it off the list")
        assertFalse(hidden.withHidden("EN - ACTION", hide = false).hides("EN - ACTION"))
        assertEquals(filter, filter.withHidden("EN - ACTION", hide = false), "already shown: nothing to add")
        assertEquals(hidden, VodCategoryFilter.parse(hidden.toJson()))
    }

    @Test fun renamesAreByNameAndDontChangeVisibility() {
        val f = filter.withName("EN - ACTION", "Action").withName("SKY", "  ")
        assertEquals("Action", f.renamed("en - action"))
        assertEquals(null, f.renamed("SKY"))
        assertEquals(filter, f.visibility)
        assertEquals(emptyMap(), f.withName("EN - ACTION", null).names)
        assertEquals(f, VodCategoryFilter.parse(f.toJson()))
    }
}
