package com.acgcompass.feature.detail

import com.acgcompass.domain.detail.DetailRelation
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class DetailRouteEntriesTest : StringSpec({
    val current = Work(
        id = "100",
        titles = Titles(canonical = "本作标题"),
        mediaType = MediaType.ANIME,
        primarySource = SourceId.BANGUMI,
    )

    "no relations means no route" {
        buildRouteEntries(current, emptyList()).shouldBeEmpty()
    }

    "main line is prequel, this work, sequel, then optional, skippable and derived" {
        val relations = listOf(
            DetailRelation(id = 5, type = 2, name = "Recap", nameCn = "总集篇", relation = "总集篇"),
            DetailRelation(id = 3, type = 2, name = "Second", nameCn = "第二季", relation = "续集"),
            DetailRelation(id = 4, type = 2, name = "Movie", nameCn = "剧场版", relation = "番外篇"),
            DetailRelation(id = 2, type = 2, name = "Zero", nameCn = "前传", relation = "前传"),
            DetailRelation(id = 6, type = 4, name = "Game", nameCn = "游戏", relation = "改编"),
            DetailRelation(id = 7, type = 2, name = "", nameCn = "", relation = "续集"),
        )

        val route = buildRouteEntries(current, relations)

        route.map { it.workId } shouldBe listOf("2", "100", "3", "4", "5", "6")
        route.map { it.section } shouldBe listOf(
            RouteSection.MAIN,
            RouteSection.MAIN,
            RouteSection.MAIN,
            RouteSection.OPTIONAL,
            RouteSection.SKIPPABLE,
            RouteSection.DERIVED,
        )
        route[1].relationLabel shouldBe "本作"
        route[0].title shouldBe "前传"
    }

    "blank relation label falls back to a generic one" {
        val route = buildRouteEntries(current, listOf(DetailRelation(id = 9, type = 2, name = "Side", relation = " ")))
        route.first { it.workId == "9" }.relationLabel shouldBe "关联"
    }
})
