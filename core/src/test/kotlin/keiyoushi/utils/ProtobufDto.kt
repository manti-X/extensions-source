package keiyoushi.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoPacked
import kotlinx.serialization.protobuf.ProtoType

// DTOs used by ProtobufDifferentialTest. They are data classes on purpose: the test leans on
// structural equals to compare what the two decoders produced.

@Serializable
enum class Color { RED, GREEN, BLUE }

@Serializable
data class Scalars(
    @ProtoNumber(1) val i: Int = 0,
    @ProtoNumber(2) val l: Long = 0,
    @ProtoNumber(3) val b: Boolean = false,
    @ProtoNumber(4) val f: Float = 0f,
    @ProtoNumber(5) val d: Double = 0.0,
    @ProtoNumber(6) val s: String = "",
    @ProtoNumber(7) val by: Byte = 0,
    @ProtoNumber(8) val sh: Short = 0,
    @ProtoNumber(9) val c: Char = 'a',
    @ProtoNumber(10) val e: Color = Color.RED,
)

@Serializable
data class IntegerTypes(
    @ProtoNumber(1) @ProtoType(ProtoIntegerType.SIGNED) val si: Int = 0,
    @ProtoNumber(2) @ProtoType(ProtoIntegerType.FIXED) val fi: Int = 0,
    @ProtoNumber(3) @ProtoType(ProtoIntegerType.SIGNED) val sl: Long = 0,
    @ProtoNumber(4) @ProtoType(ProtoIntegerType.FIXED) val fl: Long = 0,
    @ProtoNumber(5) @ProtoType(ProtoIntegerType.DEFAULT) val di: Int = 0,
    @ProtoNumber(6) @ProtoType(ProtoIntegerType.DEFAULT) val dl: Long = 0,
)

@Serializable
data class Nullables(
    @ProtoNumber(1) val a: String? = null,
    @ProtoNumber(2) val b: Int? = null,
    @ProtoNumber(3) val c: Leaf? = null,
    @ProtoNumber(4) val d: List<Int>? = null,
    @ProtoNumber(5) val e: Long? = null,
)

@Serializable
data class Leaf(
    @ProtoNumber(1) val id: Int = 0,
    @ProtoNumber(2) val name: String = "",
)

@Serializable
data class Nested(
    @ProtoNumber(1) val leaf: Leaf = Leaf(),
    @ProtoNumber(2) val inner: Nested? = null,
    @ProtoNumber(3) val tag: String = "",
)

/** Self-referential message: the same descriptor appears at two nesting levels. */
@Serializable
data class Node(
    @ProtoNumber(1) val value: Int = 0,
    @ProtoNumber(2) val next: Node? = null,
)

@Serializable
data class Lists(
    @ProtoNumber(1) val ints: List<Int> = emptyList(),
    @ProtoNumber(2) val strings: List<String> = emptyList(),
    @ProtoNumber(3) val leaves: List<Leaf> = emptyList(),
    @ProtoNumber(4) val enums: List<Color> = emptyList(),
    @ProtoNumber(5) val trailer: Int = 0,
)

@Serializable
data class PackedLists(
    @ProtoNumber(1) @ProtoPacked val ints: List<Int> = emptyList(),
    @ProtoNumber(2) @ProtoPacked val longs: List<Long> = emptyList(),
    @ProtoNumber(3) @ProtoPacked val floats: List<Float> = emptyList(),
    @ProtoNumber(4) @ProtoPacked val doubles: List<Double> = emptyList(),
    @ProtoNumber(5) @ProtoPacked val bools: List<Boolean> = emptyList(),
    @ProtoNumber(6) @ProtoPacked val enums: List<Color> = emptyList(),
    @ProtoNumber(9) val trailer: Int = 0,
)

/**
 * Packed fields carrying a [ProtoType]. kotlinx.serialization ignores the annotation inside a packed
 * field and writes plain varints, so these are checked against protoc's encoding instead.
 */
@Serializable
data class PackedTypedLists(
    @ProtoNumber(1) @ProtoPacked @ProtoType(ProtoIntegerType.SIGNED) val signed: List<Int> = emptyList(),
    @ProtoNumber(2) @ProtoPacked @ProtoType(ProtoIntegerType.SIGNED) val signedLongs: List<Long> = emptyList(),
    @ProtoNumber(3) @ProtoPacked @ProtoType(ProtoIntegerType.FIXED) val fixedLongs: List<Long> = emptyList(),
    @ProtoNumber(4) @ProtoPacked @ProtoType(ProtoIntegerType.FIXED) val fixedInts: List<Int> = emptyList(),
)

@Serializable
data class WithBytes(
    @ProtoNumber(1) val head: Int = 0,
    @ProtoNumber(2) val payload: ByteArray = ByteArray(0),
    @ProtoNumber(3) val tail: String = "",
) {
    override fun equals(other: Any?): Boolean = other is WithBytes &&
        head == other.head && payload.contentEquals(other.payload) && tail == other.tail

    override fun hashCode(): Int = head * 31 + payload.contentHashCode() * 31 + tail.hashCode()

    override fun toString(): String = "WithBytes($head, ${payload.toList()}, $tail)"
}

@JvmInline
@Serializable
value class Wrapped(val value: Int)

