package app.ombra.vpn

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Android Keystore part of ConfigKeyVault, which JVM tests cannot reach. */
class ConfigKeyVaultTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "config-key-vault-test")

    @Before
    fun cleanDir() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    @After
    fun removeDir() {
        dir.deleteRecursively()
    }

    @Test
    fun makesOneKeyAndGivesItBack() {
        val key = assertNotNull(ConfigKeyVault(dir).loadOrCreate())
        assertEquals(32, key.size)

        val onDisk = dir.listFiles().orEmpty().single().readBytes()
        assertFalse(onDisk.asList().windowed(key.size).any { it == key.asList() }, "the key is on disk in clear")
        assertContentEquals(key, ConfigKeyVault(dir).loadOrCreate())
    }

    @Test
    fun damagedFileGivesANewKey() {
        val key = assertNotNull(ConfigKeyVault(dir).loadOrCreate())
        val file = dir.listFiles().orEmpty().single()
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })

        // The old key can never open again, so Rust resets the config with the new one.
        assertTrue(ConfigKeyVault(dir).read() is KeystoreVault.Read.Gone)
        val next = assertNotNull(ConfigKeyVault(dir).loadOrCreate())
        assertFalse(key.contentEquals(next))
        assertContentEquals(next, ConfigKeyVault(dir).loadOrCreate())
    }
}
