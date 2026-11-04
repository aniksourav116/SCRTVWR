package srv.scrtvwr.ui.sort

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectorySortOrderTest {
    private val detailOrders = listOf(
        DirectorySortOrder.NewestFirst,
        DirectorySortOrder.OldestFirst,
        DirectorySortOrder.LargestFirst,
        DirectorySortOrder.SmallestFirst,
    )

    @Test
    fun onServer_keepsNameOrders() {
        assertEquals(DirectorySortOrder.NameAscending, DirectorySortOrder.NameAscending.onServer(detailsAllowed = false))
        assertEquals(DirectorySortOrder.NameDescending, DirectorySortOrder.NameDescending.onServer(detailsAllowed = false))
    }

    @Test
    fun onServer_readsDateAndSizeOrdersAsNameUnlessDetailsAreAllowed() {
        detailOrders.forEach { order ->
            assertEquals(DirectorySortOrder.NameAscending, order.onServer(detailsAllowed = false))
            assertEquals(order, order.onServer(detailsAllowed = true))
        }
    }

    @Test
    fun sortOrdersFor_offersOnlyNameOrdersOnServersWithoutDetails() {
        assertEquals(
            listOf(DirectorySortOrder.NameAscending, DirectorySortOrder.NameDescending),
            sortOrdersFor(isRemote = true, detailsAllowed = false),
        )
        assertEquals(DirectorySortOrder.entries.toList(), sortOrdersFor(isRemote = true, detailsAllowed = true))
        assertEquals(DirectorySortOrder.entries.toList(), sortOrdersFor(isRemote = false, detailsAllowed = false))
    }
}
