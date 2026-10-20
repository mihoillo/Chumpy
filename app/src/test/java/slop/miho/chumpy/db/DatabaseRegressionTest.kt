package slop.miho.chumpy.db

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import slop.miho.chumpy.sub.SubscriptionRepository
import slop.miho.chumpy.sub.LinkUtils

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DatabaseRegressionTest {
    private lateinit var db: ChumpyDatabase

    @Before
    fun createDatabase() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChumpyDatabase::class.java)
            .allowMainThreadQueries().build()
        db.subscriptionDao().upsert(SubscriptionEntity(id = "sub", name = "Original"))
        db.profileDao().replaceProfiles("sub", listOf(
            ProfileEntity(id = "profile", subscriptionId = "sub", type = ProfileType.VLESS,
                server = "example.com", serverPort = 443, password = "test", testDelayMillis = 42),
        ))
    }

    @After
    fun closeDatabase() = db.close()

    @Test
    fun `updating subscription metadata preserves child profiles`() = runBlocking {
        db.subscriptionDao().upsert(SubscriptionEntity(id = "sub", name = "Renamed"))
        assertEquals(42L, db.profileDao().byId("profile")!!.testDelayMillis)
        assertEquals("Renamed", db.subscriptionDao().byId("sub")!!.name)
    }

    @Test
    fun `auto update and fetch errors do not delete profiles or recreate deleted subscriptions`() = runBlocking {
        db.subscriptionDao().setAutoUpdate("sub", false)
        db.subscriptionDao().setUpdateError("sub", 123, "HTTP 500")
        assertNotNull(db.profileDao().byId("profile"))
        assertFalse(db.subscriptionDao().byId("sub")!!.autoUpdate)
        assertEquals("HTTP 500", db.subscriptionDao().byId("sub")!!.lastError)
        db.subscriptionDao().delete("sub")
        db.subscriptionDao().setUpdateError("sub", 124, "late response")
        assertNull(db.subscriptionDao().byId("sub"))
        assertNull(db.profileDao().byId("profile"))
    }

    @Test
    fun `failed refresh transaction retains previous metadata and profiles`() = runBlocking {
        try {
            db.withTransaction {
                db.subscriptionDao().upsert(SubscriptionEntity(id = "sub", name = "Uncommitted"))
                db.profileDao().replaceProfiles("sub", emptyList())
                error("interrupted refresh")
            }
        } catch (_: IllegalStateException) { }
        assertEquals("Original", db.subscriptionDao().byId("sub")!!.name)
        assertNotNull(db.profileDao().byId("profile"))
    }

    @Test
    fun `manual base64 imports deduplicate within a batch and append in order`() = runBlocking {
        val repository = SubscriptionRepository(RuntimeEnvironment.getApplication(), db, { false }, { null })
        val first = "hy2://test@one.example.com:443"
        val second = "hy2://test@two.example.com:443"
        assertEquals(1, repository.addManualProfiles(LinkUtils.encodeBase64("$first\n$first")).getOrThrow())
        assertEquals(1, repository.addManualProfiles(second).getOrThrow())
        val profiles = db.profileDao().bySubscriptionOnce(null)
        assertEquals(listOf("one.example.com", "two.example.com"), profiles.map { it.server })
        assertEquals(listOf(0L, 1L), profiles.map { it.order })
    }

    @Test
    fun `deleting a balancer removes unused internals but retains shared members`() = runBlocking {
        val repository = SubscriptionRepository(RuntimeEnvironment.getApplication(), db, { false }, { null })
        val member = ProfileEntity(id = "member", subscriptionId = null, type = ProfileType.SOCKS,
            hidden = true, linkKey = "shared")
        val unused = member.copy(id = "unused", linkKey = "unused")
        val first = ProfileEntity(id = "first", subscriptionId = null, type = ProfileType.BALANCER,
            linkKey = "first", balancerMembers = "shared\nunused")
        val second = first.copy(id = "second", linkKey = "second", balancerMembers = "shared")
        db.profileDao().replaceProfiles(null, listOf(member, unused, first, second))
        assertEquals(2, repository.manualProfileCount.first())
        repository.deleteManualProfile("first")
        assertEquals(setOf("member", "second"), db.profileDao().bySubscriptionOnce(null).map { it.id }.toSet())
        repository.deleteManualProfile("second")
        assertTrue(db.profileDao().bySubscriptionOnce(null).isEmpty())
        assertEquals(0, repository.manualProfileCount.first())
    }

    @Test
    fun `deleting a visible server retains the hidden endpoint needed by its balancer`() = runBlocking {
        val repository = SubscriptionRepository(RuntimeEnvironment.getApplication(), db, { false }, { null })
        val server = ProfileEntity(id = "member", subscriptionId = null, type = ProfileType.SOCKS, linkKey = "key")
        val group = ProfileEntity(id = "group", subscriptionId = null, type = ProfileType.BALANCER,
            balancerMembers = "key")
        db.profileDao().replaceProfiles(null, listOf(server, group))
        repository.deleteManualProfile("member")
        assertTrue(db.profileDao().byId("member")!!.hidden)
        assertEquals(1, repository.manualProfileCount.first())
    }

    @Test
    fun `importing a balancer member separately makes the existing server visible`() = runBlocking {
        val repository = SubscriptionRepository(RuntimeEnvironment.getApplication(), db, { false }, { null })
        val link = "hy2://test@one.example.com:443"
        repository.addManualProfiles(link).getOrThrow()
        val server = db.profileDao().bySubscriptionOnce(null).single()
        db.profileDao().replaceProfiles(null, listOf(server.copy(hidden = true)))
        assertEquals(0, repository.manualProfileCount.first())
        assertEquals(1, repository.addManualProfiles(link).getOrThrow())
        assertEquals(server.id, db.profileDao().bySubscriptionOnce(null).single().id)
        assertFalse(db.profileDao().byId(server.id)!!.hidden)
        assertEquals(1, repository.manualProfileCount.first())
    }

    @Test
    fun `legacy column migration preserves subscriptions manual profiles and cascade relationships`() = runBlocking {
        db.profileDao().replaceProfiles(null, listOf(
            ProfileEntity(id = "manual", subscriptionId = null, type = ProfileType.SOCKS),
        ))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("ALTER TABLE subscriptions ADD COLUMN happSpoof INTEGER NOT NULL DEFAULT 0")
        sql.execSQL("ALTER TABLE subscriptions ADD COLUMN happHwid TEXT NOT NULL DEFAULT ''")
        sql.beginTransaction()
        try {
            ChumpyDatabase.MIGRATION_2_3.migrate(sql)
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        assertEquals("Original", db.subscriptionDao().byId("sub")!!.name)
        assertNotNull(db.profileDao().byId("profile"))
        assertNotNull(db.profileDao().byId("manual"))
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        sql.query("PRAGMA table_info(subscriptions)").use { cursor ->
            while (cursor.moveToNext()) assertFalse(cursor.getString(1).startsWith("happ"))
        }
        db.subscriptionDao().delete("sub")
        assertNull(db.profileDao().byId("profile"))
        assertNotNull(db.profileDao().byId("manual"))
    }
}
