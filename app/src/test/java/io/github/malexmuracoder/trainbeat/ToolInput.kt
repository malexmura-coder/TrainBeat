package io.github.malexmuracoder.trainbeat

import java.io.File

/**
 * Where the desktop tools take their input path from.
 *
 * Order: the -D system property when given, otherwise a small text file in the
 * project root that you edit once. Blank lines and lines starting with # are
 * ignored, so the file carries its own instructions.
 */
object ToolInput {

    fun path(property: String, fileName: String): String? {
        val fromProp = System.getProperty(property).orEmpty().trim()
        if (fromProp.isNotEmpty()) return fromProp

        for (candidate in listOf(File(fileName), File("..", fileName), File("../..", fileName))) {
            if (!candidate.isFile) continue
            val line = try {
                candidate.readLines()
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            } catch (_: Exception) {
                null
            }
            if (!line.isNullOrEmpty()) return line
        }
        return null
    }

    fun hint(fileName: String, property: String): String =
        "no path given: write it in $fileName in the project root, or pass -D$property=..."
}
