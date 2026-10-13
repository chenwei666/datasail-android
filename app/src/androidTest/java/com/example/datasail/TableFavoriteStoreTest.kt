package com.example.datasail

import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.DatabaseKind
import com.example.datasail.core.TableRef
import org.junit.Assert.*
import org.junit.Test

class TableFavoriteStoreTest {
    @Test fun persistsExactTableIdentityAndRejectsChangedEndpoint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = TableFavoriteStore(context)
        val original = store.load()
        val connection = SavedConnection(name = "合成收藏", host = "localhost", username = "fixture", database = "one")
        val key = tableConnectionKey(connection)
        val table = TableRef("one", "dbo", "same_name", 7, false)
        val favorite = TableFavorite(key, connection.name, DatabaseKind.SQL_SERVER, table.catalog, table.schema, table.name, false)
        try {
            store.save(listOf(favorite))
            assertEquals(listOf(favorite), TableFavoriteStore(context).load())
            assertTrue(favorite.matches(key, table))
            assertFalse(favorite.matches(key, table.copy(catalog = "two")))
            assertFalse(favorite.matches(key, table.copy(schema = "other")))
            assertFalse(favorite.matches(key, table.copy(view = true)))
            assertNotEquals(key, tableConnectionKey(connection.copy(host = "other")))
            assertNotEquals(key, tableConnectionKey(connection.copy(username = "other")))
            assertNotEquals(key, tableConnectionKey(connection.copy(id = "copy")))
            assertEquals(key, tableConnectionKey(connection.copy(database = "two")))
            store.save(emptyList()); assertTrue(store.load().isEmpty())
        } finally { store.save(original) }
    }
}
