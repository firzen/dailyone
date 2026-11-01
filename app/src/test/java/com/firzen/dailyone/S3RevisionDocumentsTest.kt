package com.firzen.dailyone

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class S3RevisionDocumentsTest {
    private class Objects {
        val files = linkedMapOf<String, ByteArray>()
        var failReadOnce = false
        fun client() = S3RevisionDocuments(
            list = { prefix -> files.keys.filter { it.startsWith(prefix) }.toSet() },
            read = { path, limit ->
                if (failReadOnce) { failReadOnce = false; throw IOException("interrupted") }
                files.getValue(path).also { require(it.size <= limit) }.copyOf()
            },
            write = { path, data ->
                files[path]?.let { check(it.contentEquals(data)) { "Attempt to overwrite history" } }
                files[path] = data.copyOf()
            },
        )
    }
    private suspend fun S3RevisionDocuments.entry(id: String): Entry {
        val (raw, tag) = getDocument("entries", id)
        return Entry.parse(raw, tag)
    }
    private suspend fun S3RevisionDocuments.entries(): List<Entry> = listDocuments("entries").keys.map { entry(it) }

    @Test fun linearEditsKeepHistoryAndStaleBaselinesCannotReplaceNewerEdits() = runBlocking {
        val objects = Objects(); val client = objects.client(); val entry = Entry(body = "初始\n🌿\"\\\t", recordedLocation = GeoPoint(31.2304, 121.4737, "测试"))
        val first = client.putDocument("entries", entry.json(), null)
        assertEquals(entry.body, objects.client().entries().single().body)
        val second = client.putDocument("entries", entry.copy(body = "第二版").json(), first)
        try { client.putDocument("entries", entry.copy(body = "旧版覆盖").json(), first); fail() } catch (_: IOException) {}
        assertEquals(second, client.entry(entry.id).etag)
        assertEquals("第二版", objects.client().entries().single().body)
        assertEquals(2, objects.files.size)
    }
    @Test fun simultaneousEditsFromTwoClientsKeepBothBranchesAndStableConflictIds() = runBlocking {
        val objects = Objects(); val entry = Entry(body = "初始")
        objects.client().putDocument("entries", entry.json(), null)
        val a = objects.client(); val b = objects.client()
        val ta = a.entries().single().etag; val tb = b.entries().single().etag
        a.putDocument("entries", entry.copy(body = "设备 A").json(), ta)
        b.putDocument("entries", entry.copy(body = "设备 B").json(), tb)
        val resolved = objects.client().entries()
        assertEquals(setOf("设备 A", "设备 B"), resolved.map { it.body }.toSet())
        assertEquals(2, resolved.size)
        assertEquals(resolved.map { it.id }.toSet(), objects.client().entries().map { it.id }.toSet())
        assertEquals(4, objects.files.size)
        val fork = resolved.single { it.id != entry.id }
        val editor = objects.client(); editor.entries()
        editor.putDocument("entries", fork.copy(body = "编辑冲突副本").json(), fork.etag)
        val after = objects.client().entries()
        assertEquals(2, after.size)
        assertEquals("编辑冲突副本", after.single { it.id == fork.id }.body)
        val deleter = objects.client(); val current = deleter.entries().single { it.id == fork.id }
        deleter.putDocument("entries", current.copy(deleted = true).json(), current.etag)
        assertTrue(objects.client().entries().single { it.id == fork.id }.deleted)
        assertEquals(1, objects.client().entries().count { !it.deleted })
    }
    @Test fun simultaneousCreationDoesNotOverwriteEitherRecord() = runBlocking {
        val objects = Objects(); val a = objects.client(); val b = objects.client(); val entry = Entry(body = "A")
        a.listDocuments("entries"); b.listDocuments("entries")
        a.putDocument("entries", entry.json(), null)
        b.putDocument("entries", entry.copy(body = "B").json(), null)
        assertEquals(setOf("A", "B"), objects.client().entries().map { it.body }.toSet())
    }
    @Test fun concurrentDeleteDoesNotDiscardTheOtherDevicesEdit() = runBlocking {
        val objects = Objects(); val entry = Entry(body = "初始")
        objects.client().putDocument("entries", entry.json(), null)
        val a = objects.client(); val b = objects.client(); val ta = a.entries().single().etag; val tb = b.entries().single().etag
        a.putDocument("entries", entry.copy(deleted = true).json(), ta)
        b.putDocument("entries", entry.copy(body = "另一设备的新内容").json(), tb)
        val resolved = objects.client().entries()
        assertEquals(2, resolved.size)
        assertEquals("另一设备的新内容", resolved.single { !it.deleted }.body)
        assertTrue(resolved.any { it.deleted })
    }
    @Test fun legacyDocumentsArePreservedAndArchivedOnlyOnce() = runBlocking {
        val objects = Objects(); val entry = Entry(body = "旧格式")
        val path = "entries/${entry.id}.json"; val original = entry.json().toByteArray(); objects.files[path] = original
        val client = objects.client(); val imported = client.entries().single()
        client.putDocument("entries", entry.copy(body = "新格式修改").json(), imported.etag)
        assertEquals("新格式修改", objects.client().entries().single().body)
        assertArrayEquals(original, objects.files[path])
        assertEquals(3, objects.files.size)
        objects.client().entries(); assertEquals(3, objects.files.size)
    }
    @Test fun legacyJsonFieldOrderDoesNotCreateAnotherRootOrConflict() = runBlocking {
        val objects = Objects(); val entry = Entry(body = "同一内容")
        val path = "entries/${entry.id}.json"
        objects.files[path] = entry.json().toByteArray()
        val first = objects.client().entries().single()
        val json = JSONObject(entry.json())
        val reordered = json.keys().asSequence().sortedDescending().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + JSONObject.valueToString(json.get(it))
        }
        objects.files[path] = reordered.toByteArray()
        val second = objects.client().entries().single()
        assertEquals(first.etag, second.etag)
        assertEquals(2, objects.files.size)
    }
    @Test fun unknownUploadOutcomeIsIdempotentAndPreservesTheCommittedVersion() = runBlocking {
        val objects = Objects(); val client = objects.client(); val entry = Entry(body = "已上传但未确认")
        client.listDocuments("entries"); objects.failReadOnce = true
        try { client.putDocument("entries", entry.json(), null); fail() } catch (_: IOException) {}
        client.putDocument("entries", entry.json(), null)
        assertEquals(1, objects.files.size)
        assertEquals(entry.body, objects.client().entries().single().body)
    }
    @Test fun notebooksAlsoPreserveConcurrentRenames() = runBlocking {
        val objects = Objects(); val original = Notebook(name = "原名")
        objects.client().putDocument("notebooks", original.json(), null)
        val a = objects.client(); val b = objects.client()
        val ta = a.listDocuments("notebooks").getValue(original.id); val tb = b.listDocuments("notebooks").getValue(original.id)
        a.putDocument("notebooks", original.copy(name = "A").json(), ta)
        b.putDocument("notebooks", original.copy(name = "B").json(), tb)
        val reader = objects.client(); val books = reader.listDocuments("notebooks").keys.map { Notebook.parse(reader.getDocument("notebooks", it).first) }
        assertEquals(2, books.size)
        assertEquals(setOf("A", "B"), books.map { it.name.removeSuffix("（冲突副本）") }.toSet())
    }
    @Test fun cyclicMetadataStopsBeforeItCanHideAllRecords() = runBlocking {
        val objects = Objects(); val id = Entry().id
        val a = "a".repeat(64); val b = "b".repeat(64)
        objects.files[".versions/entries/$id/$a.$b.json"] = byteArrayOf()
        objects.files[".versions/entries/$id/$b.$a.json"] = byteArrayOf()
        try { objects.client().entries(); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("循环引用"))
        }
    }
    @Test fun corruptedVersionsAndMissingParentsStopSyncInsteadOfHidingContent() = runBlocking {
        val objects = Objects(); val entry = Entry(body = "原文")
        val client = objects.client(); val first = client.putDocument("entries", entry.json(), null)
        client.putDocument("entries", entry.copy(body = "新文").json(), first)
        val root = objects.files.keys.first { "/root." in it }
        val saved = objects.files.remove(root)!!
        try { objects.client().entries(); fail() } catch (_: IOException) {}
        objects.files[root] = saved
        val latest = objects.files.keys.first { "/root." !in it }
        objects.files[latest] = JSONObject().put("tampered", true).toString().toByteArray()
        try { objects.client().entries(); fail() } catch (_: IllegalArgumentException) {}
    }
}
