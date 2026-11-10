# Storage Architecture

This document records the current storage boundary.

## Public entry points

Production code uses two entry points from `BcPersistenceManager`:

```kotlin
persistence.shared
persistence.runtime
```

`shared` contains app-wide state that is not bound to one BC member. `runtime` exposes domain ports whose operations carry `ownerMemberNumber` explicitly.

## Shared storage

`SharedRepositories` contains:

```text
shared.sessionPreferences
shared.deadLetterSink
```

DataStore repositories for account metadata, credentials, desired session state, user settings, and room targets remain internal behind `sessionPreferences`. `StorageBackedDeadLetterSink` converts diagnostics to persisted records and writes directly to `DeadLetterDao`.

## Runtime storage

`RuntimePersistenceStores` exposes the four ports used by the session runtime:

```text
runtime.timeline
runtime.outbound
runtime.inbound
runtime.outboxDrain
```

The runtime calls these ports with an explicit owner. Storage adapters pass that owner directly to the concrete Room repositories and DAO queries. Outbox and inbound writes retain their transaction and owner validation in the concrete Room implementation.

Room owns the durable message timeline. `BcSessionTimelineController` requires `runtime.timeline` and observes its account-scoped queries. Removing the account emits an empty timeline, while a temporary disconnect preserves access to that account's history. Online room and friend availability are merged with stored conversations for presentation.

Stored conversation metadata and unread counts change through Room observations after a successful write. Advancing a read cursor waits for the persisted cursor before unread state changes. Timeline writes report an account-generation-scoped `persistenceFailure`; chat and conversation screens display it independently of connection status. A failure from a previous account cannot replace the current account's error. Successful operations clear only their matching operation and conversation failure. Observation failures retain the last values and retry cancellably; failed writes remain visible.

Outbound enqueue atomically writes the command and its local timeline row. Incoming messages atomically record the inbox event, timeline row, any read-cursor advance, and matching self-echo confirmation. A wire `MsgId` provides stable deduplication; frames without one use their reception occurrence so repeated text remains a distinct message.

Contact profile updates merge observations with complete character snapshots in a Room transaction. An unknown `#memberNumber` observation preserves a known name. Newer complete snapshots can clear optional profile and relationship fields; older snapshots cannot replace newer profile details.

## Outbox delivery and recovery

The durable command envelope is serialized directly in `data:storage` by `toOutboxPayloadJson` and strictly restored by `toValidatedOutboxDrainMessage`. Existing fields and legacy fixtures remain compatible. Room replies add optional `replyToId` and `replyRoomName` together; restoration rejects incomplete pairs, unsupported transports and conflicting room scope. These fields fence local dispatch, and only ReplyId enters the native Dictionary. Actual `ChatRoomChat` and `AccountBeep` wire serialization belongs to the Razor gateway.

The outbox distinguishes `Pending`, `Sending`, `Submitted`, `Confirmed`, and `Failed`. A successful room-chat submission waits for the server echo before confirmation. If that confirmation expires, the local row becomes a visible failure with unknown delivery; it is not automatically resent because the server may already have received it. A later matching self echo can still replace that row and confirm delivery. Whisper and account-beep submission use their transport-specific confirmation policy in `OutboxDrainCoordinator`.

The durable envelope retains the original command text. Room Chat/Emote/Whisper validation runs before enqueue, and the Razor gateway prepares the actual payload again at final emission using current character effects, private account rules and preferences. Restoring a command therefore does not transform previously transformed text a second time. Map distance and some private gameplay state remain outside the current preparation contract.

`sendPrepared` returns a `BcOutboundDispatchReceipt` containing the emitted content and Dictionary. Storage records that receipt with submission or confirmation so local whispers show the text actually sent. This does not replace the raw durable command, and a server echo remains authoritative when it arrives; a receipt cannot overwrite an already confirmed echo. Room-chat confirmation still follows the existing timeout and late-echo policy.

Private account Log is authentication state rather than a second message timeline. The socket layer preserves the complete source Log array and unknown fields while applying supported OwnerRule mutations, then synchronizes it through the existing AccountUpdate queue. Forbidden-word mute consequences are staged during preparation and committed only after successful emission. Login token and revision fence these updates, including a new login on the same socket; preparing or rejecting an outbound message must not persist a mute consequence. AccountUpdate enqueue is not a server persistence acknowledgement.

AccountUpdate captures its login UUID/member guard when fields are queued and its connection generation before serialization. Debouncing, draining and externally held batches preserve those guards through emission, including a new login for the same account. General relationship events independently carry the receiving account, login UUID and revision; early events merge into their matching login publication through the session layer. Before another AccountLogin on the same connection, the gateway rebuilds the socket so uncorrelated late wire responses cannot enter the new login through the old physical channel. Already queued account updates also retain their original guards. Relationship state and private Log remain authentication state, rather than additional durable timelines.

Sending starts only after an atomic claim succeeds. Each restored command must agree with its stored ID, conversation, and transport; an invalid command becomes a local failure while other queued messages continue. The session dispatch policy defers room chat for another room and whispers whose recipient is no longer present. Deferred commands retain their attempt count. A drain requests subsequent work while commands remain deferred, another batch may be pending, or room-chat confirmation is outstanding.

Room creation and administrator updates use the live session request path, not the durable chat outbox. Their responses have no request ID, so an emitted request with an unknown outcome is not automatically replayed. Authoritative room synchronization establishes success; the session serializes account-scoped recovery-target persistence and isolates stale login or room results. A failed target write is logged and does not terminate the session event subscriber.

Current-room Kick/Ban/Promote/Demote also use the live request path. The session observes room state for at most ten seconds after emission and returns the observed target/role/ban state or Unconfirmed; it does not persist an independently acknowledged command or automatically replay it. `BcMember.roomMemberPresenceEpoch` and nullable `BcRoom.banMemberNumbers` are ephemeral session projections. They add no columns or schema migration.

