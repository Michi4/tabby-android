package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.TabbyGroup

/**
 * Pure cache merge for multi-account sync.
 *
 * A sync result is account-scoped, but [ProfileRepository] stores one flat
 * cached-profile list. Replacing that whole list from only the accounts that
 * happened to succeed silently deletes device-only fields (`keyId`, port
 * forwards) and hosts from failed/locked accounts. This merge keeps every
 * account slice intact unless that account returned a fresh remote list.
 */
object SyncMerge {
    data class AccountPull(
        val accountId: String,
        val ok: Boolean,
        val profiles: List<SshProfile> = emptyList(),
        val groups: List<TabbyGroup> = emptyList(),
    )

    data class MergedState(
        val profiles: List<SshProfile>,
        val groups: List<TabbyGroup>,
    )

    fun merge(
        existingProfiles: List<SshProfile>,
        existingGroups: List<TabbyGroup>,
        pulls: List<AccountPull>,
    ): MergedState {
        val successfulOwners = pulls.filter { it.ok }.map { it.accountId }.toSet()
        val localById = existingProfiles.associateBy { it.id }

        val profiles = LinkedHashMap<String, SshProfile>()
        for (p in existingProfiles) {
            val owner = TabbyYamlSerializer.accountIdFromOrigin(p.origin)
            if (owner == null || owner !in successfulOwners) {
                profiles[p.id] = p
            }
        }
        for (pull in pulls.filter { it.ok }) {
            for (remote in pull.profiles) {
                profiles[remote.id] = preserveDeviceFields(remote, localById[remote.id])
            }
        }

        val referencedGroups = profiles.values.mapNotNull { it.group }.toSet()
        val groups = LinkedHashMap<String, TabbyGroup>()
        for (g in existingGroups) {
            val owner = g.ownerAccountId
            val preserveOwned = owner != null && owner !in successfulOwners
            val preserveReferenced = owner == null && g.id in referencedGroups
            if (preserveOwned || preserveReferenced) groups[g.id] = g
        }
        for (pull in pulls.filter { it.ok }) {
            for (g in pull.groups) {
                groups[g.id] = g.copy(ownerAccountId = pull.accountId)
            }
        }

        return MergedState(profiles.values.toList(), groups.values.toList())
    }

    private fun preserveDeviceFields(remote: SshProfile, local: SshProfile?): SshProfile {
        if (local == null) return remote
        return remote.copy(
            keyId = local.keyId,
            groupName = remote.groupName ?: local.groupName,
            forwards = local.forwards.ifEmpty { remote.forwards },
        )
    }
}
