package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.TabbyGroup

/**
 * Folder tree built from flat profiles + groups (Termius-style nesting via
 * `parentGroupId`). Pure logic, unit-tested. Cycles and orphan parents are
 * handled gracefully (orphans become roots, cycles are cut).
 */
data class FolderNode(
    /** Stable key: group id, or `v:<name>` for virtual folders. */
    val key: String,
    val groupId: String?,
    val name: String,
    val depth: Int,
    val children: List<FolderNode>,
    val ownProfiles: List<SshProfile>,
) {
    val totalProfiles: Int get() = ownProfiles.size + children.sumOf { it.totalProfiles }
}

data class Forest(val roots: List<FolderNode>, val ungrouped: List<SshProfile>)

/** Blank or literal-"null" group ids/names are missing data, never folders. */
private fun normGroup(value: String?): String? =
    value?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

fun buildForest(profiles: List<SshProfile>, groups: List<TabbyGroup>): Forest {
    val known = groups.associateBy { it.id }
    val byName = Comparator<FolderNode> { a, b -> a.name.lowercase().compareTo(b.name.lowercase()) }

    val profilesByGroup: Map<String, List<SshProfile>> = profiles
        .mapNotNull { p -> normGroup(p.group)?.let { it to p } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, v) -> v.sortedBy { it.name.lowercase() } }

    // children per parent (only when the parent is known, else the group is a root)
    val childIds = mutableMapOf<String, MutableList<String>>()
    val rootGroups = mutableListOf<TabbyGroup>()
    for (g in groups.sortedBy { it.name.lowercase() }) {
        val parent = g.parentId
        if (parent != null && parent in known) {
            childIds.getOrPut(parent) { mutableListOf() }.add(g.id)
        } else {
            rootGroups.add(g)
        }
    }

    fun build(id: String, depth: Int, visited: Set<String>, reached: MutableSet<String>): FolderNode? {
        if (id in visited) return null // cycle guard
        val g = known[id] ?: return null
        reached.add(id)
        val next = visited + id
        val kids = childIds[id].orEmpty().mapNotNull { build(it, depth + 1, next, reached) }
            .sortedWith(byName)
        return FolderNode(
            key = id,
            groupId = id,
            name = g.name,
            depth = depth,
            children = kids,
            ownProfiles = profilesByGroup[id].orEmpty(),
        )
    }

    val reached = mutableSetOf<String>()
    val realRoots = rootGroups.mapNotNull { build(it.id, 0, emptySet(), reached) }.sortedWith(byName)
    // groups unreachable from any root (e.g. parent cycles) are re-rooted so no host vanishes
    val reRooted = groups
        .filter { it.id !in reached }
        .sortedBy { it.name.lowercase() }
        .mapNotNull { build(it.id, 0, emptySet(), reached) }
        .sortedWith(byName)

    // profiles pointing at unknown group ids -> virtual folders by display name
    val virtuals = profiles
        .mapNotNull { p -> normGroup(p.group)?.let { gid -> Triple(gid, p, normGroup(p.groupName)) } }
        .filter { (gid, _, _) -> gid !in known }
        .groupBy({ (gid, _, name) -> name ?: gid }, { (_, p, _) -> p })
        .toList()
        .sortedBy { (name, _) -> name.lowercase() }
        .map { (name, members) ->
            FolderNode(
                key = "v:$name",
                groupId = null,
                name = name,
                depth = 0,
                children = emptyList(),
                ownProfiles = members.sortedBy { it.name.lowercase() },
            )
        }

    val ungrouped = profiles
        .filter { normGroup(it.group) == null }
        .sortedBy { it.name.lowercase() }

    return Forest((realRoots + reRooted + virtuals).sortedWith(byName), ungrouped)
}

/** Finds any node in the forest by key (used to resolve pinned folders). */
fun findNode(roots: List<FolderNode>, key: String): FolderNode? {
    for (n in roots) {
        if (n.key == key) return n
        findNode(n.children, key)?.let { return it }
    }
    return null
}

/** All profile ids in a subtree (used to exclude pinned-folder members elsewhere). */
fun FolderNode.allProfileIds(): Set<String> =
    ownProfiles.map { it.id }.toSet() + children.flatMap { it.allProfileIds() }
