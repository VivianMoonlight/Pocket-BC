package dev.vivian.mobilebc.platform.text

import android.content.Context
import dev.vivian.mobilebc.resource.AndroidBcWebResourceFiles
import java.io.File

class AndroidBcTextResourceReader internal constructor(
    private val webRoot: File,
    private val echoNames: () -> String? = { null },
) {
    constructor(context: Context, echoNames: () -> String? = { null }) : this(
        File(context.applicationContext.filesDir, "bc-web"),
        echoNames,
    )

    fun readText(version: String, relativePath: String): String? =
        if (relativePath == "echo/names.json") echoNames() else synchronized(AndroidBcWebResourceFiles.accessLock) {
            if (runCatching {
                    AndroidBcWebResourceFiles.recoverInterruptedCommit(webRoot, version)
                }.isFailure
            ) {
                return@synchronized null
            }
            AndroidBcWebResourceFiles.readText(webRoot, version, relativePath)
        }
}
