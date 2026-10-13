package com.example.datasail

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.datasail.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SqliteDeviceTest {
    @Test fun importedCopyBrowsesQueriesPreservesTypesAndNeverModifiesSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "sqlite-fixture.db")
        source.delete()
        try {
            SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                db.execSQL("CREATE TABLE records (id INTEGER PRIMARY KEY, code TEXT, optional TEXT, payload BLOB)")
                db.execSQL("CREATE TABLE wide (id INTEGER PRIMARY KEY," + (1..129).joinToString(",") { "c$it TEXT" } + ")")
                db.execSQL("INSERT INTO wide(id,c129) VALUES (1,'last-column-0007')")
                db.beginTransaction()
                try { for (i in 1..105) db.execSQL("INSERT INTO records VALUES (?, ?, NULL, ?)", arrayOf(i, "0007 中文 🚢", byteArrayOf(0, 1, -1)))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            val before = source.readBytes()
            val store = ConnectionStore(context)
            val id = store.importSqlite(Uri.fromFile(source))
            val imported = store.sqliteFile(id)
            try {
                assertNotEquals(source.path, imported.path)
                val profile = SavedConnection(kind = DatabaseKind.SQLITE, database = id).profile(context)
                SqliteBrowseSession(imported, profile).use { session ->
                    val table = session.tables("main").single { it.name == "records" }
                    val page = session.page(table)
                    val wide = session.tables("main").single { it.name == "wide" }
                    val last = session.page(wide, 0, 128)
                    assertEquals(130, last.totalColumns); assertEquals(128, last.columnOffset)
                    assertEquals(listOf("c128", "c129"), last.columns.map { it.name })
                    assertEquals(DbValue.Text("last-column-0007"), last.rows.single()[1])
                    assertEquals(100, page.rows.size); assertTrue(page.hasMore)
                    assertEquals(DbValue.Integer(1), page.rows[0][0]); assertEquals(DbValue.Text("0007 中文 🚢"), page.rows[0][1])
                    assertEquals(DbValue.Null, page.rows[0][2]); assertArrayEquals(byteArrayOf(0, 1, -1), (page.rows[0][3] as DbValue.Binary).copyBytes())
                    assertEquals(5, session.page(table, 100).rows.size)
                    assertThrows(WorkspaceRejected::class.java) { session.select("DELETE FROM records") }
                    assertThrows(WorkspaceRejected::class.java) { session.setWritable(true) }
                    assertEquals(DbValue.Integer(105), session.select("SELECT COUNT(*) FROM records").rows.single().single())
                }
                assertArrayEquals(before, source.readBytes())
                assertArrayEquals(before, imported.readBytes())
            } finally { imported.delete() }
        } finally { source.delete() }
    }
    @Test fun rejectsWalAndInvalidCopiesWithoutSavingThem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "invalid-sqlite.db")
        try {
            file.writeBytes(ByteArray(100))
            val store = ConnectionStore(context)
            assertThrows(Exception::class.java) { store.importSqlite(Uri.fromFile(file)) }
            val header = ByteArray(100); "SQLite format 3\u0000".toByteArray().copyInto(header); header[18] = 2; header[19] = 2
            file.writeBytes(header)
            assertThrows(Exception::class.java) { store.importSqlite(Uri.fromFile(file)) }
            assertFalse(File(context.filesDir, "sqlite").listFiles().orEmpty().any { it.name.startsWith("import-") })
        } finally { file.delete() }
    }
}
