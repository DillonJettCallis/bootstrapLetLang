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
        val methods = dataObj.instanceMethods + it.funcs.associate { fn -> fn.func.name to fn.func.body.makeJFunction(moduleScope) }
        moduleScope[dataName] = dataObj.copy(instanceMethods = methods)
      }
    }
  }

  return moduleScope
}

private fun executeMain(mainFun: JFunction, args: List<String>): Any? {
  var tramp = mainFun.trampoline( listOf(args.map { it.wrap() }.wrap()) )

  while (true) {
    val result = tramp()

    if (result is JTrampoline) {
      tramp = result
    } else {
      return result
    }
  }
}

private fun interpret(ex: Expression, scope: Scope): JValue {
  return when (ex) {
    is NullLiteralExp -> JNull
    is BooleanLiteralExp -> ex.value.wrap()
    is NumberLiteralExp -> ex.value.toInt().wrap()
    is StringLiteralExp -> ex.value.wrap()
    is CharLiteralExp -> ex.value.wrap()
    is ListLiteralExp -> ex.args.map { interpret(it, scope) }.wrap()
    is IdentifierExp -> scope[ex.name]
    is BinaryOpExp -> {
      val left = interpret(ex.left, scope)

      if (ex.op == ".") {
        if (ex.right !is IdentifierExp) {
          println("uh oh")
        }

        val key = (ex.right as IdentifierExp).name

        if (left is JObject) {
          when {
            left.fields.containsKey(key) -> return left.fields.getValue(key).wrap()
            left.jClass.instanceMethods.containsKey(key) -> return left.jClass.instanceMethods.getValue(key)
            else -> ex.pos.fail("Attempt to access property '$key' that does not exist on object '$left'")
          }
        }

        if (left is JClass) {
          if (left.staticMethods.containsKey(key)) {
            return left.staticMethods.getValue(key)
          } else {
            ex.pos.fail("Attempt to access static that does not exist")
          }
        }

        ex.pos.fail("Attempt to access when value was not an object or a class")
      }

      when (ex.op) {
        // these need to lazily evaluate the right side so they're handled special
        "&&" -> return (left.unwrap<Boolean>() && interpret(ex.right, scope).unwrap<Boolean>()).wrap()
        "||" -> return (left.unwrap<Boolean>() || interpret(ex.right, scope).unwrap<Boolean>()).wrap()
      }

      val right = interpret(ex.right, scope)

      when (ex.op) {
        "is" -> ((left as JObject).jClass == (right as JClass)).wrap()
        "isNot" -> ((left as JObject).jClass != (right as JClass)).wrap()
        "+" -> (left.unwrap<Int>() + right.unwrap<Int>()).wrap()
        "-" -> (left.unwrap<Int>() - right.unwrap<Int>()).wrap()
        "*" -> (left.unwrap<Int>() * right.unwrap<Int>()).wrap()
        "/" -> (left.unwrap<Int>() / right.unwrap<Int>()).wrap()
        ">" -> (left.unwrap<Int>() > right.unwrap<Int>()).wrap()
        ">=" -> (left.unwrap<Int>() >= right.unwrap<Int>()).wrap()
        "<" -> (left.unwrap<Int>() < right.unwrap<Int>()).wrap()
        "<=" -> (left.unwrap<Int>() <= right.unwrap<Int>()).wrap()
        "==" -> (left == right).wrap()
        "!=" -> (left != right).wrap()
        else -> throw Exception("Invalid operator ${ex.op}")
      }
    }
    is UnaryOpExp -> {
      val body = interpret(ex.ex, scope)

      when (ex.op) {
        "-" -> (-body.unwrap<Int>()).wrap()
        "!" -> (!body.unwrap<Boolean>()).wrap()
        else -> throw Exception("Invalid operator ${ex.op}")
      }
    }
    is CallExp -> {
      val call = callCheck(ex)

      val raw = interpret(call.func, scope)

      if (raw !is JFunction) {
        println("uh oh")
      }

      val func = raw as JFunction
      val args = call.arguments.map { interpret(it, scope) }

      var result = func(args)

      while (result is JTrampoline) {
        result = result()
      }

      result
    }
    is LambdaExp -> ex.makeJFunction(scope)
    is IfExp -> {
      val condition = interpret(ex.condition, scope).unwrap<Boolean>()

      if (condition) {
        interpret(ex.thenExp, scope)
      } else {
        if (ex.elseExp == null) {
          JNull
        } else {
          interpret(ex.elseExp, scope)
        }
      }
    }
    is ReturnExp -> {
      val body = ex.ex

      if (body is CallExp) {
        val call = callCheck(body)

        val func = interpret(call.func, scope) as JFunction
        val args = call.arguments.map { interpret(it, scope) }

        throw ReturnException(func.trampoline(args))
      }

      throw ReturnException(interpret(ex.ex, scope))
    }
    is ThrowExp -> {
      throw interpret(ex.ex, scope).unwrap<Throwable>()
    }
    is ConstructExp -> {
      val base = interpret(ex.base, scope)
      val fields = ex.values.associate { (key, body) -> key to interpret(body, scope) }

      if (base is JClass) {
        return JObject(fields, base)
      }

      if (base is JObject) {
        return JObject(base.fields + fields, base.jClass)
      }

      ex.base.pos.fail("Construct on something that was neither a class nor an object")
    }
    is ConstructTupleExp -> ex.values.map { interpret(it, scope) }.wrap()
    is MatchExp -> {
      val base = interpret(ex.base, scope).unwrap<Any?>()

      ex.patterns.forEach { pattern ->
        val raw = interpret(pattern.base, scope)
        val patternBase = raw.unwrap<Any?>()

        if (patternBase == Wildcard || patternBase == base) {
          if (pattern.guard == null) {
            return interpret(pattern.body, scope)
          } else {
            if (interpret(pattern.guard, scope).unwrap<Boolean>()) {
              return interpret(pattern.body, scope)
            }
          }
        }
      }

      ex.pos.fail("No pattern in match clause passed!")
    }
    is BlockExp -> {
      val local = scope.child()
      var result: JValue = JNull

      ex.body.forEach { state ->
        result = when (state) {
          is ExpressionStatement -> interpret(state.ex, local)
          is AssignmentStatement -> {
            local[state.name] = interpret(state.body, local)
            JNull
          }
          is FunctionStatement -> {
            local[state.name] = state.body.makeJFunction(local)
            JNull
          }
          is TypeStatement, is ImportStatement -> JNull
          is DeconstructDataStatement -> {
            val values = interpret(state.base, local) as JObject

            state.values.forEach { (inside, outside) ->
              val value = values.fields[outside]

              if (value == null) {
                throw NoSuchElementException("Could not find field named $outside in object")
              }

              local[inside] = value.wrap()
            }

            JNull
          }
          is DeconstructTupleStatement -> {
            val values = interpret(state.base, local).unwrap<List<JValue>>()

            state.names.zip(values).forEach { (name, value) ->
              local[name] = value
            }

            JNull
          }
          is DebuggerStatement -> {
            println("Debugger point")
            JNull
          }
        }
      }

      result
    }
  }
}

