private class Scope(val values: MutableMap<String, JValue>, val parent: Scope?) {

  operator fun get(key: String): JValue {
    return this.values.getOrElse(key) {
      if (parent != null)
        parent[key]
      else
        throw Exception("No such value $key in scope")
    }
  }

  operator fun set(key: String, value: JValue) {
    this.values[key] = value
  }

  fun child(): Scope {
    return Scope(HashMap(), this)
  }

}

private class ReturnException(val value: JValue): RuntimeException()

fun executePackage(pack: AstModule, args: List<String>): Any? {
  val core = initCoreScope()

  pack.files.forEach { (path, mod) ->
    core[path.joinToString(".")] = buildScope(mod, core).wrap()
  }

  pack.files.forEach { (path, mod) ->
    val thisScope = core[path.joinToString(".")].unwrap<Scope>()

    mod.declarations.filterIsInstance<ImportDeclare>().forEach {
      val (packageName, modulePath, path) = it.import

      // TODO: For now we assume only internal imports. All core libs are auto imported

      val init = path.dropLast(1).joinToString(".")
      val value = path.last()

      thisScope[value] = core[init].unwrap<Scope>()[value]
    }
  }

  val mainFun = core["App"].unwrap<Scope>()["main"] as JFunction

  return executeMain(mainFun, args)
}

private fun buildScope(file: AstFile, core: Scope): Scope {
  val moduleScope = core.child()

  file.declarations.forEach {
    when (it) {
      is AtomDeclare -> moduleScope[it.name] = JAtom(it.name)
      is DataDeclare -> moduleScope[it.name] = JClass(it.name, emptyMap(), emptyMap())
      is TypeDeclare -> moduleScope[it.type.name] = JAtom(it.type.name)
      is ImportDeclare -> {}
      is ConstantDeclare -> moduleScope[it.assign.name] = interpret(it.assign.body, moduleScope)
      is ProtocolDeclare -> TODO()
      is FunctionDeclare -> moduleScope[it.func.name] = it.func.body.makeJFunction(moduleScope)
      is ImplDeclare -> {
        val dataName = (it.base as NamedType).name
        val dataObj = moduleScope[dataName] as JClass

        val (statics, instances) = it.funcs.partition { fn -> fn.func.body.args.isEmpty() || fn.func.body.args.first() != "this" }

        moduleScope[dataName] = dataObj.copy(
          staticMethods = dataObj.staticMethods + statics.associate { fn -> fn.func.name to fn.func.body.makeJFunction(moduleScope) },
          instanceMethods = dataObj.instanceMethods + instances.associate { fn -> fn.func.name to fn.func.body.makeJFunction(moduleScope) }
        )
      }
    }
  }

  return moduleScope
}

private fun executeMain(mainFun: JFunction, args: List<String>): Any? {
  return mainFun( listOf(args.map { it.wrap() }.wrap()) )
}

