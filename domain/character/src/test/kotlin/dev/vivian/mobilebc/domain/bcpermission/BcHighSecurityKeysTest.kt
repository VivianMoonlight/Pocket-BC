package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.bcitemproperty.*
import dev.vivian.mobilebc.domain.bcrules.*
import org.junit.Assert.*
import org.junit.Test

class BcHighSecurityKeysTest {
    private val actor = BcCharacterSnapshot("10", 10, "Actor", "Actor", true)
    private val item = BcCharacterItem("ItemArms", "LeatherCuffs", "[\"Red\"]", 7,
        """{"LockedBy":"HighSecurityPadlock","LockMemberNumber":30,"MemberNumberListKeys":"10,30","Effect":["Lock","Slow"],"Text":"中文😀","Custom":{"keep":null},"TypeRecord":{"typed":1}}""", """{"Name":"定制铐"}""")
    private val assets = listOf(BcAssetRule(item.group, item.name, allowLock = true), BcAssetRule("ItemMisc", "HighSecurityPadlock", isLock = true, exclusiveUnlock = true))
    private fun context() = BcPermissionContext(actor, BcCharacterSnapshot("20",20,"Target","Target",false,appearance=listOf(item)),
        BcPermissionRoomContext(memberNumbers = setOf(10,20)), BcRuleCatalog("R133", mapOf(item.group to BcGroupRule(item.group)),emptyMap(),assets.associateBy { BcAssetKey(it.group,it.name) }),
        serverAllowItem = true, itemPrivateState = BcItemPermissionPrivateState(BcWardrobeState(10,"login",1,null,null,emptyList(),false,false),emptyList()), serverTimeMillis = 1000)
    private fun evaluate(context: BcPermissionContext, change: BcLockConfiguration = BcLockConfiguration.UpdateKeyholders("20",false,false)) =
        DefaultBcInteractionPermissionEngine().evaluate(BcPermissionRequest(context, BcInteractionCandidate.ConfigureLock(20,item.group,item.name,"HighSecurityPadlock",change)))
    private fun command(context: BcPermissionContext, change: BcLockConfiguration = BcLockConfiguration.UpdateKeyholders("20",false,false)) =
        ((evaluate(context,change) as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command

    @Test fun nativeClickFixturesMatchNormalizationRelationsDepositAndPermissions() {
        val fixtures = requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/high-security-keys-r133.json")).readText()
        BcItemPropertyJson.readObjectArray(fixtures).forEach { f ->
            fun text(key: String) = f.readBcStringProperty(key).orEmpty()
            fun numbers(key: String) = BcItemPropertyJson.readPropertyValue(f,key)?.removeSurrounding("[","]")?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
            val configuration = BcLockConfiguration.UpdateKeyholders(text("input"), f.readBcBooleanProperty("appendOwner")==true,
                f.readBcBooleanProperty("appendLovers")==true, f.readBcBooleanProperty("appendWhitelist")==true)
            val actor = actor.copy(ownerMemberNumber=f.readBcIntProperty("owner"),lovership=numbers("lovers").map { BcCharacterLovership("Lover",it,2) },whiteList=numbers("whitelist"))
            val actual = if (f.readBcBooleanProperty("blocked")==true || 10.0 !in readBcKeyholderNumbers(text("before"))) text("before")
                else configuration.keyholderText(actor, f.readBcBooleanProperty("deposited")!=true)
            assertEquals(text("id"),text("expected"),actual)
        }
    }

    @Test fun listedNonCreatorCanReplaceKeysAndAllItemMetadataSurvives() {
        val context = context()
        val command = command(context)
        assertEquals("20",command.propertyJson.readBcStringProperty("MemberNumberListKeys"))
        assertEquals(30,command.propertyJson.readBcIntProperty("LockMemberNumber"))
        assertEquals("HighSecurityPadlock",command.propertyJson.readBcStringProperty("LockedBy"))
        assertEquals("中文😀",command.propertyJson.readBcStringProperty("Text"))
        assertEquals("{\"keep\":null}",BcItemPropertyJson.readPropertyValue(command.propertyJson,"Custom"))
        assertEquals(mapOf("typed" to 1),BcItemPropertyJson.readTypeRecord(command.propertyJson))
        assertEquals(item.colorJson,command.colorJson); assertEquals(item.difficulty,command.difficulty); assertEquals(item.craftJson,command.craftJson)
        assertEquals(BcItemActionMessage("HighSecurityUpdate"),command.publishAction)
        assertEquals(context.itemPrivateState,command.permissionPrivateState)
        val entry = DefaultBcItemManagementQuery().lockConfigurationEntry(context,item.group)!!
        assertTrue(entry.enabled); assertNull(entry.candidate); assertNull(entry.executable)
        val updated = context.copy(target=context.target.copy(appearance=listOf(item.copy(propertyJson=command.propertyJson))))
        assertTrue(evaluate(updated) is BcPermissionDecision.Denied)
    }

    @Test fun editorRequiresExistingKeyholderVisibilityItemPermissionAndPrivateLog() {
        val base = context()
        fun withProperty(value: String) = base.copy(target=base.target.copy(appearance=listOf(item.copy(propertyJson=value))))
        assertTrue(evaluate(withProperty("""{"LockedBy":"HighSecurityPadlock","LockMemberNumber":30,"MemberNumberListKeys":"30"}""")) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(itemPrivateState=null)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base,BcLockConfiguration.UpdateKeyholders("1".repeat(251))) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(serverAllowItem=false)) is BcPermissionDecision.Denied)
        assertTrue(evaluate(base.copy(serverAllowItem=null)) is BcPermissionDecision.NeedsServerCheck)
        assertTrue(evaluate(base.copy(target=base.target.copy(blockItemsJson="""[{"Group":"ItemMisc","Name":"HighSecurityPadlock"}]"""))) is BcPermissionDecision.Denied)
        val blind = actor.copy(appearance=listOf(BcCharacterItem("ItemHead","Hood",propertyJson="""{"Effect":["BlindHeavy"]}""")))
        assertTrue(evaluate(base.copy(actor=blind)) is BcPermissionDecision.Denied)
        val bound = actor.copy(appearance=listOf(BcCharacterItem("ItemHands","Mittens",propertyJson="""{"Effect":["Block"]}""")))
        assertTrue(evaluate(base.copy(actor=bound)) is BcPermissionDecision.Allowed)
        val blocked = base.target.copy(appearance=listOf(item,BcCharacterItem("ItemDevices","Box",propertyJson="""{"Block":["ItemArms"]}""")))
        assertTrue(evaluate(base.copy(target=blocked)) is BcPermissionDecision.Denied)
        for (keys in listOf("",null)) {
            val old = BcItemPropertyJson.overlayRawProperties(item.propertyJson,"""{"LockMemberNumber":10,"MemberNumberListKeys":${keys?.let { "\"$it\"" } ?: "null"}}""")
            assertTrue(evaluate(withProperty(old!!)) is BcPermissionDecision.Allowed)
        }
        for (keys in listOf("[]", "{}", "true", "10")) {
            val old = BcItemPropertyJson.overlayRawProperties(item.propertyJson,"""{"LockMemberNumber":10,"MemberNumberListKeys":$keys}""")
            assertTrue(evaluate(withProperty(old!!)) is BcPermissionDecision.Denied)
        }
    }

