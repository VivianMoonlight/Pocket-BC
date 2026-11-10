package dev.vivian.mobilebc.data.storage

import android.content.Context

class BcPersistenceManager private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    internal val database: BcColdDatabase = createBcColdDatabase(appContext)
    private val stateRepository = DataStoreStateRepository(appContext)
    private val contactProfileRepository = RoomContactProfileRepository(database, database.contactProfileDao())
    private val conversationRepository = RoomConversationRepository(database.conversationDao(), contactProfileRepository)
    private val readCursorRepository = RoomReadCursorRepository(
        database = database,
        dao = database.messageReadCursorDao(),
    )
    private val messageRepository = RoomMessageRepository(
        messageDao = database.messageDao(),
        conversationDao = database.conversationDao(),
        contactProfileRepository = contactProfileRepository,
    )
    private val outboxRepository = RoomOutboxRepository(
        database = database,
        dao = database.outboxDao(),
        messageDao = database.messageDao(),
        messageRepository = messageRepository,
    )
    private val inboundPersistenceRepository = RoomInboundPersistenceStore(
        database = database,
        inboxDao = database.inboxDao(),
        messageRepository = messageRepository,
        messageReadCursorDao = database.messageReadCursorDao(),
        outboxDao = database.outboxDao(),
        syncCursorDao = database.syncCursorDao(),
    )
    /** App-wide/shared storage entry point. */
    val shared: SharedRepositories = SharedRepositories(
        sessionPreferences = StorageBackedBcSessionPreferencesStore(stateRepository),
        deadLetterSink = StorageBackedDeadLetterSink(
            database.deadLetterDao(),
        ),
    )

    /** Runtime-facing domain storage ports. */
    val runtime: RuntimePersistenceStores = RuntimePersistenceStores(
        timeline = StorageBackedBcMessageTimelineStore(
            conversations = conversationRepository,
            messages = messageRepository,
            readCursors = readCursorRepository,
            contactProfiles = contactProfileRepository,
        ),
        outbound = StorageBackedBcOutboundMessageStore(outboxRepository),
        inbound = inboundPersistenceRepository,
        outboxDrain = StorageBackedOutboxDrainStore(outboxRepository),
    )

    companion object {
        @Volatile
        private var instance: BcPersistenceManager? = null

        fun get(context: Context): BcPersistenceManager =
            instance ?: synchronized(this) {
                instance ?: BcPersistenceManager(context.applicationContext).also { instance = it }
            }
    }
}
