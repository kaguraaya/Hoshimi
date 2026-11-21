package com.acgcompass.data.local.migration

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.data.local.AcgCompassDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 按导出的 21 版 schema 建库、写入旧数据，再用正式的迁移列表打开：Room 会校验迁移后的表结构和 22 版完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration21To22RoomTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migratesFrom21AndKeepsRows() = runBlocking {
        val name = "migration-21-22.db"
        context.deleteDatabase(name)
        createVersion21(name)

        val db = Room.databaseBuilder(context, AcgCompassDatabase::class.java, name)
            .addMigrations(*AcgMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("旧作品", db.workDao().getById("1")?.canonicalTitle)
            // 旧的作品资料保留，评分分布为空（以后刷新资料时补上）。
            val feature = db.workFeatureDao().getById("1")
            assertEquals(7.5f, feature?.bangumiScore)
            assertEquals("", feature?.ratingHistogram)
            assertEquals(0, db.predictionSnapshotDao().count())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersion21(name: String) {
        val schema = Json.parseToJsonElement(File(SCHEMA_21).readText()).jsonObject["database"]!!.jsonObject
        val statements = buildList {
            schema["entities"]!!.jsonArray.forEach { entity ->
                val obj = entity.jsonObject
                val table = obj["tableName"]!!.jsonPrimitive.content
                add(obj["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                obj["indices"]?.jsonArray?.forEach { index ->
                    add(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { add(it.jsonPrimitive.content) }
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        statements.forEach(db::execSQL)
                        db.execSQL(
                            "INSERT INTO works (id, canonicalTitle, aliases, mediaType, status, primarySource, " +
                                "createdAt, updatedAt) VALUES ('1', '旧作品', '[]', 'ANIME', 'UNKNOWN', 'BANGUMI', 0, 0)",
                        )
                        db.execSQL(
                            "INSERT INTO work_features (subjectId, tagCountsJson, staff, characters, cv, " +
                                "bangumiScore, bangumiVotes, eps, durationMin, titles, updatedAt) " +
                                "VALUES ('1', '[]', '[]', '[]', '[]', 7.5, 100, 12, 24, '[]', 0)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()
    }

    private companion object {
        const val SCHEMA_21 = "schemas/com.acgcompass.data.local.AcgCompassDatabase/21.json"
    }
}
