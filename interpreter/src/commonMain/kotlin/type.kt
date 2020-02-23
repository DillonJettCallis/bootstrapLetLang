
sealed class Type

sealed class PrimitiveType: Type()

object CharType: PrimitiveType()
object StringType: PrimitiveType()
object IntType: PrimitiveType()
object FloatType: PrimitiveType()
object BooleanType: PrimitiveType()
object AnyType: PrimitiveType()
object NullType: PrimitiveType()
object UnitType: PrimitiveType()
object NothingType: PrimitiveType()

object ListType: PrimitiveType()

data class DataType(val fields: Map<String, Type>, val typeParams: List<PlaceholderType> = emptyList()): Type()
data class ProtocolType(val functions: Map<String, Type>, val typeParams: List<PlaceholderType> = emptyList()): Type()
data class FunctionType(val paramTypes: List<Type>, val resultType: Type, val typeParams: List<PlaceholderType> = emptyList()): Type()
data class TupleType(val paramTypes: List<Type>): Type()
data class GenericType(val base: Type, val paramTypes: List<Type>): Type()

object UnknownType: Type()
data class NamedType(val name: String): Type()

data class PlaceholderType(val name: String): Type()


data class UnionType(val paramTypes: List<Type>): Type() {
  companion object {
    fun from(left: Type, right: Type): UnionType {
      return UnionType(unwrap(left) + unwrap(right))
    }

    private fun unwrap(type: Type): List<Type> = if (type is UnionType) type.paramTypes else listOf(type)
  }
}
data class IntersectionType(val paramTypes: List<Type>): Type() {
  companion object {
    fun from(left: Type, right: Type): IntersectionType {
      return IntersectionType(unwrap(left) + unwrap(right))
    }

    private fun unwrap(type: Type): List<Type> = if (type is IntersectionType) type.paramTypes else listOf(type)
  }
}

fun listOfType(of: Type) = GenericType(ListType, listOf(of))

val stringTemplateType = FunctionType(
  paramTypes = listOf(listOfType(StringType), listOfType(AnyType)),
  resultType = StringType
)