private fun callCheck(call: CallExp): CallExp {
  val fn = call.func

  return if (fn is BinaryOpExp && fn.op == ".") {
    call.copy(arguments = listOf(fn.left) + call.arguments)
  } else {
    call
  }
}

sealed class JValue

object JNull: JValue() { override fun toString() = "null" }
object Wildcard: JValue() { override fun toString() = "_" }
data class JAtom(val name: String): JValue() { override fun toString() = name }
data class JTrampoline(val func: () -> JValue): JValue() {
  override fun toString() = "<Function>"
  operator fun invoke(): JValue = func()
}
data class JClass(val name: String, val instanceMethods: Map<String, JFunction>, val staticMethods: Map<String, JFunction>): JValue() {
  override fun toString() = name
}
data class JObject(val fields: Map<String, Any?>, val jClass: JClass): JValue() {
  override fun toString(): String {
    return if (jClass.instanceMethods.containsKey("toString")) {
      (jClass.instanceMethods["toString"] as JFunction)(listOf(this)).unwrap()
    } else {
      fields.entries.joinToString(prefix = "${jClass.name} {", postfix = "}") { "${it.key}: ${it.value}" }
    }
  }
}
data class JFunction(val func: (List<JValue>) -> JValue): JValue() {
  override fun toString() = "<Function>"
  operator fun invoke(args: List<JValue>): JValue = func(args)
  fun trampoline(args: List<JValue>) = JTrampoline { func(args) }
}


