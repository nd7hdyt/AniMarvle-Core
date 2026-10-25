package com.animarvle.app.updater

import com.animarvle.app.data.updater.AppUpdateRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryTest {

    @Test
    fun isNewerVersion_higherVersionCode_returnsTrue() {
        val result = AppUpdateRepository.isNewerVersion(
            remoteVersionCode = 3,
            remoteVersionName = "0.1.2",
            currentVersionCode = 2,
            currentVersionName = "0.1.1"
        )
        assertTrue("Higher remote versionCode must trigger an update", result)
    }

    @Test
    fun isNewerVersion_equalVersionCode_returnsFalse() {
        val result = AppUpdateRepository.isNewerVersion(
            remoteVersionCode = 2,
            remoteVersionName = "0.1.1",
            currentVersionCode = 2,
            currentVersionName = "0.1.1"
        )
        assertFalse("Identical versionCode must not trigger an update", result)
    }

    @Test
    fun isNewerVersion_lowerVersionCode_returnsFalse() {
        val result = AppUpdateRepository.isNewerVersion(
            remoteVersionCode = 1,
            remoteVersionName = "0.1.0",
            currentVersionCode = 2,
            currentVersionName = "0.1.1"
        )
        assertFalse("Lower versionCode must not trigger an update", result)
    }

    @Test
    fun isNewerVersion_noRemoteCode_newerVersionName_returnsTrue() {
        val result = AppUpdateRepository.isNewerVersion(
            remoteVersionCode = 0,
            remoteVersionName = "v0.1.2",
            currentVersionCode = 2,
            currentVersionName = "0.1.1"
        )
        assertTrue("Different versionName fallback should trigger update when code is 0", result)
    }

    @Test
    fun isNewerVersion_noRemoteCode_sameVersionName_returnsFalse() {
        val result = AppUpdateRepository.isNewerVersion(
            remoteVersionCode = 0,
            remoteVersionName = "v0.1.1",
            currentVersionCode = 2,
            currentVersionName = "0.1.1"
        )
        assertFalse("Matching versionName with v prefix should not trigger update", result)
    }
}
