package com.example.transitkompakt.data

/**
 * Minimal RFC4180-ish CSV reader. GTFS quotes any field containing a comma
 * (route_long_name does this constantly), so a naive split() corrupts the data.
 */
internal object Csv {

    fun split(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** header name -> column index, tolerant of a UTF-8 BOM and stray spaces. */
    fun header(line: String): Map<String, Int> =
        split(line.removePrefix("\uFEFF")).withIndex().associate { (i, name) -> name.trim() to i }
}

internal fun List<String>.at(index: Int?): String =
    if (index == null || index >= size) "" else this[index].trim()