private fun interpret(src: Expression, scope: Scope): JValue {
  val ex = if (src is ByteExpression) {
    src
  } else {
    compile(src)
  }

  val stack = ArrayList<JValue>()
  var code = ex.code
  var index = 0
  var scope = scope

  while (index < code.size) {
    when (val next = code[index++]) {
      Pop -> stack.removeLast()
      Dup -> stack.add(stack.last())
      Swap -> {
        val last = stack.removeLast()
        val second = stack.removeLast()
        stack += last
        stack += second
      }

      Is -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add(((left as JObject).jClass == (right as JClass)).wrap())
      }
      IsNot -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add(((left as JObject).jClass != (right as JClass)).wrap())
      }
      Add -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left.unwrap<Int>() + right.unwrap<Int>()).wrap())
      }
      Subtract -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left.unwrap<Int>() - right.unwrap<Int>()).wrap())
      }
      Multiply -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left.unwrap<Int>() * right.unwrap<Int>()).wrap())
      }
      Divide -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left.unwrap<Int>() / right.unwrap<Int>()).wrap())
      }
      GreaterThan -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left.unwrap<Int>() > right.unwrap<Int>()).wrap())
      }
      GreaterThanOrEqualTo -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left.unwrap<Int>() >= right.unwrap<Int>()).wrap())
      }
      LessThan -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left.unwrap<Int>() < right.unwrap<Int>()).wrap())
      }
      LessThanOrEqualTo -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left.unwrap<Int>() <= right.unwrap<Int>()).wrap())
      }
      EqualTo -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left == right || left is Wildcard || right is Wildcard).wrap())
      }
      NotEqualTo -> {
        val right = stack.removeLast()
        val left =  stack.removeLast()

        stack.add((left != right).wrap())
      }
      Negate -> {
        val body = stack.removeLast()

        stack.add((-body.unwrap<Int>()).wrap())
      }
      Not -> {
        val body = stack.removeLast()

        stack.add((!body.unwrap<Boolean>()).wrap())
      }
      Return -> {
        return stack.last()
      }
      Throw -> {
        throw stack.last().unwrap()
      }
      PushScope -> {
        scope = scope.child()
      }
      PopScope -> {
        scope = scope.parent!!
      }
      Debug -> {
        println("Debug here")
      }
      is LoadLiteral -> {
        stack.add(next.value)
      }
      is LoadLocal -> {
        stack.add(scope[next.name])
      }
      is StoreLocal -> {
        val value = stack.removeLast()

        scope[next.name] = value
      }
      is Call -> {
        val args = (0 until next.args).map { stack.removeLast() }.reversed()
        val func = stack.removeLast().unwrap<JFunction>()

        val result = func(args)
        stack.add(result)
      }
      is TailCall -> {
        val args = (0 until next.args).map { stack.removeLast() }.reversed()
        val func = stack.removeLast().unwrap<JFunction>()

        if (func is ByteFunction) {
          scope = func.closingScope.child()
          stack.clear()
          index = 0
          code = func.code.code

          func.args.zip(args).forEach { (name, value) ->
            scope[name] = value
          }
        } else {
          return func(args)
        }
      }
      is Access -> {
        val left = stack.removeLast()
        val key = next.name

        if (left is JObject) {
          when {
            left.fields.containsKey(key) -> stack +=  left.fields.getValue(key).wrap()
            left.jClass.instanceMethods.containsKey(key) -> stack +=  left.jClass.instanceMethods.getValue(key)
            else -> ex.pos.fail("Attempt to access property '$key' that does not exist on object '$left'")
          }
        } else if (left is JClass) {
          if (left.staticMethods.containsKey(key)) {
            stack += left.staticMethods.getValue(key)
          } else {
            ex.pos.fail("Attempt to access static that does not exist")
          }
        } else {
          ex.pos.fail("Attempt to access when value was not an object or a class")
        }
      }
      is ListAccess -> {
        val list = stack.removeLast().unwrap<List<JValue>>()
        stack.add(list[next.index])
      }
      is MakeClosure -> {
        stack.add(ByteFunction(
          args = next.args,
          code = next.ex,
          closingScope = scope,
        ))
      }
      is If -> {
        val test = stack.removeLast()

        if (!test.unwrap<Boolean>()) {
          index += next.jumpIfFalse - 1
        }
      }
      is Jump -> {
        index += next.jump - 1
      }
      is Label, is IfLabeled, is JumpLabeled -> {
        throw IllegalStateException("Labeling should not make it to the interpreter")
      }
    }
  }

  return stack.removeLast()
}

sealed interface JValue

object JNull: JValue { override fun toString() = "null" }
object Wildcard: JValue { override fun toString() = "_" }
data class JAtom(val name: String): JValue { override fun toString() = name }
data class JClass(val name: String, val instanceMethods: Map<String, JFunction>, val staticMethods: Map<String, JFunction>): JValue {
  override fun toString() = name
}
data class JObject(val fields: Map<String, Any?>, val jClass: JClass): JValue {
  override fun toString(): String {
    return if (jClass.instanceMethods.containsKey("toString")) {
      (jClass.instanceMethods["toString"] as JFunction)(listOf(this)).unwrap()
    } else {
      fields.entries.joinToString(prefix = "${jClass.name} {", postfix = "}") { "${it.key}: ${it.value}" }
    }
  }
}
interface JFunction: JValue {
  operator fun invoke(args: List<JValue>): JValue
}

