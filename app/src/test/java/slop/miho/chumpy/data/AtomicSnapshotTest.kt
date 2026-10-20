package slop.miho.chumpy.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors

class AtomicSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `concurrent readers see complete snapshots and successful writes leave no temporary files`() {
        val file = File(temporary.root, "snapshot.json")
        val first = "first-Русский".repeat(4096)
        val second = "second-English".repeat(4096)
        AtomicSnapshot.write(file, first)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer = executor.submit {
                repeat(20) { AtomicSnapshot.write(file, if (it % 2 == 0) second else first) }
            }
            repeat(100) {
                val snapshot = AtomicSnapshot.read(file)
                assertTrue(snapshot == first || snapshot == second)
            }
            writer.get()
            assertEquals(first, AtomicSnapshot.read(file))
            assertEquals(listOf(file.name), temporary.root.listFiles()!!.map { it.name })
        } finally {
            executor.shutdownNow()
        }
    }
}
