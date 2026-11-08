package com.piano.sequencer.midi

/** Small pure formatting primitives used on every passive diagnostics snapshot. */
internal object UsbDiagnosticFormat {
    const val MAX_LINE_CHARS = 4_000

    data class BoundedList(val lines: List<String>, val truncated: Boolean)

    fun <T> boundedList(values: Sequence<T>, limit: Int, marker: String, format: (T) -> String): BoundedList {
        require(limit >= 0)
        val result = ArrayList<String>(limit.coerceAtMost(16))
        val iterator = values.iterator()
        while (result.size < limit && iterator.hasNext()) result += format(iterator.next())
        val truncated = iterator.hasNext()
        if (truncated && result.isNotEmpty()) result[result.lastIndex] += "+$marker"
        return BoundedList(result, truncated)
    }

    fun sanitize(value: String?, maxChars: Int): String = value.orEmpty()
        .filter { it.isLetterOrDigit() || it in " ._-" }
        .take(maxChars.coerceAtLeast(0))

    fun boundedLine(value: String): String =
        if (value.length <= MAX_LINE_CHARS) value else value.take(MAX_LINE_CHARS - "...[line-truncated]".length) + "...[line-truncated]"

    fun boundedRecords(records: Sequence<String>, required: List<String>, maxLines: Int): List<String> {
        require(maxLines >= required.size)
        val out = ArrayList<String>(maxLines)
        required.take(maxLines).forEach { out += boundedLine(it) }
        val iterator = records.iterator()
        while (out.size < maxLines && iterator.hasNext()) out += boundedLine(iterator.next())
        if (iterator.hasNext() && out.isNotEmpty()) out[out.lastIndex] = boundedLine(out.last() + " [records-truncated]")
        return out
    }

    fun snapshotRecords(id: String, phase: String, build: String, usb: Sequence<String>, outputs: Sequence<String>, maxLines: Int): List<String> {
        val required = listOf("$id phase=$phase $build focus=unknown route=unknown outputMeaning=advertised-capabilities-not-active-route")
        return boundedRecords(usb.map { "$id usb=[$it]" } + outputs.map { "$id output=[$it]" }, required, maxLines)
    }
}
