package me.rerere.rikkahub.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.util.KeyCursorStore
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.ProviderKeyCursorStore
import me.rerere.rikkahub.data.ai.RequestLoggingInterceptor
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.RikkaHubAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.KnowledgeBaseFtsManager
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.index.INDEX_DB_NAME
import me.rerere.rikkahub.data.db.index.IndexDatabase
import me.rerere.rikkahub.data.db.index.IndexMigrationManager
import me.rerere.rikkahub.data.db.index.IndexVectorTableManager
import me.rerere.rikkahub.data.db.index.VectorBackendVerifier
import me.rerere.rikkahub.data.db.index.createKnowledgeBaseFtsTable
import me.rerere.rikkahub.data.db.index.objectbox.IndexObjectBoxVectorStore
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_17_18
import me.rerere.rikkahub.data.db.migrations.Migration_18_19
import me.rerere.rikkahub.data.db.migrations.Migration_19_20
import me.rerere.rikkahub.data.db.migrations.Migration_21_22
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_23_24
import me.rerere.rikkahub.data.db.migrations.Migration_24_25
import me.rerere.rikkahub.data.db.migrations.Migration_25_26
import me.rerere.rikkahub.data.db.migrations.Migration_26_27
import me.rerere.rikkahub.data.db.migrations.Migration_6_7
import me.rerere.search.SearchService
import me.rerere.rikkahub.data.sync.S3Sync
import me.rerere.rikkahub.data.sync.webdav.WebDavSync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, AppDatabase::class.java, "rikka_hub")
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_17_18,
                Migration_18_19,
                Migration_19_20,
                Migration_21_22,
                Migration_22_23,
                Migration_23_24,
                Migration_24_25,
                Migration_25_26,
                Migration_26_27,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS message_fts USING fts5(
                            text,
                            node_id UNINDEXED,
                            message_id UNINDEXED,
                            conversation_id UNINDEXED,
                            title UNINDEXED,
                            update_at UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_base_chunk_fts USING fts5(
                            content,
                            assistant_id UNINDEXED,
                            document_id UNINDEXED,
                            chunk_id UNINDEXED,
                            tokenize = 'simple'
                        )
                        """.trimIndent()
                    )
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                        RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                            options.customExtensions.add(
                                SQLiteCustomExtension(
                                    context.applicationInfo.nativeLibraryDir + "/libsimple",
                                    null
                                )
                            )
                            options
                        }
                    )
                )
            )
            .build()
    }

    single {
        val context: Context = get()
        Room.databaseBuilder(context, IndexDatabase::class.java, INDEX_DB_NAME)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    createKnowledgeBaseFtsTable(db)
                }
            })
            .openHelperFactory(
                RequerySQLiteOpenHelperFactory(
                    listOf(
                        RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                            options.customExtensions.add(
                                SQLiteCustomExtension(
                                    context.applicationInfo.nativeLibraryDir + "/libsimple",
                                    null
                                )
                            )
                            options
                        }
                    )
                )
            )
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().messageNodeDao()
    }

    single {
        get<AppDatabase>().managedFileDao()
    }

    single {
        get<AppDatabase>().favoriteDao()
    }

    single {
        get<AppDatabase>().compressionEventDao()
    }

    single {
        get<AppDatabase>().conversationCompressionPayloadDao()
    }

    single {
        get<AppDatabase>().compressionEventPayloadDao()
    }

    single {
        get<AppDatabase>().pendingLedgerBatchDao()
    }

    single {
        get<AppDatabase>().memoryIndexChunkDao()
    }

    single {
        get<AppDatabase>().sourcePreviewChunkDao()
    }

    single {
        get<AppDatabase>().scheduledTaskRunDao()
    }

    single {
        get<AppDatabase>().knowledgeBaseDocumentDao()
    }

    single {
        get<AppDatabase>().knowledgeBaseChunkDao()
    }

    single {
        get<IndexDatabase>().knowledgeBaseChunkDao()
    }

    single {
        get<IndexDatabase>().memoryIndexChunkDao()
    }

    single {
        get<IndexDatabase>().sourcePreviewChunkDao()
    }

    single {
        get<IndexDatabase>().pendingLedgerBatchDao()
    }

    single {
        get<IndexDatabase>().migrationStateDao()
    }

    single {
        IndexObjectBoxVectorStore(get())
    }

    single {
        VectorBackendVerifier(get())
    }

    single {
        IndexVectorTableManager(
            indexDatabase = get(),
            migrationStateDAO = get(),
            vectorStore = get(),
            vectorBackendVerifier = get(),
        )
    }

    single {
        IndexMigrationManager(
            appDatabase = get(),
            indexDatabase = get(),
            migrationStateDAO = get(),
            knowledgeBaseChunkDAO = get(),
            memoryIndexChunkDAO = get(),
            sourcePreviewChunkDAO = get(),
            pendingLedgerBatchDAO = get(),
            vectorTableManager = get(),
        )
    }

    single {
        MessageFtsManager(get())
    }

    single {
        KnowledgeBaseFtsManager(get(), get(), get(), get())
    }

    single { McpManager(settingsStore = get(), appScope = get(), filesManager = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            aiLoggingManager = get()
        )
    }

    single<OkHttpClient> {
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestBuilder = originalRequest.newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)

                if (originalRequest.header(HttpHeaders.UserAgent) == null) {
                    requestBuilder.addHeader(HttpHeaders.UserAgent, "RikkaHub-Android/${BuildConfig.VERSION_NAME}")
                }

                chain.proceed(requestBuilder.build())
            }
            // Strip charset from Content-Type at the network layer so providers that compare the
            // header strictly still receive plain "application/json" after okhttp finalizes it.
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val contentTypeHeader = request.header("Content-Type")
                if (contentTypeHeader != null && contentTypeHeader.contains(";")) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Content-Type", contentTypeHeader.substringBefore(";").trim())
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
            // Logging also needs the finalized network request/response pair, otherwise the
            // interceptor misses what actually reaches the provider after header normalization.
            .addNetworkInterceptor(RequestLoggingInterceptor())
            .addInterceptor(AIRequestInterceptor())
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            })
            .build().also { SearchService.init(it, get()) }
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        ProviderManager(client = get(), keyCursorStore = get())
    }

    single<KeyCursorStore> {
        ProviderKeyCursorStore(context = get())
    }

    single {
        WebDavSync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<HttpClient> {
        HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(20, TimeUnit.SECONDS)
                    readTimeout(10, TimeUnit.MINUTES)
                    writeTimeout(120, TimeUnit.SECONDS)
                    followSslRedirects(true)
                    followRedirects(true)
                    retryOnConnectionFailure(true)
                }
            }
        }
    }

    single {
        S3Sync(
            settingsStore = get(),
            json = get(),
            context = get(),
            httpClient = get()
        )
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<RikkaHubAPI> {
        get<Retrofit>().create(RikkaHubAPI::class.java)
    }
}
