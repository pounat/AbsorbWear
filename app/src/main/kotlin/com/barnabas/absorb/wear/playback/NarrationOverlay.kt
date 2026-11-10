package com.barnabas.absorb.wear.playback

import com.barnabas.absorb.wear.api.BoPlaylist
import kotlinx.serialization.Serializable
import kotlin.math.max

/**
 * An EPUB 3 media overlay (a narrated or "read along" EPUB) laid out the way
 * the player sees it: the audio files end to end, each sentence a stretch of
 * one file. Built from BookOrbit's `/epub/:bookId/media-overlay` playlist or
 * straight from the EPUB's package and SMIL files, the same way the phone
 * client builds it, so positions and the sentence saved with them line up
 * across devices.
 */
class NarrationOverlay private constructor(
    val tracks: List<Track>,
    val clips: List<Clip>,
    val sections: List<Section>,
) {
    /** An audio file inside the EPUB, placed on the book's timeline. */
    class Track(val href: String, val mediaType: String, val duration: Double, val start: Double)

    /** One narrated sentence: where its text is and which stretch of which
     *  audio file reads it. */
    class Clip(
        val section: Int,
        val textHref: String,
        val fragment: String?,
        val track: Int,
        val begin: Double,
        val end: Double,
    ) {
        /** The sentence the way the SMIL file names it, document and id.
         *  BookOrbit's read along keeps its place in this form. */
        val textSrc: String get() = if (fragment == null) textHref else "$textHref#$fragment"
    }

    /** A narrated chapter (spine item) and where its narration starts. */
    class Section(val index: Int, val href: String, val label: String?, val start: Double)

    /** A sentence as read from a playlist or SMIL file, before layout. */
    class RawClip(
        val section: Int,
        val textHref: String,
        val fragment: String?,
        val audioHref: String,
        val begin: Double,
        var end: Double?,
    )

    /** The overlay as saved to disk, so a book's timings are read once. */
    @Serializable
    data class Snapshot(
        val tracks: List<TrackDto>,
        val clips: List<ClipDto>,
        val sections: List<SectionDto>,
    ) {
        @Serializable
        data class TrackDto(val href: String, val mediaType: String, val duration: Double, val start: Double)

        @Serializable
        data class ClipDto(
            val section: Int,
            val textHref: String,
            val fragment: String?,
            val track: Int,
            val begin: Double,
            val end: Double,
        )

        @Serializable
        data class SectionDto(val index: Int, val href: String, val label: String?, val start: Double)
    }

    private val byTrack: List<List<Int>> = List(tracks.size) { t ->
        clips.indices.filter { clips[it].track == t }.sortedBy { clips[it].begin }
    }

    val duration: Double get() = if (tracks.isEmpty()) 0.0 else tracks.last().start + tracks.last().duration

    val isEmpty: Boolean get() = clips.isEmpty()

    fun snapshot(): Snapshot = Snapshot(
        tracks = tracks.map { Snapshot.TrackDto(it.href, it.mediaType, it.duration, it.start) },
        clips = clips.map { Snapshot.ClipDto(it.section, it.textHref, it.fragment, it.track, it.begin, it.end) },
        sections = sections.map { Snapshot.SectionDto(it.index, it.href, it.label, it.start) },
    )

    /** The track playing at book time [t]. */
    fun trackAt(t: Double): Int {
        var lo = 0
        var hi = tracks.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tracks[mid].start <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** The sentence being read at book time [t]: the last one to have
     *  started in the file playing then. */
    fun clipAt(t: Double): Clip? {
        if (tracks.isEmpty()) return null
        val track = trackAt(t)
        val local = t - tracks[track].start
        val list = byTrack[track]
        var lo = 0
        var hi = list.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (clips[list[mid]].begin <= local + 0.001) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (ans < 0) return if (list.isEmpty()) null else clips[list.first()]
        return clips[list[ans]]
    }

    /** Book time where [clip] starts. */
    fun startOf(clip: Clip): Double = tracks[clip.track].start + clip.begin

    /** The sentence whose element is [fragment], in [section] when given.
     *  The id can come bare or with its document in front
     *  (`text/ch1.xhtml#s12`), the way BookOrbit keeps it. */
    fun clipByFragment(fragment: String, section: Int?): Clip? {
        val hash = fragment.indexOf('#')
        val id = if (hash < 0) fragment else fragment.substring(hash + 1)
        val doc = if (hash > 0) fragment.substring(0, hash) else null
        var any: Clip? = null
        for (c in clips) {
            if (c.fragment != id) continue
            if (doc != null && !sameDoc(c.textHref, doc)) continue
            if (section == null || c.section == section) return c
            if (any == null) any = c
        }
        return any
    }

    /** The chapters the player shows: each named section starts one and
     *  unnamed sections after it carry it on; a book with no names gets one
     *  chapter per section. */
    fun chapters(): List<Chapter> {
        val named = sections.any { !it.label.isNullOrBlank() }
        val out = ArrayList<Chapter>()
        var openingUnnamed = false
        for (i in sections.indices) {
            val s = sections[i]
            val end = if (i + 1 < sections.size) sections[i + 1].start else duration
            if (end <= s.start) continue
            val label = s.label?.trim().orEmpty()
            if (named && label.isEmpty() && out.isNotEmpty()) {
                out[out.lastIndex] = out.last().copy(end = end)
                continue
            }
            if (out.isEmpty()) openingUnnamed = named && label.isEmpty()
            out.add(Chapter(id = out.size, start = s.start, end = end, title = label.ifEmpty { "Chapter ${out.size + 1}" }))
        }
        if (openingUnnamed && out.size > 1) {
            val merged = out[1].copy(start = out[0].start)
            out.removeAt(0)
            out[0] = merged
            return out.mapIndexed { i, c -> c.copy(id = i) }
        }
        return out
    }

    /** The sections named from the book's contents: [spine] is every file
     *  in reading order, [labels] the first contents entry pointing into
     *  each. An entry on a file with no narration (a chapter's title page)
     *  names the next narrated file the contents leave out. */
    fun withContents(spine: List<String>, labels: Map<String, String>): NarrationOverlay {
        val names = HashMap<Int, String?>()
        var pending: String? = null
        for (href in spine) {
            val own = labels[href]
            val section = sections.firstOrNull { sameDoc(it.href, href) }
            if (section == null) {
                if (own != null) pending = own
                continue
            }
            names[section.index] = own ?: pending ?: section.label
            pending = null
        }
        return NarrationOverlay(
            tracks,
            clips,
            sections.map { s -> Section(s.index, s.href, if (names.containsKey(s.index)) names[s.index] else s.label, s.start) },
        )
    }

    companion object {
        fun fromSnapshot(s: Snapshot): NarrationOverlay? {
            if (s.tracks.isEmpty() || s.clips.isEmpty()) return null
            return NarrationOverlay(
                s.tracks.map { Track(it.href, it.mediaType, it.duration, it.start) },
                s.clips.map { Clip(it.section, it.textHref, it.fragment, it.track, it.begin, it.end) },
                s.sections.map { Section(it.index, it.href, it.label, it.start) },
            )
        }

        /** From BookOrbit's media-overlay playlist. Null when nothing is narrated. */
        fun fromBookOrbit(playlist: BoPlaylist): NarrationOverlay? {
            val types = playlist.resources
                .filter { !it.href.isNullOrEmpty() && !it.mediaType.isNullOrEmpty() }
                .associate { it.href!! to it.mediaType!! }
            val sectionInfo = HashMap<Int, Pair<String, String?>>()
            for (s in playlist.sections) {
                val index = s.index ?: continue
                val href = s.href ?: continue
                sectionInfo[index] = href to s.label
            }
            val raw = ArrayList<RawClip>()
            for (i in playlist.items) {
                val audio = i.audioHref ?: continue
                val text = i.textHref ?: continue
                val begin = i.clipBeginSeconds ?: continue
                val section = i.sectionIndex ?: 0
                raw.add(RawClip(section, text, i.textFragment, audio, begin, i.clipEndSeconds))
                if (!sectionInfo.containsKey(section) && i.label != null) sectionInfo[section] = text to i.label
            }
            return build(raw, types, sectionInfo)
        }

        /** Lay [raw] sentences out: the audio files end to end in the order
         *  they are first used, a clip with no end running to the next clip
         *  in its file. */
        fun build(
            raw: List<RawClip>,
            mediaTypes: Map<String, String>,
            sectionInfo: Map<Int, Pair<String, String?>>,
        ): NarrationOverlay? {
            if (raw.isEmpty()) return null
            val order = LinkedHashMap<String, Int>()
            for (c in raw) order.getOrPut(c.audioHref) { order.size }
            val byFile = raw.groupBy { it.audioHref }.mapValues { (_, list) -> list.sortedBy { it.begin } }
            for (list in byFile.values) {
                for (i in list.indices) {
                    if (list[i].end != null) continue
                    list[i].end = if (i + 1 < list.size) list[i + 1].begin else list[i].begin
                }
            }
            val tracks = ArrayList<Track>()
            var start = 0.0
            for (href in order.keys) {
                val d = byFile.getValue(href).fold(0.0) { m, c -> max(m, c.end ?: c.begin) }
                tracks.add(Track(href, mediaTypes[href] ?: guessType(href), d, start))
                start += d
            }
            val clips = raw.map { c ->
                Clip(
                    section = c.section,
                    textHref = c.textHref,
                    fragment = c.fragment,
                    track = order.getValue(c.audioHref),
                    begin = c.begin,
                    end = c.end ?: c.begin,
                )
            }
            val sections = ArrayList<Section>()
            val seen = HashSet<Int>()
            for (c in clips) {
                if (!seen.add(c.section)) continue
                val info = sectionInfo[c.section]
                sections.add(Section(c.section, info?.first ?: c.textHref, info?.second, tracks[c.track].start + c.begin))
            }
            sections.sortBy { it.start }
            return NarrationOverlay(tracks, clips, sections)
        }

        fun guessType(href: String): String = when (href.substringAfterLast('.').lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a", "m4b", "mp4", "aac" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

        fun sameDoc(a: String, b: String): Boolean {
            if (a == b) return true
            val x = docPath(a)
            val y = docPath(b)
            return x == y || x.endsWith("/$y") || y.endsWith("/$x")
        }

        // BookOrbit's timings can keep a name escaped (%2C) where the EPUB's
        // own path has it plain, so both are compared with escapes undone.
        private fun docPath(href: String): String = undoEscapes(href.substringBefore('#'))

        /** Every percent escape undone, a plus sign left alone. */
        fun undoEscapes(path: String): String = try {
            java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            path
        }
    }
}
