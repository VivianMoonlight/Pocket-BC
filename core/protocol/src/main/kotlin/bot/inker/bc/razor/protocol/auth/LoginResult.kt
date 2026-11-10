package bot.inker.bc.razor.protocol.auth

import bot.inker.bc.razor.protocol.account.ServerAccountData

sealed class LoginResult {
    data class Success(val data: ServerAccountData) : LoginResult()
    data class Error(val message: String) : LoginResult()
}