data class JNativeFunction(val func: (List<JValue>) -> JValue): JFunction {
  override fun toString() = "<Function>"
  override fun invoke(args: List<JValue>): JValue = func(args)
}

private data class ByteFunction(val args: List<String>, val code: ByteExpression, val closingScope: Scope): JFunction {
  override fun toString() = "<Function>"
  override fun invoke(args: List<JValue>): JValue {
    val scope = closingScope.child()

    this.args.zip(args).forEach { (key, value) ->
      scope[key] = value
    }

    return interpret(code, scope)
  }
}

private inline fun <reified T> JValue.unwrap(): T {
  return when (this) {
    is JFunction -> this as T
    is JObject -> {
      val raw = this.fields["@src"]

      if (raw is T) {
        raw
      } else {
        throw RuntimeException("Invalid unwrap $this")
      }
    }
    is JNull -> null as T
    is Wildcard -> Wildcard as T
    else -> {
      test()
      throw RuntimeException("Invalid unwrap $this")
    }
  }
}

private fun test() {
  println("Test!")
}

private val jAnyClass = JClass("Any", emptyMap(), emptyMap())

fun Any?.wrap(): JValue {
  return if (this == null) {
    JNull
  } else {
    val clazz = when (this) {
      is JAtom -> return this
      is JNull -> return this
      is JObject -> return this
      is JFunction -> return this
      is String -> return JObject(mapOf("@src" to this, "size" to this.length), jString)
      is Char -> jChar
      is Exception -> jError
      is List<*> -> return JObject(mapOf("@src" to this, "size" to this.size), jList)
      is Set<*> -> jSet
      is Map<*, *> -> jMap
      is FileImpl -> jFile
      else -> jAnyClass
    }

    JObject(mapOf("@src" to this), clazz)
  }
}

private val jError = JClass("Error", emptyMap(), mapOf("new" to JNativeFunction { Exception( it[1].unwrap<String>()).wrap() }))

private val jString = JClass("String", mapOf(
    "getCharAt" to JNativeFunction {
      val (rawStr, rawIndex) = it
      val str = rawStr.unwrap<String>()
      val index = rawIndex.unwrap<Int>()

      if (index >= str.length) {
        println("uh oh")
      }

      str[index].wrap()
    },
    "toUpperCase" to JNativeFunction {
      it[0].unwrap<String>().uppercase().wrap()
    },
    "isEmpty" to JNativeFunction {
      it[0].unwrap<String>().isEmpty().wrap()
    },
    "append" to JNativeFunction {
      val (left, right) = it
      (left.unwrap<String>() + right.unwrap<Any?>().toString()).wrap()
    },
    "contains" to JNativeFunction {
      val (rawStr, rawChar) = it

      rawStr.unwrap<String>().contains(rawChar.unwrap<Char>()).wrap()
    },
    "replace" to JNativeFunction {
      val (rawStr, rawPattern, rawReplace) = it
      val self = rawStr.unwrap<String>()
      val pattern = rawPattern.unwrap<String>()
      val replace = rawReplace.unwrap<String>()

      self.replace(pattern, replace).wrap()
    },
    "toString" to JNativeFunction { it[0] }
  ), mapOf()
)

private val jChar = JClass(
  name = "Char",
  instanceMethods = mapOf(
    "toUpperCase" to JNativeFunction {
      it[0].unwrap<Char>().uppercaseChar().wrap()
    },
    "toString" to JNativeFunction { it[0].unwrap<Char>().toString().wrap() }
  ),
  staticMethods = mapOf()
)

