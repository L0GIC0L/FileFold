package io.github.l0gic0l.filefold.core

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renames several files in one go from a template. In the template,
 * `{name}` stands for the old name (without its extension), `{n}` for a
 * counter and `{date}` for the day the file was last changed. Extensions are
 * always kept. Text can also be found and replaced in the old name first.
 */
object BatchRename {

    data class Rule(
        val template: String = "{name}",
        val find: String = "",
        val replace: String = "",
        /** The first value of `{n}`. */
        val start: Int = 1,
    )

    data class Step(val source: File, val newName: String) {
        val changes: Boolean get() = source.name != newName
    }

    /** The [problem] reported while the template still gives every item the name it already has. */
    const val NOTHING_CHANGES = "Nothing would change"

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Splits "photo.tar.gz" into "photo" and ".tar.gz"; folders have no extension. */
    private fun split(file: File): Pair<String, String> {
        val name = file.name
        if (file.isDirectory) return name to ""
        val lower = name.lowercase()
        val compound = listOf(".tar.gz", ".tar.bz2", ".tar.xz").firstOrNull { lower.endsWith(it) && lower.length > it.length }
        val dot = if (compound != null) name.length - compound.length else name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }

    /** Works out the new name of every file, in the order given. */
    fun plan(files: List<File>, rule: Rule, zone: ZoneId = ZoneId.systemDefault()): List<Step> {
        val width = (rule.start + files.size - 1).coerceAtLeast(1).toString().length
        return files.mapIndexed { index, file ->
            val (stem, extension) = split(file)
            val name = if (rule.find.isEmpty()) stem else stem.replace(rule.find, rule.replace, ignoreCase = true)
            val number = (rule.start + index).toString().padStart(width, '0')
            val date = dateFormat.format(Instant.ofEpochMilli(file.lastModified()).atZone(zone))
            val newStem = rule.template
                .replace("{name}", name)
                .replace("{n}", number)
                .replace("{date}", date)
                .trim()
            // An empty result is left empty, so it is reported instead of becoming a bare ".jpg".
            Step(file, if (newStem.isEmpty()) "" else newStem + extension)
        }
    }

    /** Why [steps] cannot be carried out, or null when they can. */
    fun problem(steps: List<Step>): String? {
        if (steps.none { it.changes }) return NOTHING_CHANGES
        for (step in steps) {
            FileOps.validateName(step.newName)?.let { return "\"${step.newName}\": $it" }
        }
        val seen = HashSet<String>()
        for (step in steps) {
            // Compared without case: shared storage treats "A.txt" and "a.txt" as the same file.
            // Items found by a search can sit in different folders, where the same name is fine.
            if (!seen.add("${step.source.parent}/${step.newName.lowercase()}")) {
                return "Two items would both be called \"${step.newName}\". Add {n} or {name}."
            }
        }
        val renamed = steps.mapTo(HashSet()) { it.source.absolutePath }
        for (step in steps) {
            val target = File(step.source.parentFile, step.newName)
            if (step.changes && target.exists() && target.absolutePath !in renamed) return "\"${step.newName}\" already exists"
        }
        return null
    }

    /**
     * Carries out the renames. Every file first gets a temporary name, so swaps such as
     * a → b while b → a work. If anything fails, what was done is put back. Returns the
     * old and new file of everything that changed, which [undo] can reverse.
     */
    fun execute(steps: List<Step>, progress: Progress = Progress.None): List<Pair<File, File>> {
        problem(steps)?.let { throw IOException(it) }
        val changing = steps.filter { it.changes }
        val stamp = System.nanoTime()
        val parked = ArrayList<Pair<Step, File>>()
        val done = ArrayList<Pair<File, File>>()
        try {
            for ((index, step) in changing.withIndex()) {
                progress.ensureActive()
                val temp = File(step.source.parentFile, ".rename-$stamp-$index.tmp")
                if (!step.source.renameTo(temp)) throw IOException("Could not rename \"${step.source.name}\"")
                parked += step to temp
            }
            for ((step, temp) in parked) {
                progress.start(step.newName)
                val target = File(step.source.parentFile, step.newName)
                if (target.exists() || !temp.renameTo(target)) throw IOException("Could not rename \"${step.source.name}\"")
                done += step.source to target
            }
        } catch (e: Throwable) {
            for ((old, new) in done) new.renameTo(old)
            for ((step, temp) in parked) if (temp.exists()) temp.renameTo(step.source)
            throw e
        }
        return done
    }

    /** Gives everything renamed by [execute] its old name back. Returns how many were restored. */
    fun undo(renamed: List<Pair<File, File>>): Int {
        val steps = renamed.filter { it.second.exists() }.map { (old, new) -> Step(new, old.name) }
        if (steps.none { it.changes }) return 0
        return execute(steps).size
    }
}
