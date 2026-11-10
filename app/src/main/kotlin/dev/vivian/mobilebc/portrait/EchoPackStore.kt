package dev.vivian.mobilebc.portrait

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Keep the last validated package across crashes; never replace it with a partial download. */
internal class EchoPackStore(private val directory: File, private val supplement: String) {
    private fun read(name: String): EchoDataPack? = runCatching {
        val file = File(directory, name)
        require(file.length() in 1..EchoDataPack.MaxBytes.toLong())
        EchoDataPack.decode(file.readText(), supplement)
    }.getOrNull()

    fun current(bundled: EchoDataPack): EchoDataPack =
        listOfNotNull(bundled, read("current.json"), read("previous.json")).maxBy { it.sequence }

    fun install(text: String, sequence: Long, active: EchoDataPack) {
        val pack = EchoDataPack.decode(text, supplement)
        require(pack.sequence == sequence && sequence > current(active).sequence) { "Echo downgrade or mismatched sequence" }
        directory.mkdirs()
        val staging = File(directory, "pending.json")
        staging.outputStream().use { output -> output.write(text.toByteArray()); output.fd.sync() }
        val current = File(directory, "current.json")
        if (read("current.json") != null) {
            Files.move(current.toPath(), File(directory, "previous.json").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(staging.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