private inline fun <reified T> JValue.unwrap(): T {
  return when (this) {
    is JObject -> fields["@src"] as T
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

private fun Any?.wrap(): JValue {
  return if (this == null) {
    JNull
  } else {
    val clazz = when (this) {
      is JNull -> return this
      is JObject -> return this
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

private val jError = JClass("Error", emptyMap(), mapOf("new" to JFunction { Exception( it[1].unwrap<String>()).wrap() }))

private val jString = JClass("String", mapOf(
    "getCharAt" to JFunction {
      val (rawStr, rawIndex) = it
      val str = rawStr.unwrap<String>()
      val index = rawIndex.unwrap<Int>()

      if (index >= str.length) {
        println("uh oh")
      }

      str[index].wrap()
    },
    "toUpperCase" to JFunction {
      it[0].unwrap<String>().toUpperCase().wrap()
    },
    "isEmpty" to JFunction {
      it[0].unwrap<String>().isEmpty().wrap()
    },
    "append" to JFunction {
      val (left, right) = it
      (left.unwrap<String>() + right.unwrap<Any?>().toString()).wrap()
    },
    "contains" to JFunction {
      val (rawStr, rawChar) = it

      rawStr.unwrap<String>().contains(rawChar.unwrap<Char>()).wrap()
    },
    "replace" to JFunction {
      val (rawStr, rawPattern, rawReplace) = it
      val self = rawStr.unwrap<String>()
      val pattern = rawPattern.unwrap<String>()
      val replace = rawReplace.unwrap<String>()

      self.replace(pattern, replace).wrap()
    },
    "toString" to JFunction { it[0] }
  ), mapOf()
)

private val jChar = JClass(
  name = "Char",
  instanceMethods = mapOf(
    "toString" to JFunction { it[0].unwrap<Char>().toString().wrap() }
  ),
  staticMethods = mapOf()
)

private val jList = JClass(
  name = "List",
  staticMethods =  mapOf(
    "of" to JFunction { it.drop(1).wrap() }
  ),
  instanceMethods = mapOf(
    "add" to JFunction {
      val (rawList, rawNew) = it
      val list = rawList.unwrap<List<JValue>>()
      (list + rawNew).wrap()
    },
    "prepend" to JFunction {
      val (rawList, rawNew) = it
      val list = rawList.unwrap<List<JValue>>()
      (listOf(rawNew) + list).wrap()
    },
    "get" to JFunction {
      val (rawList, rawIndex) = it
      val list = rawList.unwrap<List<JValue>>()
      val index = rawIndex.unwrap<Int>()
      if (index >= list.size) {
        println("uh oh")
      }
      list[index]
    },
    "head" to JFunction {
      it[0].unwrap<List<JValue>>().first()
    },
    "last" to JFunction {
      it[0].unwrap<List<JValue>>().last()
    },
    "tail" to JFunction {
      it[0].unwrap<List<JValue>>().drop(1).wrap()
    },
    "init" to JFunction {
      it[0].unwrap<List<JValue>>().dropLast(1).wrap()
    },
    "isEmpty" to JFunction {
      it[0].unwrap<List<JValue>>().isEmpty().wrap()
    },
    "concat" to JFunction {
      val (rawSelf, rawOther) = it
      val self = rawSelf.unwrap<List<JValue>>()
      val other = rawOther.unwrap<List<JValue>>()
      (self + other).wrap()
    },
    "filter" to JFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.filter { item ->
        func(listOf(item)).unwrap<Boolean>()
      }.wrap()
    },
    "map" to JFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      val result = list.map { item ->
        func(listOf(item))
      }

      result.wrap()
    },
    "fold" to JFunction {
      val (rawList, rawInit, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.fold(rawInit) { sum, next -> func(listOf(sum, next)) }
    },
    "forEach" to JFunction {
      val (rawList, rawFunc) = it
      val list = rawList.unwrap<List<JValue>>()
      val func = rawFunc as JFunction

      list.forEach { item ->
        func(listOf(item))
      }

      JNull
    }
  )
)

private val jSet = JClass(
  name = "Set",
  staticMethods = mapOf(
    "of" to JFunction {
      it.toSet().wrap()
    }
  ),
  instanceMethods = mapOf(
    "contains" to JFunction {
      val (rawSet, rawValue) = it
      val set = rawSet.unwrap<Set<JValue>>()
      (rawValue in set).wrap()
    }
  )
)

private val jMap = JClass(
  name = "Map",
  staticMethods = mapOf(
    "of" to JFunction { args ->
      args.drop(1).associate { pair ->
        val (key, value) = pair.unwrap<List<JValue>>();
        key to value
      }.wrap()
    },
    "from" to JFunction { args ->
      args[1].unwrap<List<JValue>>().associate { pair ->
        val (key, value) = pair.unwrap<List<JValue>>();
        key to value
      }.wrap()
    }
  ),
  instanceMethods = mapOf(
    "set" to JFunction { args ->
      val (rawMap, rawKey, rawValue) = args
      val map = rawMap.unwrap<Map<JValue, JValue>>()
      (map + (rawKey to rawValue)).wrap()
    },
    "get" to JFunction { args ->
      val (rawMap, rawKey) = args
      val map = rawMap.unwrap<Map<JValue, JValue>>()
      map[rawKey] ?: JNull
    },
    "contains" to JFunction { args ->
      val (rawMap, rawKey) = args
      val map = rawMap.unwrap<Map<JValue, JValue>>()
      map.containsKey(rawKey).wrap()
    },
    "entries" to JFunction { args ->
      args[0].unwrap<Map<JValue, JValue>>().entries.map { it.toPair().toList().wrap() }.wrap()
    }
  )
)

private val jFile = JClass(
  name = "File",
  staticMethods = mapOf(
    "from" to JFunction {
      val path = it[1].unwrap<String>()
      FileImpl(path).wrap()
    }
  ),
  instanceMethods = mapOf(
    "walkFiles" to JFunction { args -> args[0].unwrap<FileImpl>().walkFiles().map { it.wrap() }.wrap() },
    "readText" to JFunction { it[0].unwrap<FileImpl>().readText().wrap() },
    "path" to JFunction { it[0].unwrap<FileImpl>().path.wrap() },
    "extension" to JFunction { it[0].unwrap<FileImpl>().extension().wrap() },
    "relativePath" to JFunction { args ->
      val (self, other) = args
      self.unwrap<FileImpl>().relativePath(other.unwrap<FileImpl>()).map { it.wrap() } .wrap()
    }
  )
)

private fun LambdaExp.makeJFunction(context: Scope): JFunction {
  return JFunction { args: List<JValue> ->
    val local = context.child()

    this.args.zip(args).forEach { (key, value) ->
      local[key] = value
    }

    try {
      interpret(this.body, local)
    } catch (e: ReturnException) {
      e.value
    }
  }
}

val templateFun = JFunction { args ->
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

val jPrintln = JFunction { args ->
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
    "@template" to templateFun
  ), null)
}

