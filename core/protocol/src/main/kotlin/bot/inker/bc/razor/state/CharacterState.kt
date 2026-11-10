package bot.inker.bc.razor.state

import bot.inker.bc.razor.protocol.account.Difficulty
import bot.inker.bc.razor.protocol.account.Lovership
import bot.inker.bc.razor.protocol.account.Ownership
import bot.inker.bc.razor.protocol.account.Reputation
import bot.inker.bc.razor.protocol.common.ItemBundle
import com.google.gson.JsonElement

data class CharacterState(
    val id: String,
    val memberNumber: Int,
    val name: String,
    val accountName: String? = null,
    val type: String = "online",
    val assetFamily: String = "Female3DCG",
    val nickname: String? = null,
    val appearance: List<ItemBundle> = emptyList(),
    val activePose: List<String> = emptyList(),
    val owner: String? = null,
    val lover: String? = null,
    val ownership: Ownership? = null,
    val lovership: List<Lovership> = emptyList(),
    val reputation: List<Reputation> = emptyList(),
    val whiteList: List<Int> = emptyList(),
    val blackList: List<Int> = emptyList(),
    val labelColor: String? = null,
    val description: String? = null,
    val title: String? = null,
    val creation: Long = 0L,
    val difficulty: Difficulty? = null,
    val allowedInteractions: Int? = null,
    val arousal: ArousalState? = null,
    val arousalSettings: JsonElement? = null,
    val onlineSharedSettings: JsonElement? = null,
    val game: JsonElement? = null,
    val crafting: JsonElement? = null,
    val inventoryData: String? = null,
    val blockItems: JsonElement? = null,
    val limitedItems: JsonElement? = null,
    val favoriteItems: JsonElement? = null,
    val hiddenItems: JsonElement? = null,
    val mapData: JsonElement? = null,
    val rawData: JsonElement? = null,
) {
    data class ArousalState(
        val orgasmTimer: Double? = null,
        val orgasmCount: Int? = null,
        val progress: Double? = null,
        val progressTimer: Double? = null,
    )
}