    @Test fun depositExpiryAndAutoAppendsUseActorsRelationsAndNativeOrder() {
        val base = context().copy(actor=actor.copy(ownerMemberNumber=90,lovership=listOf(BcCharacterLovership("Lover",80,2)),whiteList=listOf(10,70)))
        fun deposited(expiry: Long?) = base.copy(itemPrivateState=base.itemPrivateState!!.copy(ruleLog=listOf(BcOwnerRuleLogRecord("KeyDeposit","Cell",expiry))))
        val change = BcLockConfiguration.UpdateKeyholders("10,20")
        assertEquals("20,90,80",command(deposited(1000),change).propertyJson.readBcStringProperty("MemberNumberListKeys"))
        assertEquals("10,20,90,80",command(deposited(999),change).propertyJson.readBcStringProperty("MemberNumberListKeys"))
        assertEquals("20,90,80,10,70",command(deposited(null),change.copy(appendWhitelist=true)).propertyJson.readBcStringProperty("MemberNumberListKeys"))
        assertEquals("20,90,80",command(deposited(1000).copy(serverTimeMillis=null),change).propertyJson.readBcStringProperty("MemberNumberListKeys"))
    }

    @Test fun heartLocksKeepTheirSeparateProtocolAndNeverExposeNativeKeysEditor() {
        val base = context()
        val afc = item.copy(propertyJson=BcItemPropertyJson.overlayStrings(item.propertyJson,mapOf("Name" to BcAfcHeartLock.Name,"HeartLockId" to "id-1")))
        val context = base.copy(target=base.target.copy(appearance=listOf(afc)))
        assertTrue(evaluate(context) is BcPermissionDecision.Denied)
        assertNull(DefaultBcItemManagementQuery().lockConfigurationEntry(context,item.group))
    }
}