private val jList = JClass(
  name = "List",
  staticMethods =  mapOf(
    "of" to JNativeFunction { it.drop(1).wrap() }
  ),
  instanceMethods = mapOf(
    "add" to JNativeFunction {
      val (rawList, rawNew) = it
      val list = rawList.unwrap<List<JValue>>()
      (list + rawNew).wrap()
    },
    "prepend" to JNativeFunction {
      val (rawList, rawNew) = it
      val list = rawList.unwrap<List<JValue>>()
      (listOf(rawNew) + list).wrap()
    },
    "get" to JNativeFunction {
      val (rawList, rawIndex) = it
      val list = rawList.unwrap<List<JValue>>()
      val index = rawIndex.unwrap<Int>()
      if (index >= list.size) {
        println("uh oh")
      }
      list[index]
    },
    "head" to JNativeFunction {
      it[0].unwrap<List<JValue>>().first()
    },
    "last" to JNativeFunction {
      it[0].unwrap<List<JValue>>().last()
    },
    "tail" to JNativeFunction {
      it[0].unwrap<List<JValue>>().drop(1).wrap()
    },
    "init" to JNativeFunction {
      it[0].unwrap<List<JValue>>().dropLast(1).wrap()
    },
    "isEmpty" to JNativeFunction {
      it[0].unwrap<List<JValue>>().isEmpty().wrap()
    },
    "concat" to JNativeFunction {
      val (rawSelf, rawOther) = it
      val self = rawSelf.unwrap<List<JValue>>()
      val other = rawOther.unwrap<List<JValue>>()
      (self + other).wrap()
    },
    "toSet" to JNativeFunction {
      val (rawSelf) = it
      val self = rawSelf.unwrap<List<JValue>>()

      self.toSet().wrap()
    },
    "zip" to JNativeFunction {
      val (rawFirst, rawSecond) = it
      val first = rawFirst.unwrap<List<JValue>>()
      val second = rawSecond.unwrap<List<JValue>>()

      first.zip(second) { l, r -> listOf(l, r) }.wrap()
    },
    "filter" to JNativeFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.filter { item ->
        func(listOf(item)).unwrap<Boolean>()
      }.wrap()
    },
    "map" to JNativeFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      val result = list.map { item ->
        func(listOf(item))
      }

      result.wrap()
    },
    "flatMap" to JNativeFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      val result = list.flatMap { item ->
        func(listOf(item)).unwrap<Iterable<JValue>>()
      }

      result.wrap()
    },
    "fold" to JNativeFunction {
      val (rawList, rawInit, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.fold(rawInit) { sum, next -> func(listOf(sum, next)) }
    },
    "reduce" to JNativeFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.reduce{ l, r -> func(listOf(l, r)) }
    },
    "forEach" to JNativeFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.forEach { item ->
        func(listOf(item))
      }

      JNull
    },
    "join" to JNativeFunction { args ->
      val (rawList, rawSep, rawFunc) = args
      val list = rawList.unwrap<List<JValue>>()
      val sep = rawSep.unwrap<String>()
      val func = rawFunc as JFunction

      list.joinToString(sep) { func(listOf(it)).unwrap() }.wrap()
    },
    "toString" to JNativeFunction { args ->
      args[0].unwrap<List<JValue>>().joinToString(", ", "[", "]") { it.toString() }.wrap()
    }
  )
)

private val jSet = JClass(
  name = "Set",
  staticMethods = mapOf(
    "of" to JNativeFunction {
      it.drop(1).toSet().wrap()
    }
  ),
  instanceMethods = mapOf(
    "contains" to JNativeFunction {
      val (rawSet, rawValue) = it
      val set = rawSet.unwrap<Set<JValue>>()
      (rawValue in set).wrap()
    },
    "add" to JNativeFunction {
      val (rawSet, rawValue) = it
      val set = rawSet.unwrap<Set<JValue>>()
      (set + rawValue).wrap()
    },
  )
)

