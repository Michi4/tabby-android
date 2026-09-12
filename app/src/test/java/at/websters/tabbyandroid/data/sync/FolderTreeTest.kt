package at.websters.tabbyandroid.data.sync

import at.websters.tabbyandroid.data.model.SshProfile
import at.websters.tabbyandroid.data.model.TabbyGroup
import org.junit.Assert.*
import org.junit.Test

class FolderTreeTest {
    private fun p(id: String, name: String, group: String? = null, groupName: String? = null) =
        SshProfile(id = id, name = name, host = "h", group = group, groupName = groupName)

    @Test fun nestedThreeLevels() {
        val groups = listOf(
            TabbyGroup("a", "A"),
            TabbyGroup("b", "B", parentId = "a"),
            TabbyGroup("c", "C", parentId = "b"),
        )
        val f = buildForest(listOf(p("1", "h1", "c")), groups)
        assertEquals(1, f.roots.size)
        val a = f.roots[0]
        assertEquals("A", a.name)
        assertEquals(1, a.children.size)
        assertEquals("B", a.children[0].name)
        assertEquals("C", a.children[0].children[0].name)
        assertEquals(1, a.children[0].children[0].ownProfiles.size)
        assertEquals(1, a.totalProfiles)
        assertTrue(f.ungrouped.isEmpty())
    }

    @Test fun orphanParentBecomesRoot() {
        val groups = listOf(TabbyGroup("x", "Orphan", parentId = "missing"))
        val f = buildForest(emptyList(), groups)
        assertEquals(1, f.roots.size)
        assertEquals("Orphan", f.roots[0].name)
    }

    @Test fun cycleDoesNotHangOrHide() {
        val groups = listOf(
            TabbyGroup("a", "A", parentId = "b"),
            TabbyGroup("b", "B", parentId = "a"),
        )
        val f = buildForest(listOf(p("1", "h", "a")), groups)
        // re-rooted: both folders visible exactly once, profile reachable
        val names = mutableListOf<String>()
        fun walk(n: FolderNode) {
            names += n.name
            n.children.forEach(::walk)
        }
        f.roots.forEach(::walk)
        assertTrue(names.contains("A"))
        assertTrue(names.contains("B"))
        val allIds = f.roots.flatMap { it.allProfileIds() } + f.ungrouped.map { it.id }
        assertTrue(allIds.contains("1"))
    }

    @Test fun unknownGroupIdBecomesVirtualFolder() {
        val f = buildForest(listOf(p("1", "h", "zzz", "Mystery")), emptyList())
        assertEquals(1, f.roots.size)
        assertEquals("Mystery", f.roots[0].name)
        assertEquals(null, f.roots[0].groupId)
        assertEquals(1, f.roots[0].ownProfiles.size)
    }

    @Test fun ungroupedSeparatedAndSorted() {
        val f = buildForest(
            listOf(p("2", "b-host"), p("1", "a-host"), p("3", "g", "g1", "G")),
            listOf(TabbyGroup("g1", "G")),
        )
        assertEquals(listOf("a-host", "b-host"), f.ungrouped.map { it.name })
        assertEquals(1, f.roots.size)
    }

    @Test fun findNodeAndIds() {
        val groups = listOf(TabbyGroup("a", "A"), TabbyGroup("b", "B", parentId = "a"))
        val f = buildForest(listOf(p("1", "h", "b")), groups)
        val b = findNode(f.roots, "b")
        assertNotNull(b)
        assertEquals(setOf("1"), b!!.allProfileIds())
        assertNull(findNode(f.roots, "nope"))
    }
}
