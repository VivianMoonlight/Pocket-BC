package bot.inker.bc.razor.protocol.account

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

internal class StrictNullableStringAdapter : TypeAdapter<String>() {
    override fun read(reader: JsonReader): String? = if (reader.peek() == JsonToken.STRING) {
        reader.nextString()
    } else {
        reader.skipValue()
        null
    }

    override fun write(writer: JsonWriter, value: String?) {
        if (value == null) writer.nullValue() else writer.value(value)
    }
}
