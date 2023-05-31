

// typechecker works in three passes
// first pass collects all declared types
// second pass goes through all the declarations and verifies that all used types do in fact exist, nothing more,
//   this should replace all the bare names with references to the fully qualified locations of those types
// third pass actually verifies the types, only checking that each layer has the required properties when needed


// step 1: for every file, create a TypeScope, add all imports and all declared types and




// data types
// type aliases, including intersection and union types
// functions
// instance methods
// static methods
// protocols
// implementations

private data class FullTypeName(
  val module: String,
  val path: List<String>,
)

private data class FileTypes(
  val imports: Map<String, FullTypeName>,
  val data: Set<String>,

)

private data class TypeScope(
  val types: Map<String, Type>,
  val values: Map<String, Type>,
  val parent: TypeScope? = null,
) {

  fun addType(name: String, type: Type, pos: Position): TypeScope = copy(types = types + (name to verifyNamed(type, pos)))

  fun addValue(name: String, type: Type, pos: Position): TypeScope = copy(values = values + (name to verifyNamed(type, pos)))

  fun child(): TypeScope = TypeScope(emptyMap(), emptyMap(), this)

  fun lookupType(name: String, pos: Position): Type {
    return lookup(name, pos, types, "type", ::lookupType)
  }

  fun lookupValue(name: String, pos: Position): Type {
    return lookup(name, pos, values, "value", ::lookupValue)
  }

  private fun verifyNamed(type: Type, pos: Position): Type {
    return if (type is NamedType) {
      lookupType(type.name, pos)
    } else {
      type
    }
  }

  private inline fun lookup(name: String, pos: Position, dict: Map<String, Type>, key: String, root: (String, Position) -> Type): Type {
    val maybe = dict[name]

    if (maybe == null) {
      if (parent == null) {
        pos.fail("No $key named $name found")
      } else {
        return root(name, pos)
      }
    } else {
      return maybe
    }
  }

}


