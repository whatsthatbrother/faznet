package com.faznet.tv.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.Reader

/** Big Xtream API answers, read as a stream: only the wanted fields of each object are ever kept. */
internal object XtreamJson {

    /**
     * Calls [make] for every object of the JSON array in [reader], with the [fields] it has (as text; JSON null and the
     * text "null" are left out). An answer that is not an array (an error object, an empty body) is an empty list, as
     * before; one that breaks off half way throws, so the screen can say the server failed.
     */
    fun <T> objects(reader: Reader, fields: Set<String>, make: (Map<String, String>) -> T?): List<T> {
        val out = ArrayList<T>()
        JsonReader(reader).use { r ->
            r.isLenient = true
            // An empty body: some panels answer so when there is nothing.
            val first = try { r.peek() } catch (e: java.io.EOFException) { return out }
            if (first != JsonToken.BEGIN_ARRAY) return out
            r.beginArray()
            val values = HashMap<String, String>(fields.size * 2)
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                values.clear()
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name !in fields) { r.skipValue(); continue }
                    text(r)?.takeUnless { it == "null" }?.let { values[name] = it }
                }
                r.endObject()
                make(values)?.let(out::add)
            }
            r.endArray()
        }
        return out
    }

    /** A value as text: servers send numbers sometimes as strings, sometimes as numbers. */
    private fun text(r: JsonReader): String? = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> { r.nextNull(); null }
        else -> { r.skipValue(); null }
    }
}
