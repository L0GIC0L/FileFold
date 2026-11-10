package io.github.l0gic0l.filefold.core

enum class SortBy(val label: String) {
    NAME("Name"),
    DATE("Date modified"),
    SIZE("Size"),
    TYPE("Type"),
}

/** How every folder listing is ordered. Applied automatically whenever a folder loads. */
data class SortOptions(
    val by: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
) {
    fun comparator(): Comparator<FileItem> {
        val byName = Comparator<FileItem> { a, b -> NaturalOrder.compare(a.name, b.name) }
        val primary: Comparator<FileItem> = when (by) {
            SortBy.NAME -> byName
            SortBy.DATE -> compareBy { it.modified }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.TYPE -> compareBy { FileTypes.extensionOf(it.name) }
        }
        // Ties always fall back to the name so the order is stable and predictable.
        val directed = (if (ascending) primary else primary.reversed()).then(byName)
        return if (foldersFirst) compareByDescending<FileItem> { it.isDirectory }.then(directed) else directed
    }
}

/**
 * Case-insensitive ordering that compares runs of digits by their numeric
 * value, so "file2" sorts before "file10".
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val endA = digitRunEnd(a, i)
                val endB = digitRunEnd(b, j)
                val numA = a.substring(i, endA).trimStart('0')
                val numB = b.substring(j, endB).trimStart('0')
                // A longer run of significant digits is the larger number.
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        val remaining = (a.length - i) - (b.length - j)
        return if (remaining != 0) remaining else a.compareTo(b)
    }

    private fun digitRunEnd(s: String, start: Int): Int {
        var end = start
        while (end < s.length && s[end].isDigit()) end++
        return end
    }
}
