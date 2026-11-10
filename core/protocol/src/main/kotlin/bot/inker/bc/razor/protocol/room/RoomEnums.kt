package bot.inker.bc.razor.protocol.room

enum class ChatRoomSpace(val wireValue: String) {
    FEMALE_ONLY(""),
    MIXED("X"),
    MALE_ONLY("M"),
    ASYLUM("Asylum");

    companion object {
        fun fromWireValue(value: String): ChatRoomSpace {
            return entries.find { it.wireValue == value } ?: FEMALE_ONLY
        }
    }
}
enum class ChatRoomLanguage(val wireValue: String) {
    EN("EN"),
    DE("DE"),
    FR("FR"),
    ES("ES"),
    CN("CN"),
    RU("RU"),
    UA("UA"),
    ANY("");

    companion object {
        fun fromWireValue(value: String): ChatRoomLanguage {
            return entries.find { it.wireValue == value } ?: ANY
        }
    }
}

enum class ChatRoomGame(val wireValue: String) {
    NONE(""),
    CLUB_CARD("ClubCard"),
    LARP("LARP"),
    MAGIC_BATTLE("MagicBattle"),
    GGTS("GGTS"),
    PRISON("Prison");

    companion object {
        fun fromWireValue(value: String): ChatRoomGame {
            return entries.find { it.wireValue == value } ?: NONE
        }
    }
}

enum class ChatRoomAccessRole(val wireValue: String) {
    ALL("All"),
    ADMIN("Admin"),
    WHITELIST("Whitelist");

    companion object {
        fun fromWireValue(value: String): ChatRoomAccessRole {
            return entries.find { it.wireValue == value } ?: ALL
        }
    }
}

enum class ChatRoomBlockCategory(val wireValue: String) {
    MEDICAL("Medical"),
    EXTREME("Extreme"),
    PONY("Pony"),
    SCI_FI("SciFi"),
    ABDL("ABDL"),
    FANTASY("Fantasy"),
    SMOKING("Smoking"),
    LEASHING("Leashing"),
    PHOTOS("Photos"),
    AROUSAL("Arousal");

    companion object {
        fun fromWireValue(value: String): ChatRoomBlockCategory? {
            return entries.find { it.wireValue == value }
        }
    }
}
