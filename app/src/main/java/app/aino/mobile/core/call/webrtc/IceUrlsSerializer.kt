package app.aino.mobile.core.call.webrtc

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object IceUrlsSerializer : KSerializer<IceUrls> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IceUrls", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): IceUrls {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val values = when (element) {
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        return IceUrls(values.filter(String::isNotBlank))
    }

    override fun serialize(encoder: Encoder, value: IceUrls) {
        val json = encoder as JsonEncoder
        if (value.values.size == 1) json.encodeJsonElement(JsonPrimitive(value.values.single()))
        else json.encodeJsonElement(JsonArray(value.values.map(::JsonPrimitive)))
    }
}