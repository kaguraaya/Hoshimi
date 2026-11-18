package com.acgcompass.domain.matching

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonAndEditionMatchingTest {
    @Suppress("LongParameterList")
    private fun work(
        id: String,
        ja: String,
        cn: String? = null,
        romaji: String? = null,
        en: String? = null,
        aliases: List<String> = emptyList(),
        year: Int? = null,
        type: MediaType = MediaType.ANIME,
        source: SourceId = SourceId.ANILIST,
    ) = Work(
        id = id,
        titles = Titles(canonical = cn ?: romaji ?: ja, cn = cn, ja = ja, romaji = romaji, en = en, aliases = aliases),
        mediaType = type,
        year = year,
        primarySource = source,
    )

    @Test
    fun detectsSequelMarkers() {
        assertEquals(2, seasonNumberOf(listOf("Overlord II")))
        assertEquals(3, seasonNumberOf(listOf("ダンジョンに出会いを求めるのは間違っているだろうか Ⅲ")))
        assertEquals(2, seasonNumberOf(listOf("やはり俺の青春ラブコメはまちがっている。続")))
        assertEquals(2, seasonNumberOf(listOf("続・夏目友人帳")))
        assertEquals(2, seasonNumberOf(listOf("Fate/Zero 2ndシーズン")))
        assertEquals(2, seasonNumberOf(listOf("ゆるキャン△ 2期")))
        assertEquals(2, seasonNumberOf(listOf("Mob Psycho 100 II")))
    }

    @Test
    fun plainTitlesStayFirstSeason() {
        listOf("22/7", "けいおん!!", "STEINS;GATE 0", "ぼっち・ざ・ろっく！", "ヴァイオレット・エヴァーガーデン", "Ivy")
            .forEach { assertEquals(it, 1, seasonNumberOf(listOf(it))) }
    }

    @Test
    fun sequelIsNotMergedWithFirstSeason() {
        val s1 = work("1", "やはり俺の青春ラブコメはまちがっている。", source = SourceId.BANGUMI)
        val s2 = work("2", "やはり俺の青春ラブコメはまちがっている。続")
        assertFalse(sameWork(s1, s2))
        assertFalse(sameSeasonAndEdition(s1, s2))
        assertFalse(sameWork(work("3", "オーバーロード", romaji = "Overlord"), work("4", "オーバーロードII", romaji = "Overlord II")))
    }

    @Test
    fun derivativeEntriesAreNotMergedWithTheShow() {
        val show = work("1", "ぼっち・ざ・ろっく！", source = SourceId.BANGUMI)
        listOf("ぼっち・ざ・ろっく！ ノンクレジットOP", "ぼっち・ざ・ろっく！ PV", "ぼっち・ざ・ろっく！ ミニアニメ", "ぼっち・ざ・ろっく！ 映像特典")
            .forEachIndexed { i, title -> assertFalse(title, sameWork(show, work("d$i", title))) }
        // 主题曲 MV 一类条目在映射时归为 OTHER，与动画类型冲突。
        assertFalse(sameWork(show, work("m", "ぼっち・ざ・ろっく！", type = MediaType.OTHER)))
    }

    @Test
    fun foreignNamesMatchThroughOriginalTitleOrAlias() {
        val bangumi = work(
            "328609",
            ja = "ぼっち・ざ・ろっく！",
            cn = "孤独摇滚！",
            aliases = listOf("BTR", "Bocchi the Rock!"),
            year = 2022,
            source = SourceId.BANGUMI,
        )
        val tenrai =
            work("47917", ja = "ぼっち・ざ・ろっく！", romaji = "Bocchi the Rock!", year = 2022, source = SourceId.TENRAI)
        val englishOnly = work("x", ja = "Bocchi the Rock!", year = 2022, source = SourceId.MAL)
        assertTrue(sameWork(bangumi, tenrai))
        assertTrue(sameWork(bangumi, englishOnly))
    }

    @Test
    fun abbreviationAliasDoesNotMarkTheShowAsThemeSong() {
        val bangumi = work("975", ja = "ONE PIECE", cn = "海贼王", aliases = listOf("OP"), source = SourceId.BANGUMI)
        val anilist = work("21", ja = "ONE PIECE", romaji = "ONE PIECE")
        assertTrue(sameWork(bangumi, anilist))
    }

    @Test
    fun foreignQueriesSkipChineseAndAbbreviations() {
        val bangumi = work(
            "328609",
            ja = "ぼっち・ざ・ろっく！",
            cn = "孤独摇滚！",
            aliases = listOf("BTR", "Bocchi the Rock!"),
            source = SourceId.BANGUMI,
        )
        val queries = foreignSearchQueries(bangumi)
        assertEquals("ぼっち・ざ・ろっく！", queries.first())
        assertTrue("Bocchi the Rock!" in queries)
        assertFalse("孤独摇滚！" in queries)
        assertFalse("BTR" in queries)
    }
}
