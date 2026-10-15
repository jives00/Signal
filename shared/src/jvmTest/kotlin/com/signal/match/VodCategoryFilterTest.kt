package com.signal.match

import com.signal.model.GroupSort
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

    @Test fun theColumnSortsByTitleOrByTheManualOrder() {
        val names = listOf("EN - DRAMA", "EN - ACTION", "SKY", "EN - COMEDY")
        val titles = mapOf("EN - DRAMA" to "Drama", "EN - ACTION" to "Action", "SKY" to "Sky", "EN - COMEDY" to "Comedy")
        fun VodCategoryFilter.column(kind: String = "MOVIE") = sorted(kind, names, { it }, { titles.getValue(it) })
        assertEquals(names, filter.column(), "provider order by default")
        assertEquals(listOf("EN - ACTION", "EN - COMEDY", "EN - DRAMA", "SKY"), filter.withSort(GroupSort.A_Z).column(), "A–Z goes by the name shown")
        assertEquals(listOf("SKY", "EN - DRAMA", "EN - COMEDY", "EN - ACTION"), filter.withSort(GroupSort.Z_A).column())

        val moved = filter.withSort(GroupSort.A_Z).withMove("MOVIE", filter.withSort(GroupSort.A_Z).column(), "sky", 0)
        assertEquals(GroupSort.MANUAL, moved.categorySort)
        assertEquals(listOf("SKY", "EN - ACTION", "EN - COMEDY", "EN - DRAMA"), moved.column(), "seeded from what was shown")
        assertEquals(names, moved.column("SERIES"), "each kind has its own order")
        assertEquals(
            listOf("SKY", "EN - NEW", "EN - LATER"),
            moved.copy(order = mapOf("MOVIE" to listOf("sky"))).sorted("MOVIE", listOf("EN - NEW", "SKY", "EN - LATER"), { it }, { it }),
            "unordered ones (a new provider category) go after, in provider order",
        )

        val provider = moved.withSort(GroupSort.PROVIDER)
        assertEquals(null, provider.sort)
        assertEquals(moved.order, provider.order, "the manual order is kept for later")
        assertEquals(filter, moved.visibility, "an order alone doesn't change which titles are kept")
        assertEquals(moved, VodCategoryFilter.parse(moved.toJson()))
    }
}
