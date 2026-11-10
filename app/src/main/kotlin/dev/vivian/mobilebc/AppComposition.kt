package dev.vivian.mobilebc

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import dev.vivian.mobilebc.domain.bcrules.BcCachedRuleCatalogRepository
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcCachedInteractionCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcCachedRoomBackgroundCatalogRepository
import dev.vivian.mobilebc.platform.text.AndroidBcTextResourceReader
import dev.vivian.mobilebc.i18n.AppLanguageManager
import dev.vivian.mobilebc.resource.AndroidBcWebResourceFiles
import dev.vivian.mobilebc.resource.AndroidBcWebResourceStore
import dev.vivian.mobilebc.resource.BcWebResourceRepository
import dev.vivian.mobilebc.resource.BcWebResourceSnapshot
import dev.vivian.mobilebc.resource.BcWebResourceSyncer
import dev.vivian.mobilebc.resource.OkHttpBcWebResourceFetcher
import dev.vivian.mobilebc.resource.bcTranslationResourcePaths
import dev.vivian.mobilebc.resource.normalizedBcTranslationLanguage
import dev.vivian.mobilebc.service.BcInboundPersistenceRetrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async

/**
 * Process-wide composition root.
 *
 * Android entry points may access this object, but business rules and active-session state remain in
 * their owning domain/runtime components.
 */
class AppComposition private constructor(
    private val applicationContext: Context,
    private val echoData: dev.vivian.mobilebc.portrait.EchoDataUpdates,
    private val bcWebResources: BcWebResourceRepository,
    private val textResourceReader: AndroidBcTextResourceReader,
    private val interactionCatalogRepository: BcInteractionCatalogRepository,
    private val ruleCatalogRepository: BcRuleCatalogRepository,
    private val roomBackgroundCatalogRepository: BcCachedRoomBackgroundCatalogRepository,
) {
    internal val portraitResources by lazy {
        dev.vivian.mobilebc.portrait.BcPortraitResources(applicationContext) { echoData.snapshot }
    }

    internal val portraitWebViews by lazy {
        dev.vivian.mobilebc.portrait.BcPortraitWebViews(applicationContext, portraitResources)
    }

    internal val portraitFrames by lazy {
        dev.vivian.mobilebc.portrait.PortraitFrames(java.io.File(applicationContext.filesDir, "portrait-frames-v1"))
    }

    internal val portraitRenderingVersion = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
        portraitResources.renderingVersion
    }

    internal val inboundPersistenceRetrier = BcInboundPersistenceRetrier(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onFailure = { frame, error ->
            Log.e("BcInboundPersistence", "Retaining ${frame.transportEventName} until storage recovers", error)
        },
    )

    internal fun readBcTextResource(version: String, relativePath: String): String? =
        textResourceReader.readText(version, relativePath)

    internal suspend fun ensureBcWebResources(version: String): Unit {
        bcWebResources.ensureSynced(version)
        withContext(Dispatchers.IO) { roomBackgroundCatalogRepository.refresh(version) }
    }

    internal fun currentRoomBackgroundNames(version: String): Set<String>? =
        roomBackgroundCatalogRepository.currentSnapshot(version)?.allNames

    internal fun hasReadyBcWebResources(version: String, language: String): Boolean =
        bcWebResources.currentSnapshot(version).matchesBcTextLanguage(language)

    internal fun createMobileBcAppViewModel(
        savedStateHandle: SavedStateHandle,
    ): MobileBcAppViewModel =
        MobileBcAppViewModel(
            savedStateHandle = savedStateHandle,
            bcWebResources = bcWebResources,
            interactionCatalogRepository = interactionCatalogRepository,
            ruleCatalogRepository = ruleCatalogRepository,
            roomBackgroundCatalogRepository = roomBackgroundCatalogRepository,
        )

    companion object {
        fun create(context: Context, languageManager: AppLanguageManager): AppComposition {
            val echoData = dev.vivian.mobilebc.portrait.EchoDataUpdates(context)
            val textResourceReader = AndroidBcTextResourceReader(context) { echoData.snapshot.names }
            val bcWebResources = BcWebResourceSyncer(
                fetcher = OkHttpBcWebResourceFetcher(),
                store = AndroidBcWebResourceStore(context),
                translationLanguageProvider = languageManager::currentBcLanguageCode,
            )
            val interactionCatalogRepository = BcCachedInteractionCatalogRepository(
                resourceReader = textResourceReader::readText,
                languageProvider = languageManager::currentBcLanguageCode,
            )
            val ruleCatalogRepository = BcCachedRuleCatalogRepository(
                resourceReader = textResourceReader::readText,
                languageProvider = languageManager::currentBcLanguageCode,
            )
            val roomBackgroundCatalogRepository = BcCachedRoomBackgroundCatalogRepository(
                resourceReader = textResourceReader::readText,
            )

            return AppComposition(
                applicationContext = context.applicationContext,
                echoData = echoData,
                bcWebResources = bcWebResources,
                textResourceReader = textResourceReader,
                interactionCatalogRepository = interactionCatalogRepository,
                ruleCatalogRepository = ruleCatalogRepository,
                roomBackgroundCatalogRepository = roomBackgroundCatalogRepository,
            )
        }
    }
}

internal fun BcWebResourceSnapshot?.matchesBcTextLanguage(language: String): Boolean {
    val expectedLanguage = language.normalizedBcTranslationLanguage()
    val expectedTranslationPaths = bcTranslationResourcePaths(expectedLanguage)
    return this != null &&
        translationLanguage == expectedLanguage &&
        translationResourcePaths == expectedTranslationPaths &&
        resourceCount >= AndroidBcWebResourceFiles.requiredResourcePaths.size + expectedTranslationPaths.size &&
        totalBytes > 0L
}