For native RoomKicked/RoomBanned, an accepted event must match its receive-login, previous room name and previous socket room epoch. The session clears that room's saved autojoin target and marks its conversation as having left the room, while retaining stored messages, mute settings and unread state. Temporary disconnection retains the existing recovery behavior. Events queued with an old receive-login or old room epoch cannot clear the current recovery target. The token itself has no room name or request ID; reception context does not independently prove the original room of a delayed wire token. There is no durable replay of the removal or administrator command.

A failed join can restore the existing room with a new socket epoch, matched through the previous receive-login, room name and room epoch. Restoring the same name preserves the local visit and saved target. If that room was renamed while joining, the authoritative name updates the saved target only when it still points to the old room; a later target chosen by the user is retained. An accepted removal cannot complete a join that has not yet emitted.

An authoritative room rename updates the live selection, navigation, recovery target and chat input state. The old room conversation remains accessible as history. Messages, read cursors and outbox rows are not transactionally migrated to the new room name; queued commands retain their original room scope and are not silently rerouted. This batch adds no schema migration for rename continuity.

## Private wardrobe state

Wardrobe, WardrobeCharacterNames, inventory and Game inputs belong to the authenticated account's canonical private state. The socket and session project them with the receiving member, login UUID and revision; updates received before authentication publication merge only into their matching login. A new login or disconnect clears the previous private inputs. Explicit changes to the current player's public Inventory/Game invalidate the corresponding private input and advance the wardrobe revision, preventing an old private snapshot from authorizing a new action.

`BcWardrobeCodec` reads the supported legacy object arrays and native compact slots. Saving writes `[Name, Group, Color, Property]` tuples as JSON compressed with LZString UTF-16. It preserves non-target slots where their source format can be retained or safely converted; unsafe legacy data blocks the write. A self slot includes the complete Appearance category, including body and hair, removes Expression and does not persist top-level Craft or Difficulty. Native default assets in ArmsLeft/ArmsRight/HandsLeft/HandsRight have a valid empty Name; save and readback preserve it. Names are independent AccountUpdate fields. Slot reordering and arbitrary special extended data are not supported.

Save and rename use a guarded live AccountUpdate emission. Only after actual emission does a compare-and-set update the local canonical snapshot and revision and publish it to the matching session. This is local bookkeeping, not a server persistence acknowledgement. The UI reports that the request was sent; the current AccountUpdate path supplies no durable-storage ACK. Persistence after another login and cross-client readback remain unverified.

Applying a slot uses a guarded ChatRoomCharacterUpdate, then queues AssetFamily/Appearance through the existing AccountUpdate path. The planner retains Item data, current expressions, the ActivePose request and groups that cannot be safely changed; preview and actual emission share the same account/login, room visit, source state, rules and wardrobe revision checks. Room synchronization owns the visible character state. Observing an appearance equal to the emitted plan does not establish request causality or account persistence. Wardrobe commands are not added to the durable chat outbox or automatically replayed after recovery; no wardrobe table or schema migration is introduced.

## Room schema contract

`BcColdDatabase.kt` and `BcColdMigrations.kt` define the maintained schema version and migration chain. Explicit migrations preserve supported schemas from version 9 onward; production database creation does not use destructive fallback.

Migration 12 to 13 adds message resolution metadata. `Plain`, `NeedsTranslation`, `Resolved`, and `Failed` round-trip with their translation key and resolution text independently of display text and the raw packet. Existing rows retain their text and raw data and default to `Plain`; the migration does not guess their previous translation state.

Migration 13 to 14 adds nullable `rawRoomName` to messages. New room messages preserve the room captured when the packet arrived; this separates whispers exchanged in different rooms within one direct conversation. Existing rows retain their data with an unknown room. ReplyId remains in the original Dictionary and is reconstructed when messages are read; no quote body is duplicated or searched across conversations.

Important account-scoped primary keys:

```text
conversations:         ownerMemberNumber + conversationId
messages:              ownerMemberNumber + conversationId + messageId
outbox_messages:       ownerMemberNumber + clientMsgId
inbox_events:          ownerMemberNumber + packetId
sync_cursors:          ownerMemberNumber + conversationId
message_read_cursors:  ownerMemberNumber + conversationId
contact_profiles:      ownerMemberNumber + memberNumber
```

`dead_letters` remains globally keyed by `id` because it stores protocol diagnostics rather than conversation state.

`conversations.unreadCount` remains as a v9 compatibility column but is not the source of truth. Queries derive unread state from visible, non-self messages after the persisted read cursor.

The latest conversation text, timestamp, message ID, and sorting use the same `Visible` and `LocalOnly` rows as the timeline. This makes terminal sending failures appear in conversation summaries. Unread counts continue to include only `Visible` messages from other members.

## DataStore compatibility

State persistence is implemented by focused files under `datastore/`. These persisted keys must remain stable unless an explicit migration is added:

```text
mobile-bc-state
password:$accountName
profilesJson
lastAccountName
desiredOnlineAccountName
autoJoinRoomAfterReconnect
lastRoomTarget:$accountName
```

## File layout

```text
data/storage/src/main/kotlin/dev/vivian/mobilebc/data/storage/
├── BcPersistenceManager.kt
├── SharedRepositories.kt
├── RuntimePersistenceStores.kt
├── db/
├── mapper/
├── model/
├── repository/
└── datastore/
```

## Validation commands

Run the storage tests and app build after storage changes. Database schema versions and migrations are defined by the code and checked by migration tests. Device, online-server and persistence-after-login behavior require separate validation.

```powershell
.\gradlew.bat :data:storage:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```
