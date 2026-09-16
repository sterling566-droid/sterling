package org.sterling.assistant

import android.content.Context
import android.os.Environment
import android.provider.ContactsContract
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Three tools the model can call: web search (scrapes DuckDuckGo's HTML
 * results - no API key needed, but fragile to their markup changing),
 * contacts lookup (needs READ_CONTACTS), and file browsing on shared
 * storage (needs "All files access" / MANAGE_EXTERNAL_STORAGE on Android
 * 11+). File access is restricted to inside the shared storage root as a
 * basic safety rail against path traversal.
 */
object Tools {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun schema(): JSONArray {
        val arr = JSONArray()
        arr.put(
            functionSpec(
                "web_search",
                "Search the public web for current information. Returns a short list of result titles, snippets, and URLs.",
                mapOf("query" to "The search query"),
                listOf("query")
            )
        )
        arr.put(
            functionSpec(
                "search_contacts",
                "Search the phone's contacts by name and return matching names and phone numbers.",
                mapOf("name" to "Full or partial contact name to search for"),
                listOf("name")
            )
        )
        arr.put(
            functionSpec(
                "list_files",
                "List files and folders inside a directory on the phone's shared storage.",
                mapOf("path" to "Directory path relative to shared storage root, e.g. 'Download'. Empty string for the root."),
                listOf()
            )
        )
        arr.put(
            functionSpec(
                "read_text_file",
                "Read the text contents of a small text file (e.g. .txt, .md) on the phone's shared storage.",
                mapOf("path" to "File path relative to shared storage root"),
                listOf("path")
            )
        )
        return arr
    }

    private fun functionSpec(
        name: String,
        description: String,
        params: Map<String, String>,
        required: List<String>,
    ): JSONObject {
        val properties = JSONObject()
        params.forEach { (key, desc) ->
            properties.put(key, JSONObject().put("type", "string").put("description", desc))
        }
        val parameters = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) parameters.put("required", JSONArray(required))

        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters)
            )
    }

    fun execute(context: Context, name: String, args: JSONObject): String {
        return try {
            when (name) {
                "web_search" -> webSearch(args.optString("query", ""))
                "search_contacts" -> searchContacts(context, args.optString("name", ""))
                "list_files" -> listFiles(args.optString("path", ""))
                "read_text_file" -> readTextFile(args.optString("path", ""))
                else -> JSONObject().put("error", "unknown tool: $name").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "unknown error").toString()
        }
    }

    private fun webSearch(query: String): String {
        if (query.isBlank()) return JSONObject().put("error", "no query given").toString()

        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) Sterling/1.0")
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return JSONObject().put("error", "search request failed: ${resp.code}").toString()
            }
            val html = resp.body?.string() ?: ""
            val doc = Jsoup.parse(html)
            val results = JSONArray()
            for (el in doc.select(".result").take(5)) {
                val titleEl = el.selectFirst(".result__a") ?: continue
                val snippetEl = el.selectFirst(".result__snippet")
                results.put(
                    JSONObject()
                        .put("title", titleEl.text())
                        .put("url", titleEl.attr("href"))
                        .put("snippet", snippetEl?.text() ?: "")
                )
            }
            if (results.length() == 0) {
                return JSONObject()
                    .put("results", results)
                    .put("note", "no results parsed - DuckDuckGo's HTML may have changed")
                    .toString()
            }
            return JSONObject().put("results", results).toString()
        }
    }

    private fun searchContacts(context: Context, name: String): String {
        if (name.isBlank()) return JSONObject().put("error", "no name given").toString()

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$name%")

        val results = JSONArray()
        try {
            context.contentResolver.query(uri, null, selection, selectionArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val seen = mutableSetOf<Pair<String?, String?>>()
                while (cursor.moveToNext() && results.length() < 8) {
                    val contactName = cursor.getString(nameIdx)
                    val number = cursor.getString(numberIdx)
                    val key = Pair(contactName, number)
                    if (seen.contains(key)) continue
                    seen.add(key)
                    results.put(JSONObject().put("name", contactName).put("number", number))
                }
            }
        } catch (e: SecurityException) {
            return JSONObject().put("error", "contacts permission not granted: ${e.message}").toString()
        }
        return JSONObject().put("contacts", results).toString()
    }

    private fun storageRoot(): File = Environment.getExternalStorageDirectory()

    private fun safeJoin(relPath: String): File {
        val root = storageRoot().canonicalFile
        val full = File(root, relPath).canonicalFile
        if (full != root && !full.path.startsWith(root.path + File.separator)) {
            throw SecurityException("path escapes shared storage root")
        }
        return full
    }

    private fun listFiles(relPath: String): String {
        return try {
            val dir = safeJoin(relPath)
            if (!dir.isDirectory) {
                return JSONObject().put("error", "not a directory: ${relPath.ifBlank { "/" }}").toString()
            }
            val entries = JSONArray()
            dir.listFiles()
                ?.sortedBy { it.name }
                ?.take(200)
                ?.forEach { entries.put(JSONObject().put("name", it.name).put("is_dir", it.isDirectory)) }
            JSONObject().put("path", relPath.ifBlank { "/" }).put("entries", entries).toString()
        } catch (e: SecurityException) {
            JSONObject().put("error", "permission denied - grant 'All files access' to Sterling: ${e.message}").toString()
        } catch (e: Exception) {
            JSONObject().put("error", "list_files failed: ${e.message}").toString()
        }
    }

    private fun readTextFile(relPath: String): String {
        if (relPath.isBlank()) return JSONObject().put("error", "no path given").toString()
        return try {
            val file = safeJoin(relPath)
            if (!file.isFile) return JSONObject().put("error", "not a file: $relPath").toString()
            if (file.length() > 2_000_000) return JSONObject().put("error", "file too large to read (over 2MB)").toString()
            val content = file.readText().take(4000)
            JSONObject().put("path", relPath).put("content", content).toString()
        } catch (e: SecurityException) {
            JSONObject().put("error", "permission denied - grant 'All files access' to Sterling: ${e.message}").toString()
        } catch (e: Exception) {
            JSONObject().put("error", "read_text_file failed: ${e.message}").toString()
        }
    }
}
