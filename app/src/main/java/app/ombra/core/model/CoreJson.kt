package app.ombra.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.reflect.KClass

/**
 * JSON settings for the Rust protocol.
 * - encodeDefaults: Rust needs every non-Option field (for example all 6 DnsContentBlock flags).
 * - ignoreUnknownKeys: newer cores may add fields.
 * - coerceInputValues: `null` or an unknown enum value falls back to the property default
 *   (featureFlags are `true` or `null` in Status).
 */
val CoreJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * Serde's externally tagged enum: `{"<tag>": <variant body>}`. The tag of a variant is the
 * `@SerialName` of its class; a field-less variant must be a `data object` so it encodes as `{}`.
 */
internal open class ExternallyTaggedSerializer<T : Any>(
    private val serialName: String,
    vararg variants: Pair<KClass<out T>, KSerializer<out T>>,
) : KSerializer<T> {
    private val byClass: Map<KClass<out T>, KSerializer<out T>> = variants.toMap()
    private val byTag: Map<String, KSerializer<out T>> =
        variants.associate { (_, serializer) -> serializer.descriptor.serialName to serializer }

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    override fun serialize(encoder: Encoder, value: T) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("$serialName supports JSON only")
        @Suppress("UNCHECKED_CAST")
        val serializer = byClass[value::class] as KSerializer<T>?
            ?: throw SerializationException("$serialName has no variant ${value::class.simpleName}")
        json.encodeJsonElement(
            buildJsonObject { put(serializer.descriptor.serialName, json.json.encodeToJsonElement(serializer, value)) },
        )
    }

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: throw SerializationException("$serialName supports JSON only")
        val element = json.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("$serialName: expected a JSON object")
        val (tag, body) = element.entries.singleOrNull()
            ?: throw SerializationException("$serialName: expected exactly one variant key")
        val serializer = byTag[tag] ?: throw SerializationException("$serialName: unknown variant '$tag'")
        return json.json.decodeFromJsonElement(serializer, body)
    }
}

/** An enum with an explicit wire string. */
interface WireEnum {
    val wire: String
}

/** String enum that decodes unknown values to [fallback], so a newer core cannot break decoding. */
internal open class WireEnumSerializer<E>(
    serialName: String,
    private val values: List<E>,
    private val fallback: E,
) : KSerializer<E> where E : Enum<E>, E : WireEnum {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): E = fromWire(decoder.decodeString())

    fun fromWire(wire: String?): E = values.firstOrNull { it.wire == wire } ?: fallback
}