private val jMap = JClass(
  name = "Map",
  staticMethods = mapOf(
    "of" to JNativeFunction { args ->
      args.drop(1).associate { pair ->
        val (key, value) = pair.unwrap<List<JValue>>();
        key to value
      }.wrap()
    },
    "from" to JNativeFunction { args ->
      args[1].unwrap<List<JValue>>().associate { pair ->
        val (key, value) = pair.unwrap<List<JValue>>();
        key to value
      }.wrap()
    }
  ),
  instanceMethods = mapOf(
    "set" to JNativeFunction { args ->
      val (rawMap, rawKey, rawValue) = args
      val map = rawMap.unwrap<Map<JValue, JValue>>()
      (map + (rawKey to rawValue)).wrap()
    },
    "get" to JNativeFunction { args ->
      val (rawMap, rawKey) = args
      val map = rawMap.unwrap<Map<JValue, JValue>>()
      map[rawKey] ?: JNull
    },
    "contains" to JNativeFunction { args ->
      val (rawMap, rawKey) = args
      val map = rawMap.unwrap<Map<JValue, JValue>>()
      map.containsKey(rawKey).wrap()
    },
    "keys" to JNativeFunction { args ->
      args[0].unwrap<Map<JValue, JValue>>().keys.map { it.wrap() }.wrap()
    },
    "values" to JNativeFunction { args ->
      args[0].unwrap<Map<JValue, JValue>>().keys.map { it.wrap() }.wrap()
    },
    "entries" to JNativeFunction { args ->
      args[0].unwrap<Map<JValue, JValue>>().entries.map { it.toPair().toList().wrap() }.wrap()
    }
  )
)

private val jFile = JClass(
  name = "File",
  staticMethods = mapOf(
    "from" to JNativeFunction {
      val path = it[1].unwrap<String>()
      FileImpl(path).wrap()
    }
  ),
  instanceMethods = mapOf(
    "walkFiles" to JNativeFunction { args -> args[0].unwrap<FileImpl>().walkFiles().map { it.wrap() }.wrap() },
    "readText" to JNativeFunction { it[0].unwrap<FileImpl>().readText().wrap() },
    "writeText" to JNativeFunction { it[0].unwrap<FileImpl>().writeText(it[1].unwrap<String>()); JNull },
    "path" to JNativeFunction { it[0].unwrap<FileImpl>().path.wrap() },
    "extension" to JNativeFunction { it[0].unwrap<FileImpl>().extension().wrap() },
    "relativePath" to JNativeFunction { args ->
      val (self, other) = args
      self.unwrap<FileImpl>().relativePath(other.unwrap<FileImpl>()).map { it.wrap() } .wrap()
    }
  )
)

private fun LambdaExp.makeJFunction(context: Scope): JFunction {
  return ByteFunction(
    args = args,
    code = compile(body),
    closingScope = context
  )
}

val templateFun = JNativeFunction { args ->
  val (rawStrings, rawValues) = args

  val strings = rawStrings.unwrap<List<JValue>>().map { it.unwrap<String>() }
  val values = rawValues.unwrap<List<JValue>>().map { it.toString() }

  val result = StringBuilder()

  for (i in values.indices) {
    result.append(strings[i])
    result.append(values[i])
  }

  result.append(strings.last())

  result.toString().wrap()
}

val constructFun = JNativeFunction { args ->
  val base = args.first()

  val fields = args.asSequence().drop(1).windowed(2, 2).associate { (key, body) -> key.unwrap<String>() to body }

  when (base) {
    is JClass -> JObject(fields, base)
    is JObject ->JObject(base.fields + fields, base.jClass)
    else -> throw IllegalStateException("Illegal construct statement")
  }
}

val jPrintln = JNativeFunction { args ->
  println(args.joinToString(" "){ it.toString() })
  JNull
}

private fun initCoreScope(): Scope {
  return Scope(hashMapOf(
    "_" to Wildcard,
    "Error" to jError,
    "List" to jList,
    "Set" to jSet,
    "Map" to jMap,
    "File" to jFile,
    "println" to jPrintln,
    "@template" to templateFun,
    "@construct" to constructFun,
  ), null)
}