@Serializable
data class WithValueClass(
    @ProtoNumber(1) val id: Wrapped = Wrapped(0),
    @ProtoNumber(2) val many: List<Wrapped> = emptyList(),
    @ProtoNumber(3) @ProtoPacked val packed: List<Wrapped> = emptyList(),
)

@Serializable
data class Generic<T>(
    @ProtoNumber(1) val value: T,
)

@Serializable
data class GenericHolder(
    @ProtoNumber(1) val count: Int = 0,
    @ProtoNumber(3) val results: Generic<List<Generic<Leaf>>>,
)

/** 70 fields, which pushes the decoder past its 64-bit "seen" mask. */
@Serializable
data class WideMessage(
    @ProtoNumber(1) val f1: String? = null,
    @ProtoNumber(2) val f2: String? = null,
    @ProtoNumber(60) val f60: List<Int> = emptyList(),
    @ProtoNumber(61) val f61: String? = null,
    @ProtoNumber(62) val f62: String? = null,
    @ProtoNumber(63) val f63: String? = null,
    @ProtoNumber(64) val f64: String? = null,
    @ProtoNumber(65) val f65: String? = null,
    @ProtoNumber(66) val f66: List<Leaf> = emptyList(),
    @ProtoNumber(67) val f67: String? = null,
    @ProtoNumber(68) val f68: Int = 7,
    @ProtoNumber(69) val f69: String? = null,
    @ProtoNumber(70) val f70: String? = null,
    @ProtoNumber(71) val f71: String? = null,
    @ProtoNumber(72) val f72: String? = null,
    @ProtoNumber(73) val f73: String? = null,
    @ProtoNumber(74) val f74: String? = null,
    @ProtoNumber(75) val f75: String? = null,
    @ProtoNumber(76) val f76: String? = null,
    @ProtoNumber(77) val f77: String? = null,
    @ProtoNumber(78) val f78: String? = null,
    @ProtoNumber(79) val f79: String? = null,
    @ProtoNumber(80) val f80: String? = null,
    @ProtoNumber(81) val f81: String? = null,
    @ProtoNumber(82) val f82: String? = null,
    @ProtoNumber(83) val f83: String? = null,
    @ProtoNumber(84) val f84: String? = null,
    @ProtoNumber(85) val f85: String? = null,
    @ProtoNumber(86) val f86: String? = null,
    @ProtoNumber(87) val f87: String? = null,
    @ProtoNumber(88) val f88: String? = null,
    @ProtoNumber(89) val f89: String? = null,
    @ProtoNumber(90) val f90: String? = null,
    @ProtoNumber(91) val f91: String? = null,
    @ProtoNumber(92) val f92: String? = null,
    @ProtoNumber(93) val f93: String? = null,
    @ProtoNumber(94) val f94: String? = null,
    @ProtoNumber(95) val f95: String? = null,
    @ProtoNumber(96) val f96: String? = null,
    @ProtoNumber(97) val f97: String? = null,
    @ProtoNumber(98) val f98: String? = null,
    @ProtoNumber(99) val f99: String? = null,
    @ProtoNumber(100) val f100: String? = null,
    @ProtoNumber(101) val f101: String? = null,
    @ProtoNumber(102) val f102: String? = null,
    @ProtoNumber(103) val f103: String? = null,
    @ProtoNumber(104) val f104: String? = null,
    @ProtoNumber(105) val f105: String? = null,
    @ProtoNumber(106) val f106: String? = null,
    @ProtoNumber(107) val f107: String? = null,
    @ProtoNumber(108) val f108: String? = null,
    @ProtoNumber(109) val f109: String? = null,
    @ProtoNumber(110) val f110: String? = null,
    @ProtoNumber(111) val f111: String? = null,
    @ProtoNumber(112) val f112: String? = null,
    @ProtoNumber(113) val f113: String? = null,
    @ProtoNumber(114) val f114: String? = null,
    @ProtoNumber(115) val f115: String? = null,
    @ProtoNumber(116) val f116: String? = null,
    @ProtoNumber(117) val f117: String? = null,
    @ProtoNumber(118) val f118: String? = null,
    @ProtoNumber(119) val f119: String? = null,
    @ProtoNumber(120) val f120: String? = null,
    @ProtoNumber(121) val f121: String? = null,
    @ProtoNumber(122) val f122: String? = null,
    @ProtoNumber(123) val f123: String? = null,
    @ProtoNumber(124) val f124: String? = null,
    @ProtoNumber(125) val f125: String? = null,
    @ProtoNumber(126) val f126: String? = null,
    @ProtoNumber(127) val f127: String? = null,
)

/**
 * [values] has no default, so an absent one can only come from the decoder's rescue pass. That makes
 * this shape sensitive to a recycled decoder carrying over the previous message's "seen" state.
 */
@Serializable
data class RequiredList(
    @ProtoNumber(1) val values: List<Int>,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val note: String? = null,
)

@Serializable
data class RequiredListHolder(
    @ProtoNumber(1) val items: List<RequiredList> = emptyList(),
    @ProtoNumber(2) val nested: RequiredListHolder? = null,
)

/** No @ProtoNumber anywhere: proto ids fall back to 1-based declaration order. */
@Serializable
data class Implicit(
    val first: Int = 0,
    val second: String = "",
    val third: List<Int> = emptyList(),
)
