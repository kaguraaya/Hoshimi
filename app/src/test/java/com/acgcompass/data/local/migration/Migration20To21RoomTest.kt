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
 * 按导出的 20 版 schema 建库、写入一条旧数据，再用正式的迁移列表打开：Room 会校验迁移后的表结构和 21 版完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration20To21RoomTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migratesFrom20AndKeepsRows() = runBlocking {
        val name = "migration-20-21.db"
        context.deleteDatabase(name)
        createVersion20(name)

        val db = Room.databaseBuilder(context, AcgCompassDatabase::class.java, name)
            .addMigrations(*AcgMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("旧作品", db.workDao().getById("1")?.canonicalTitle)
            assertEquals(0, db.ratingBackfillDao().count())
            assertEquals(null, db.taskRunDao().get("bangumi_sync"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun createVersion20(name: String) {
        val schema = Json.parseToJsonElement(File(SCHEMA_20).readText()).jsonObject["database"]!!.jsonObject
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
                object : SupportSQLiteOpenHelper.Callback(20) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        statements.forEach(db::execSQL)
                        db.execSQL(
                            "INSERT INTO works (id, canonicalTitle, aliases, mediaType, status, primarySource, " +
                                "createdAt, updatedAt) VALUES ('1', '旧作品', '[]', 'ANIME', 'UNKNOWN', 'BANGUMI', 0, 0)",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()
    }

    private companion object {
        const val SCHEMA_20 = "schemas/com.acgcompass.data.local.AcgCompassDatabase/20.json"
    }
}
