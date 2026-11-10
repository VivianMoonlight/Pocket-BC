package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementOperation
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionContext
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRoomContext
import dev.vivian.mobilebc.domain.bcpermission.DefaultBcItemManagementQuery
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemOptionRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDetailsItemPickerControllerTest {
    @Test fun textEditEntryForModularItemsDoesNotInheritTheDisabledSelectedMode() {
        val modular = BcExtendedItemRule("ItemArms","HempRope",BcExtendedItemArchetype.Modular,"modular",
            modules=listOf(dev.vivian.mobilebc.domain.bcrules.BcExtendedItemModuleRule("Tag","t",listOf(
                dev.vivian.mobilebc.domain.bcrules.BcExtendedItemModuleOptionRule("t0",0,propertyJson="""{"TypeRecord":{"t":0}}"""),
                dev.vivian.mobilebc.domain.bcrules.BcExtendedItemModuleOptionRule("t1",1,propertyJson="""{"TypeRecord":{"t":1}}""")))),
            textFields=mapOf("Text" to 9))
        val rules = rules().copy(extendedItems=mapOf(BcAssetKey(modular.group,modular.name) to modular))
        val item = BcCharacterItem(modular.group,modular.name,propertyJson="""{"TypeRecord":{"t":0},"Text":"测试"}""")
        val target = character(20,appearance=listOf(item))
        val context = BcPermissionContext(character(10,true),target,BcPermissionRoomContext(memberNumbers=setOf(10,20)),rules,serverAllowItem=true)
        val selection = RoomInteractionSelectionState().apply { open(InteractionMode.ItemPicker,20);selectGroup(modular.group) }
        val state = RoomDetailsItemPickerController(DefaultBcItemManagementQuery()).buildUiState(selection,target,
            InteractionGroupUiState(modular.group,"Arms",false,true,true),rules,context,"R133")
        assertTrue(state.currentItemConfigurable)
        assertEquals(mapOf("Text" to 9),state.currentItemTextFields)
        assertEquals(mapOf("Text" to "测试"),state.currentItemTextValues)
        assertTrue(state.currentItemTextEditEntry!!.enabled)
        assertEquals(null,state.currentItemTextEditEntry.candidate)
    }

    @Test fun nativeHighSecurityKeysHaveAnEditorAndHeartLocksDoNot() {
        val base = rules()
        val rules = base.copy(assets = base.assets + mapOf(
            BcAssetKey("ItemArms", "HempRope") to base.asset("ItemArms", "HempRope")!!.copy(allowLock = true),
            BcAssetKey("ItemMisc", "HighSecurityPadlock") to BcAssetRule("ItemMisc", "HighSecurityPadlock", isLock = true, exclusiveUnlock = true)))
        val item = BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"LockedBy":"HighSecurityPadlock","LockMemberNumber":30,"MemberNumberListKeys":"10,30","Effect":["Lock"]}""")
        val target = character(20, appearance = listOf(item))
        val context = BcPermissionContext(character(10, true), target, BcPermissionRoomContext(memberNumbers=setOf(10,20)), rules, serverAllowItem=true,
            itemPrivateState=dev.vivian.mobilebc.core.model.BcItemPermissionPrivateState(dev.vivian.mobilebc.core.model.BcWardrobeState(10,"login",1,null,null,emptyList(),false,false),emptyList()))
        val selection = RoomInteractionSelectionState().apply { open(InteractionMode.ItemPicker,20); selectGroup("ItemArms") }
        fun state(context: BcPermissionContext) = RoomDetailsItemPickerController(DefaultBcItemManagementQuery()).buildUiState(selection,context.target,
            InteractionGroupUiState("ItemArms","Arms",false,true,true),rules,context,"R133")
        val native = state(context)
        assertEquals(dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfigurationMode.UpdateKeyholders,native.currentItemLockConfigurationMode)
        assertEquals("10,30",native.currentItemKeyholders)
        assertTrue(native.currentItemLockConfigurationEntry!!.enabled)
        assertEquals(null,native.currentItemLockConfigurationEntry.candidate)
        val afc = item.copy(propertyJson=dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson.overlayStrings(item.propertyJson,
            mapOf("Name" to dev.vivian.mobilebc.domain.bcitemproperty.BcAfcHeartLock.Name,"HeartLockId" to "id-1")))
        val heart = state(context.copy(target=target.copy(appearance=listOf(afc))))
        assertTrue(heart.currentItemIsAfcHeartLock)
        assertEquals(null,heart.currentItemLockConfigurationMode)
        assertEquals(null,heart.currentItemLockConfigurationEntry)
        assertEquals("",heart.currentItemKeyholders)
    }

    @Test fun hiddenTimerAllowsOnePublicInputAndBoundInspectionWithoutExposingTime() {
        val base = rules()
        val rules = base.copy(assets = base.assets + mapOf(
            BcAssetKey("ItemArms", "HempRope") to base.asset("ItemArms", "HempRope")!!.copy(allowLock = true),
            BcAssetKey("ItemMisc", "OwnerTimerPadlock") to BcAssetRule("ItemMisc", "OwnerTimerPadlock", isLock = true, ownerOnly = true, removeTimerSeconds = 300, maxTimerSeconds = 3600)))
        val item = BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"LockedBy":"OwnerTimerPadlock","LockMemberNumber":30,"RemoveTimer":10000,"ShowTimer":false,"EnableRandomInput":true,"MemberNumberList":[]}""")
        val target = character(20, appearance = listOf(item)).copy(ownerMemberNumber = 30)
        val context = BcPermissionContext(character(10, true), target, BcPermissionRoomContext(memberNumbers = setOf(10, 20)), rules,
            serverAllowItem = true, serverTimeAvailable = true, serverTimeMillis = 1000)
        val selection = RoomInteractionSelectionState().apply { open(InteractionMode.ItemPicker, 20); selectGroup("ItemArms") }
        val controller = RoomDetailsItemPickerController(DefaultBcItemManagementQuery())
        fun state(context: BcPermissionContext) = controller.buildUiState(selection, target, InteractionGroupUiState("ItemArms", "Arms", false, true, true), rules, context, "R133").currentItemTimer!!
        val timer = state(context)
        assertTrue(timer.canOpen)
        assertTrue(timer.limited!!.enabled)
        assertEquals(null, timer.deadlineMillis)
        assertEquals(null, timer.limited.candidate)
        val bound = context.actor.copy(appearance = listOf(BcCharacterItem("ItemHands", "Mittens", propertyJson = """{"Effect":["Block"]}""")))
        assertTrue(state(context.copy(actor = bound)).canOpen)
        assertFalse(state(context.copy(actor = bound)).limited!!.enabled)
        val blind = context.actor.copy(appearance = listOf(BcCharacterItem("ItemHead", "Hood", propertyJson = """{"Effect":["BlindHeavy"]}""")))
        assertFalse(state(context.copy(actor = blind)).canOpen)
    }

    @Test fun combinationLockRemainsAvailableWithBlindClumsyInputAndOffersCodeChanges() {
        val base = rules()
        val rules = base.copy(assets = base.assets + mapOf(
            BcAssetKey("ItemMisc", "CombinationPadlock") to BcAssetRule("ItemMisc", "CombinationPadlock", isLock = true),
            BcAssetKey("ItemArms", "HempRope") to base.asset("ItemArms", "HempRope")!!.copy(allowLock = true),
            BcAssetKey("ItemHands", "Mittens") to BcAssetRule("ItemHands", "Mittens", effects = setOf("Block", "BlindHeavy"), isRestraint = true)))
        val item = BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"LockedBy":"CombinationPadlock","CombinationNumber":"0123","Effect":["Lock"]}""")
        val target = character(2, appearance = listOf(item))
        val actor = character(1, true, appearance = listOf(BcCharacterItem("ItemHands", "Mittens")))
        val context = BcPermissionContext(actor, target, BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules, serverAllowItem = true)
        val selection = RoomInteractionSelectionState().apply { open(InteractionMode.ItemPicker, 2); selectGroup("ItemArms") }
        val state = RoomDetailsItemPickerController(DefaultBcItemManagementQuery()).buildUiState(selection, target,
            InteractionGroupUiState("ItemArms", "Arms", false, true, true), rules, context, "R133")
        assertTrue(state.lockInputConditions.blind)
        assertEquals(4, state.lockInputConditions.clumsiness)
        assertTrue(state.currentItemUnlockEntry!!.enabled)
        assertTrue(state.currentItemUnlockEntry.requiresUnlockInput)
        assertTrue(state.currentItemLockConfigurationEntry!!.enabled)
        assertEquals(null, state.currentItemLockConfigurationEntry.candidate)
        assertFalse(state.toString().contains("0123"))
    }

    @Test fun passwordLockProjectsHintAndInputAvailabilityWithoutAStoredPasswordOrCommand() {
        val base = rules()
        val rules = base.copy(assets = base.assets + (BcAssetKey("ItemMisc", "PasswordPadlock") to BcAssetRule("ItemMisc", "PasswordPadlock", isLock = true)))
        val item = BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"LockedBy":"PasswordPadlock","Password":"SECRET","Hint":"提示","RemoveOnUnlock":true,"Effect":["Lock"]}""")
        val target = character(2, appearance = listOf(item))
        val context = BcPermissionContext(character(1, true), target, BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules, serverAllowItem = true)
        val selection = RoomInteractionSelectionState().apply { open(InteractionMode.ItemPicker, 2); selectGroup("ItemArms") }
        val state = RoomDetailsItemPickerController(DefaultBcItemManagementQuery()).buildUiState(selection, target,
            InteractionGroupUiState("ItemArms", "Arms", false, true, true), rules, context, "R133")
        assertEquals("提示", state.currentItemLockHint)
        assertEquals(dev.vivian.mobilebc.domain.bcitemproperty.BcLockInputKind.Password, state.currentItemLockInputKind)
        assertTrue(state.currentItemRemoveOnUnlock)
        assertTrue(state.currentItemUnlockEntry!!.enabled)
        assertTrue(state.currentItemUnlockEntry.requiresUnlockInput)
        assertEquals(null, state.currentItemUnlockEntry.candidate)
        assertFalse(state.toString().contains("SECRET"))
    }

    @Test fun textItemsExposeDeclaredFieldsAndPermissionCheckedEditing() {
        val rules = rules().copy(extendedItems = mapOf(BcAssetKey("ItemArms", "HempRope") to BcExtendedItemRule(
            "ItemArms", "HempRope", BcExtendedItemArchetype.Text, "text", defaultPropertyJson = """{"Text":"","Text2":""}""",
            textFields = mapOf("Text" to 12, "Text2" to 12))))
        val item = BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"Text":"","Text2":"第二行"}""")
        val target = character(2, appearance = listOf(item))
        val context = BcPermissionContext(character(1, true), target, BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules, serverAllowItem = true)
        val selection = RoomInteractionSelectionState().apply { open(InteractionMode.ItemPicker, 2); selectGroup("ItemArms") }
        val controller = RoomDetailsItemPickerController(DefaultBcItemManagementQuery())
        val state = controller.buildUiState(selection, target, InteractionGroupUiState("ItemArms", "Arms", false, true, true), rules, context, "R133")
        assertEquals(mapOf("Text" to 12, "Text2" to 12), state.currentItemTextFields)
        assertEquals(mapOf("Text" to "", "Text2" to "第二行"), state.currentItemTextValues)
        assertTrue(state.currentItemTextEditEntry!!.enabled)
        assertTrue(state.currentItemTextEditEntry.requiresTextInput)
        assertEquals(null, state.currentItemTextEditEntry.candidate)
        val invalidText = target.copy(appearance = listOf(item.copy(propertyJson = """{"Text":"😀","Text2":"第二行"}""")))
        val repair = controller.buildUiState(selection, invalidText, InteractionGroupUiState("ItemArms", "Arms", false, true, true), rules, context.copy(target = invalidText), "R133")
        assertTrue(repair.currentItemTextEditEntry!!.enabled)
        assertEquals("😀", repair.currentItemTextValues["Text"])
        val locked = target.copy(appearance = listOf(item.copy(propertyJson = """{"Text":"","Text2":"第二行","LockedBy":"HighSecurityPadlock"}""")))
        val lockedState = controller.buildUiState(selection, locked, InteractionGroupUiState("ItemArms", "Arms", false, true, true), rules, context.copy(target = locked), "R133")
        assertFalse(lockedState.currentItemTextEditEntry!!.enabled)
    }

    @Test
    fun currentCardIdentifiesAfcHeartLockAndKeepsCustomText() {
        val item = BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"LockedBy":"HighSecurityPadlock","Name":"Heart Padlock","HeartLockId":"id","LockMemberNumber":1,"Text":"中文😀"}""",
            craftJson = """{"Name":"定制绳","Description":"说明"}""")
        val target = character(2, appearance = listOf(item))
        val rules = rules()
        val context = BcPermissionContext(character(1, true), target, BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules, serverAllowItem = true)
        val selection = RoomInteractionSelectionState().apply { open(InteractionMode.ItemPicker, 2); selectGroup("ItemArms") }
        val state = RoomDetailsItemPickerController(DefaultBcItemManagementQuery()).buildUiState(selection, target,
            InteractionGroupUiState("ItemArms", "Arms", false, true, true), rules, context, "R129")
        assertTrue(state.currentItemIsAfcHeartLock)
        assertEquals(1, state.currentItemLockOwnerNumber)
        assertEquals(listOf("中文😀"), state.currentItemTextLines)
        assertEquals("说明", state.currentItemDescription)
        assertFalse(state.currentItemCanLock)
        assertTrue(state.currentItemUnlockEntry!!.enabled)
        assertFalse(state.currentItemRemoveEntry!!.enabled)
    }

    @Test
    fun lockingUsesTheLockCatalogAndRetainsTheWornItemAndSearchOnReturn() {
        val base = rules()
        val rules = base.copy(assets = base.assets + mapOf(
            BcAssetKey("ItemArms", "HempRope") to base.asset("ItemArms", "HempRope")!!.copy(allowLock = true),
            BcAssetKey("ItemMisc", "MetalPadlock") to BcAssetRule("ItemMisc", "MetalPadlock", isLock = true, value = 0.0)))
        val item = BcCharacterItem("ItemArms", "HempRope")
        val target = character(2, appearance = listOf(item))
        val privateState = dev.vivian.mobilebc.core.model.BcItemPermissionPrivateState(
            dev.vivian.mobilebc.core.model.BcWardrobeState(1, "login", 1, null, null, emptyList(), false, false), emptyList())
        val context = BcPermissionContext(character(1, isSelf = true), target,
            BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules, serverAllowItem = true, itemPrivateState = privateState)
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = 2)
            selectGroup("ItemArms")
            updateItemPickerQuery("绳")
            selectItemPickerOperation(BcItemManagementOperation.Lock)
            updateItemPickerQuery("金属")
        }
        val controller = RoomDetailsItemPickerController(DefaultBcItemManagementQuery(), catalogProvider = {
            dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot("R129", 0L, emptyList(), emptyList(),
                listOf(dev.vivian.mobilebc.core.model.BcInteractionItem("ItemMisc", "MetalPadlock", "金属挂锁")), emptyList())
        })
        val group = InteractionGroupUiState("ItemArms", "Arms", false, true, true)
        val state = controller.buildUiState(selection, target, group, rules, context, "R129")
        assertTrue(state.lockingCurrentItem && state.currentItemCanLock)
        assertEquals("金属挂锁", state.entries.single().label)
        assertTrue(state.entries.single().enabled)
        assertEquals("HempRope", state.currentItemName)
        selection.returnToItemPickerCatalog(BcItemManagementOperation.Replace)
        assertEquals("绳", selection.itemPickerQuery)
        val locked = target.copy(appearance = listOf(item.copy(propertyJson = """{"LockedBy":"MetalPadlock","Effect":["Lock"]}""")))
        val refreshed = controller.buildUiState(selection, locked, group, rules, context.copy(target = locked), "R129")
        assertFalse(refreshed.currentItemCanLock)
        assertEquals("Unlock", refreshed.currentItemUnlockEntry?.operation)
    }

    @Test
    fun craftedTextUsesLocalizedBaseNameAndRefreshesWithoutChangingTheItemId() {
        val rules = rules()
        val item = BcCharacterItem("ItemArms", "HempRope", craftJson = """{"Name":"定制绳","Description":"专属\n备注"}""",
            propertyJson = """{"Text2":"第二行","Text":"你好🔒"}""")
        val target = character(2, appearance = listOf(item))
        val context = BcPermissionContext(character(1, isSelf = true), target,
            BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules, serverAllowItem = true)
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = 2)
            selectGroup("ItemArms")
        }
        val group = InteractionGroupUiState("ItemArms", "Arms", false, true, true)
        val controller = RoomDetailsItemPickerController(DefaultBcItemManagementQuery(), catalogProvider = {
            dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot("R129", 0L, emptyList(), emptyList(),
                listOf(dev.vivian.mobilebc.core.model.BcInteractionItem("ItemArms", "HempRope", "麻绳")), emptyList())
        })
        val state = controller.buildUiState(selection, target, group, rules, context, "R129")
        assertEquals("定制绳 (麻绳)", state.currentItemDisplayName)
        assertEquals("HempRope", state.currentItemName)
        assertEquals("专属\n备注", state.currentItemDescription)
        assertEquals(listOf("你好🔒", "第二行"), state.currentItemTextLines)
        val changed = target.copy(appearance = listOf(item.copy(craftJson = null, propertyJson = "{}")))
        val refreshed = controller.buildUiState(selection, changed, group, rules, context.copy(target = changed), "R129")
        assertEquals("麻绳", refreshed.currentItemDisplayName)
        assertEquals(null, refreshed.currentItemDescription)
        assertTrue(refreshed.currentItemTextLines.isEmpty())
    }

    @Test
    fun unlockEntryDisappearsAfterAuthoritativeUnlockAndPreservesItemSelection() {
        val rules = rules()
        val target = character(2).copy(appearance = listOf(BcCharacterItem("ItemArms", "HempRope",
            propertyJson = """{"LockedBy":"ExclusivePadlock","Effect":["Lock"]}""")))
        val context = BcPermissionContext(character(1, isSelf = true), target,
            BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules, serverAllowItem = true)
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = 2)
            selectGroup("ItemArms")
        }
        val group = InteractionGroupUiState("ItemArms", "Arms", false, true, true)
        val controller = RoomDetailsItemPickerController(DefaultBcItemManagementQuery())
        val locked = controller.buildUiState(selection, target, group, rules, context, "R129")
        assertEquals("Unlock", locked.currentItemUnlockEntry?.operation)
        assertEquals(true, locked.currentItemUnlockEntry?.enabled)
        assertTrue(selection.updateAfterSuccessfulItemSend(BcItemManagementOperation.Unlock))
        assertEquals(BcItemManagementOperation.Replace, selection.itemPickerOperation)
        val unlocked = target.copy(appearance = listOf(target.appearance.single().copy(propertyJson = "{}")))
        val updated = controller.buildUiState(selection, unlocked, group, rules, context.copy(target = unlocked), "R129")
        assertEquals(null, updated.currentItemUnlockEntry)
        assertEquals("HempRope", updated.currentItemName)
    }

    @Test
    fun translatedNamesRefreshCachedRowsWithoutChangingSelectionIds() {
        val target = character(2)
        val rules = rules()
        val context = BcPermissionContext(
            actor = character(1, isSelf = true), target = target,
            room = BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules = rules,
            serverAllowItem = true,
        )
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = 2)
            selectGroup("ItemArms")
        }
        val group = InteractionGroupUiState("ItemArms", "Arms", false, true, true)
        var label = "Hemp Rope"
        val controller = RoomDetailsItemPickerController(DefaultBcItemManagementQuery(), catalogProvider = {
            dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot(
                "R129", 0L, emptyList(), emptyList(),
                listOf(dev.vivian.mobilebc.core.model.BcInteractionItem("ItemArms", "HempRope", label)), emptyList(),
            )
        })
        val initial = controller.buildUiState(selection, target, group, rules, context, "R129")
        assertEquals("Hemp Rope", initial.entries.single { it.itemName == "HempRope" }.label)
        label = "麻绳"
        val refreshed = controller.buildUiState(selection, target, group, rules, context, "R129")
        assertEquals("麻绳", refreshed.entries.single { it.itemName == "HempRope" }.label)
        assertEquals(initial.entries.map { it.itemName }, refreshed.entries.map { it.itemName })
    }

    @Test
    fun propertyChangesRefreshTheSelectedOptionForTheSameItem() {
        val options = listOf("Loose", "Tight").mapIndexed { index, name ->
            BcExtendedItemOptionRule(name, index, propertyJson = """{"TypeRecord":{"typed":$index}}""")
        }
        val rules = rules().copy(
            extendedItems = mapOf(
                BcAssetKey("ItemArms", "Ribbons") to BcExtendedItemRule(
                    "ItemArms", "Ribbons", BcExtendedItemArchetype.Typed, "typed", typedOptions = options,
                ),
            ),
        )
        val target = character(
            memberNumber = 2,
            appearance = listOf(BcCharacterItem("ItemArms", "Ribbons", propertyJson = options[0].propertyJson)),
        )
        val context = BcPermissionContext(
            actor = character(1, isSelf = true),
            target = target,
            room = BcPermissionRoomContext(memberNumbers = setOf(1, 2)),
            rules = rules,
            serverAllowItem = true,
        )
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = 2)
            selectGroup("ItemArms")
            selectItemPickerOperation(BcItemManagementOperation.Modify)
        }
        val group = InteractionGroupUiState("ItemArms", "Arms item", false, true, true)
        val controller = RoomDetailsItemPickerController(DefaultBcItemManagementQuery())
        val initial = controller.buildUiState(selection, target, group, rules, context, "R129")
        assertEquals("Loose", initial.entries.single { it.selected }.label)

        val changed = target.copy(appearance = listOf(target.appearance.single().copy(propertyJson = options[1].propertyJson)))
        val refreshed = controller.buildUiState(selection, changed, group, rules, context.copy(target = changed), "R129")

        assertEquals("Tight", refreshed.entries.single { it.selected }.label)
        assertTrue(refreshed.entries.single { it.label == "Loose" }.enabled)
    }

    @Test
    fun relationshipPermissionChangesRefreshCachedEntryAvailability() {
        val target = character(2).copy(allowedInteractions = 3, whiteList = listOf(1))
        val rules = rules()
        val context = BcPermissionContext(
            actor = character(1, isSelf = true),
            target = target,
            room = BcPermissionRoomContext(memberNumbers = setOf(1, 2)),
            rules = rules,
        )
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = 2)
            selectGroup("ItemArms")
            selectItemPickerOperation(BcItemManagementOperation.Wear)
            toggleItemPickerUnavailable()
        }
        val group = InteractionGroupUiState("ItemArms", "Arms item", false, true, false)
        val controller = RoomDetailsItemPickerController(DefaultBcItemManagementQuery())
        val initial = controller.buildUiState(selection, target, group, rules, context, "R129")
        assertTrue(initial.entries.all { it.enabled })

        val changed = target.copy(whiteList = emptyList())
        val refreshed = controller.buildUiState(selection, changed, group, rules, context.copy(target = changed), "R129")

        assertEquals(initial.entries.map { it.itemName }, refreshed.entries.map { it.itemName })
        assertTrue(refreshed.entries.all { !it.enabled && it.permissionMessage != null })
    }

    @Test
    fun pagesEntriesAndInvalidatesCacheWhenTheSelectionChanges() {
        val target = character(memberNumber = 2)
        val rules = rules()
        val context = BcPermissionContext(
            actor = character(memberNumber = 1, isSelf = true),
            target = target,
            room = BcPermissionRoomContext(memberNumbers = setOf(1, 2)),
            rules = rules,
            serverAllowItem = true,
        )
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = target.memberNumber)
            selectGroup("ItemArms")
            selectItemPickerOperation(BcItemManagementOperation.Wear)
        }
        val group = InteractionGroupUiState(
            name = "ItemArms",
            displayName = "Arms item",
            hasActivities = false,
            hasItems = true,
            hasCurrentItem = false,
        )
        val controller = RoomDetailsItemPickerController(
            itemManagementQuery = DefaultBcItemManagementQuery(),
            pageSize = 1,
        )

        val firstPage = controller.buildUiState(
            selection = selection,
            target = target,
            selectedGroup = group,
            rules = rules,
            permissionContext = context,
            version = "R129",
        )

        assertEquals(listOf("HempRope"), firstPage.entries.map { it.itemName })
        assertEquals(1, firstPage.loadedCount)
        assertTrue(firstPage.hasMore)

        assertTrue(
            controller.loadNext(
                selection = selection,
                target = target,
                group = group.name,
                version = "R129",
                context = context,
                rules = rules,
            ),
        )
        val secondPage = controller.buildUiState(
            selection = selection,
            target = target,
            selectedGroup = group,
            rules = rules,
            permissionContext = context,
            version = "R129",
        )
        assertEquals(listOf("HempRope", "LeatherCuffs"), secondPage.entries.map { it.itemName })
        assertEquals(2, secondPage.loadedCount)

        selection.updateItemPickerQuery("ribbons")
        val filtered = controller.buildUiState(
            selection = selection,
            target = target,
            selectedGroup = group,
            rules = rules,
            permissionContext = context,
            version = "R129",
        )
        assertEquals(listOf("Ribbons"), filtered.entries.map { it.itemName })
        assertFalse(filtered.hasMore)

        selection.updateItemPickerQuery("")
        val newVersion = controller.buildUiState(
            selection = selection,
            target = target,
            selectedGroup = group,
            rules = rules,
            permissionContext = context,
            version = "R130",
        )
        assertEquals(listOf("HempRope"), newVersion.entries.map { it.itemName })

        controller.clear()
        val afterClear = controller.buildUiState(
            selection = selection,
            target = target,
            selectedGroup = group,
            rules = rules,
            permissionContext = context,
            version = "R130",
        )
        assertEquals(1, afterClear.loadedCount)
        assertEquals(listOf("HempRope"), afterClear.entries.map { it.itemName })
    }

    @Test
    fun clothingGroupsInferWardrobeCatalogOperation() {
        val target = character(memberNumber = 2)
        val rules = BcRuleCatalog(
            version = "R129",
            groups = mapOf(
                "Cloth" to BcGroupRule(
                    name = "Cloth",
                    category = "Appearance",
                    clothing = true,
                    isItem = false,
                ),
            ),
            activities = emptyMap(),
            assets = mapOf(
                BcAssetKey("Cloth", "SummerDress") to BcAssetRule("Cloth", "SummerDress"),
            ),
        )
        val context = BcPermissionContext(
            actor = character(memberNumber = 1, isSelf = true),
            target = target,
            room = BcPermissionRoomContext(memberNumbers = setOf(1, 2)),
            rules = rules,
            serverAllowItem = true,
        )
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = target.memberNumber)
            selectGroup("Cloth")
        }
        val group = InteractionGroupUiState(
            name = "Cloth",
            displayName = "Clothes",
            hasActivities = false,
            hasItems = true,
            hasCurrentItem = false,
            kind = BcAppearanceGroupKind.Clothing,
        )

        val state = RoomDetailsItemPickerController(DefaultBcItemManagementQuery()).buildUiState(
            selection = selection,
            target = target,
            selectedGroup = group,
            rules = rules,
            permissionContext = context,
            version = "R129",
        )

        assertEquals(InteractionItemPickerStep.Item, state.step)
        assertEquals("Wear", state.selectedOperation?.name)
        assertEquals(listOf("SummerDress"), state.entries.map { it.itemName })
        assertEquals(null, state.currentItemName)
    }

    @Test
    fun occupiedSlotInfersReplaceAndExposesRemovalOnCurrentCard() {
        val target = character(
            memberNumber = 2,
            appearance = listOf(BcCharacterItem("ItemArms", "HempRope")),
        )
        val rules = rules()
        val context = BcPermissionContext(
            actor = character(memberNumber = 1, isSelf = true),
            target = target,
            room = BcPermissionRoomContext(memberNumbers = setOf(1, 2)),
            rules = rules,
            serverAllowItem = true,
        )
        val selection = RoomInteractionSelectionState().apply {
            open(InteractionMode.ItemPicker, targetMemberNumber = target.memberNumber)
            selectGroup("ItemArms")
        }
        val state = RoomDetailsItemPickerController(DefaultBcItemManagementQuery()).buildUiState(
            selection = selection,
            target = target,
            selectedGroup = InteractionGroupUiState(
                name = "ItemArms",
                displayName = "Arms item",
                hasActivities = false,
                hasItems = true,
                hasCurrentItem = true,
            ),
            rules = rules,
            permissionContext = context,
            version = "R129",
        )

        assertEquals("Replace", state.selectedOperation?.name)
        assertEquals("HempRope", state.currentItemName)
        assertEquals("Remove", state.currentItemRemoveEntry?.operation)
        assertTrue(state.entries.none { it.itemName == "HempRope" })
    }

    private fun character(
        memberNumber: Int,
        isSelf: Boolean = false,
        appearance: List<BcCharacterItem> = emptyList(),
    ): BcCharacterSnapshot = BcCharacterSnapshot(
        id = memberNumber.toString(),
        memberNumber = memberNumber,
        name = "C$memberNumber",
        displayName = "C$memberNumber",
        isSelf = isSelf,
        appearance = appearance,
    )

    private fun rules(): BcRuleCatalog = BcRuleCatalog(
        version = "R129",
        groups = mapOf("ItemArms" to BcGroupRule(name = "ItemArms")),
        activities = emptyMap(),
        assets = listOf("HempRope", "LeatherCuffs", "Ribbons")
            .associate { name ->
                BcAssetKey(group = "ItemArms", name = name) to
                    BcAssetRule(group = "ItemArms", name = name)
            },
    )
}
