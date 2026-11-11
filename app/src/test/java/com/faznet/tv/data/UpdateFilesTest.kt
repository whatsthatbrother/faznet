package com.faznet.tv.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Android's installer may read a downloaded update through the FileProvider, and nothing else in the cache. */
@RunWith(RobolectricTestRunner::class)
class UpdateFilesTest {
    private val c: Context = ApplicationProvider.getApplicationContext()
    /** FileProvider compares paths with "/", as on Android: on a Windows PC it finds nothing, so this runs in CI (Linux). */
    @Before
    fun onlyWithAndroidPaths() = assumeTrue(File.separatorChar == '/')

    private fun uri(f: File) = FileProvider.getUriForFile(c, c.packageName + ".files", f)

    @Test
    fun `a downloaded update can be handed to the installer`() {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        assertEquals("/updates/update-62.apk", uri(File(dir, "update-62.apk")).path)
    }

    @Test
    fun `the rest of the cache folder is not shared`() {
        assertThrows(IllegalArgumentException::class.java) { uri(File(c.cacheDir, "logos/journal")) }
        assertThrows(IllegalArgumentException::class.java) { uri(File(c.cacheDir, "update-61.apk")) }
        assertThrows(IllegalArgumentException::class.java) { uri(File(c.filesDir, "playlists/a.m3u")) }
    }
}
