package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.PortForward
import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.TabbyGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private fun profile(id: String, host: String, origin: String) = SshProfile(
        id = id,
        name = id,
        host = host,
        origin = origin,
    )

    @Test fun failedAccountKeepsCachedSlice() {
        val existing = listOf(
            profile("good", "good.example.com", "tabby:good:1"),
            profile("bad", "bad.example.com", "tabby:bad:1"),
        )
        val merged = SyncMerge.merge(
            existingProfiles = existing,
            existingGroups = emptyList(),
            pulls = listOf(
                SyncMerge.AccountPull(
                    accountId = "good",
                    ok = true,
                    profiles = listOf(profile("new-good", "new.example.com", "tabby:good:1")),
                ),
                SyncMerge.AccountPull("bad", ok = false),
            ),
        )

        assertEquals(setOf("bad", "new-good"), merged.profiles.map { it.id }.toSet())
    }

    @Test fun allAccountsFailedPreservesEntireCache() {
        val existing = profile("locked", "locked.example.com", "tabby:locked:1")
        val merged = SyncMerge.merge(
            existingProfiles = listOf(existing),
            existingGroups = emptyList(),
            pulls = listOf(SyncMerge.AccountPull("locked", ok = false)),
        )

        assertEquals(listOf(existing), merged.profiles)
    }

    @Test fun successfulPullPreservesDeviceOnlyFields() {
        val local = profile("ssh:custom:one:1", "old.example.com", "tabby:acc:1").copy(
            keyId = "key:local",
            forwards = listOf(PortForward(localPort = 8080, remoteHost = "db", remotePort = 5432)),
            groupName = "Local Folder",
        )
        val remote = profile("ssh:custom:one:1", "new.example.com", "tabby:acc:1").copy(
            name = "Remote Name",
            port = 2200,
            username = "ops",
            authType = "publicKey",
        )

        val merged = SyncMerge.merge(
            existingProfiles = listOf(local),
            existingGroups = emptyList(),
            pulls = listOf(SyncMerge.AccountPull("acc", ok = true, profiles = listOf(remote))),
        )

        val p = merged.profiles.single { it.id == remote.id }
        assertEquals("new.example.com", p.host)
        assertEquals("Remote Name", p.name)
        assertEquals(2200, p.port)
        assertEquals("ops", p.username)
        assertEquals("key:local", p.keyId)
        assertEquals("Local Folder", p.groupName)
        assertEquals(1, p.forwards.size)
        assertEquals(8080, p.forwards.first().localPort)
    }

    @Test fun successfulPullDropsDeletedProfilesAndUnknownCacheIsKept() {
        val merged = SyncMerge.merge(
            existingProfiles = listOf(
                profile("gone", "gone.example.com", "tabby:acc:1"),
                profile("orphan", "orphan.example.com", "tabby:missing:1"),
            ),
            existingGroups = emptyList(),
            pulls = listOf(
                SyncMerge.AccountPull(
                    accountId = "acc",
                    ok = true,
                    profiles = listOf(profile("fresh", "fresh.example.com", "tabby:acc:1")),
                ),
            ),
        )

        assertEquals(setOf("orphan", "fresh"), merged.profiles.map { it.id }.toSet())
    }

    @Test fun groupsFollowAccountSliceRules() {
        val existingGroups = listOf(
            TabbyGroup("good-empty", "Good Empty", ownerAccountId = "good"),
            TabbyGroup("bad", "Bad Folder", ownerAccountId = "bad"),
        )
        val merged = SyncMerge.merge(
            existingProfiles = listOf(profile("bad-host", "bad.example.com", "tabby:bad:1")),
            existingGroups = existingGroups,
            pulls = listOf(
                SyncMerge.AccountPull(
                    accountId = "good",
                    ok = true,
                    profiles = listOf(profile("new-good", "new.example.com", "tabby:good:1")),
                    groups = listOf(TabbyGroup("new-good", "New Good")),
                ),
                SyncMerge.AccountPull("bad", ok = false),
            ),
        )

        assertEquals(setOf("bad", "new-good"), merged.groups.map { it.id }.toSet())
        assertEquals("Bad Folder", merged.groups.first { it.id == "bad" }.name)
        assertEquals("good", merged.groups.first { it.id == "new-good" }.ownerAccountId)
        assertTrue(merged.groups.none { it.id == "good-empty" })
    }

    @Test fun legacyUnownedGroupsReferencedByKeptProfilesSurvive() {
        val merged = SyncMerge.merge(
            existingProfiles = listOf(profile("legacy", "legacy.example.com", "tabby:bad:1").copy(group = "g")),
            existingGroups = listOf(TabbyGroup("g", "Legacy Group")),
            pulls = listOf(
                SyncMerge.AccountPull(
                    accountId = "other",
                    ok = true,
                    profiles = listOf(profile("fresh", "fresh.example.com", "tabby:other:1")),
                    groups = listOf(TabbyGroup("h", "Fresh Group")),
                ),
            ),
        )

        assertEquals(setOf("g", "h"), merged.groups.map { it.id }.toSet())
    }
}
