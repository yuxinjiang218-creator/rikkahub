package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.KnowledgeBaseRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryIndexRepository
import me.rerere.rikkahub.data.repository.PendingLedgerBatchRepository
import me.rerere.rikkahub.data.repository.ScheduledTaskRunRepository
import me.rerere.rikkahub.data.repository.SourcePreviewRepository
import me.rerere.rikkahub.data.skills.SkillsRepository
import org.koin.dsl.module

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        PendingLedgerBatchRepository(get(), get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        MemoryIndexRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        SourcePreviewRepository(get(), get(), get(), get(), get())
    }

    single {
        ScheduledTaskRunRepository(get())
    }

    single {
        KnowledgeBaseRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        FilesManager(get(), get(), get())
    }

    single {
        SkillManager(get(), get())
    }

    single {
        SkillsRepository(
            context = get(),
            appScope = get(),
            skillManager = get(),
        )
    }
}
