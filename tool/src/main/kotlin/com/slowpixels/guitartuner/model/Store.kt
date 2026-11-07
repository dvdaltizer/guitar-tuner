package com.slowpixels.guitartuner.model

import java.io.File
import java.util.Properties

/** What the tool remembers between visits. Nothing else is kept, and nothing the mic hears is. */
data class Settings(
    /** The tuning's name, as [Tunings.named] takes it. */
    val tuning: String = Tunings.standard.id,
    /** The last few tunings used, newest first. */
    val recent: List<String> = emptyList(),
    val electric: Boolean = false,
    /** Where A above middle C sits, in Hz. */
    val a4: Int = 440,
    val chime: Boolean = true,
) {
    val tuningNow get() = Tunings.named(tuning)
    val recentTunings get() = recent.map(Tunings::named).distinctBy { it.id }

    /** With [t] chosen, and put first among the recent ones. */
    fun choosing(t: Tuning) = copy(tuning = t.id, recent = (listOf(t.id) + recent.filter { it != t.id }).take(RECENT))

    companion object {
        const val RECENT = 3
        val PITCHES = intArrayOf(432, 436, 438, 440, 442, 444, 446)
    }
}

/** One small file in the tool's own storage. It's written whole and then swapped in, so a kill mid-write loses nothing. */
class Store(private val file: File) {
    fun load(): Settings {
        val p = Properties()
        runCatching { file.inputStream().use(p::load) }
        val d = Settings()
        return Settings(
            tuning = Tunings.named(p.getProperty("tuning")).id,
            recent = p.getProperty("recent")?.split('\n')?.filter { it.isNotEmpty() } ?: d.recent,
            electric = p.getProperty("electric")?.toBooleanStrictOrNull() ?: d.electric,
            a4 = p.getProperty("a4")?.toIntOrNull()?.takeIf { it in Settings.PITCHES } ?: d.a4,
            chime = p.getProperty("chime")?.toBooleanStrictOrNull() ?: d.chime,
        )
    }

    fun save(s: Settings) {
        val p = Properties()
        p.setProperty("tuning", s.tuning)
        p.setProperty("recent", s.recent.joinToString("\n"))
        p.setProperty("electric", s.electric.toString())
        p.setProperty("a4", s.a4.toString())
        p.setProperty("chime", s.chime.toString())
        runCatching {
            val part = File(file.path + ".part")
            part.outputStream().use { p.store(it, null) }
            if (!part.renameTo(file)) part.delete()
        }
    }
}
