
sealed interface Type

sealed interface PrimitiveType: Type

object CharType: PrimitiveType
object StringType: PrimitiveType
object IntType: PrimitiveType
object FloatType: PrimitiveType
object BooleanType: PrimitiveType
object AnyType: PrimitiveType
object NullType: PrimitiveType
object UnitType: PrimitiveType
object NothingType: PrimitiveType

object ListType: PrimitiveType

data class AtomType(val name: String): Type
data class DataType(val name: String, val fields: Map<String, Type>, val typeParams: List<PlaceholderType> = emptyList()): Type
data class ProtocolType(val name: String, val functions: Map<String, Type>, val typeParams: List<PlaceholderType> = emptyList()): Type
data class FunctionType(val paramTypes: List<Type>, val resultType: Type, val typeParams: List<PlaceholderType> = emptyList()): Type
data class TupleType(val paramTypes: List<Type>): Type
data class GenericType(val base: Type, val paramTypes: List<Type>): Type

object UnknownType: Type
data class NamedType(val name: String): Type

data class PlaceholderType(val name: String): Type


data class UnionType(val paramTypes: List<Type>): Type {
  companion object {
    fun from(left: Type, right: Type): UnionType {
      return UnionType(unwrap(left) + unwrap(right))
    }

    private fun unwrap(type: Type): List<Type> = if (type is UnionType) type.paramTypes else listOf(type)
  }
}
data class IntersectionType(val paramTypes: List<Type>): Type {
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

data class ModuleVersion(
  val major: Int,
  val minor: Int,
  val build: Int,
)

enum class ModuleDepMode {
  Implementation, // used internally, but not exposed
  Exported, // exposed in the api, thus this dep must be included transitively
}

data class ModuleDep(
  val meta: ModuleMeta,
  val mode: ModuleDepMode,
)

data class ModuleMeta(
  val name: String,
  val org: String,
  val version: ModuleVersion,

  // a map of the module meta to the name for quicker lookups
  val dependencies: Map<String, ModuleDep>,
)

data class FullName(
  val module: String,
  val path: List<String>,
)

interface TypeExpression
interface TypeToken

interface TypeDictionary {

  fun getTypeByName(name: String): TypeToken

  fun isSubType(target: TypeToken, parent: TypeToken): Boolean

  fun isSuperType(target: TypeToken, child: TypeToken): Boolean

  fun getProperty(target: TypeToken, prop: String): TypeToken

  fun callFunction(func: TypeToken, args: List<TypeToken>): TypeToken

  fun declareDataType(name: String, access: AccessModifier, fields: Map<String, TypeExpression>, typeParams: List<Pair<String, TypeExpression>> = emptyList())

  fun evaluate(ex: TypeExpression): TypeToken

}

