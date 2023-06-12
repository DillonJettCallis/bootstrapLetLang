class Scope(val values: MutableMap<String, JValue>, val parent: Scope?) {

  operator fun get(key: String, pos: Position): JValue {
    return this.values.getOrElse(key) {
      if (parent != null)
        parent[key, pos]
      else
        pos.fail("No such value $key in scope")
    }
  }

  operator fun set(key: String, value: JValue) {
    this.values[key] = value
  }

  fun setAll(names: List<String>, values: List<JValue>) {
    names.zip(values).forEach { (name, value) -> this[name] = value }
  }

  fun child(): Scope {
    return Scope(HashMap(), this)
  }

}

sealed interface JValue

object JNull: JValue { override fun toString() = "null" }
object Wildcard: JValue { override fun toString() = "_" }
data class JAtom(val name: String): JValue { override fun toString() = name }
data class JClass(val name: String, val fields: Set<String>, val instanceMethods: Map<String, JFunction>, val staticMethods: Map<String, JFunction>): JValue {
  override fun toString() = name
}

class JConst(base: JBytecodeFunction): JValue {
  var value: JConstValue = Lazy(base)
  override fun toString() = "<Const>"

  sealed interface JConstValue

  data class Lazy(val base: JBytecodeFunction): JConstValue
  data class Actual(val value: JValue): JConstValue

}

data class JObject(val fields: Map<String, Any?>, val jClass: JClass): JValue {
  override fun toString(): String {
    return if (jClass.instanceMethods.containsKey("toString")) {
      (jClass.instanceMethods["toString"] as JNativeFunction)(listOf(this)).unwrap()
    } else {
      fields.entries.joinToString(prefix = "${jClass.name} {", postfix = "}") { "${it.key}: ${it.value}" }
    }
  }
}

sealed interface JFunction: JValue

data class JNativeFunction(val func: (List<JValue>) -> JValue): JFunction {
  override fun toString() = "<Function>"
  operator fun invoke(args: List<JValue>): JValue = func(args)
}
data class JBytecodeFunction(val func: BytecodeFunction, val scope: Scope): JFunction
sealed interface Channel: JValue

data class SendChannel(val impl: ChannelImpl): Channel
data class ReceiveChannel(val impl: ChannelImpl): Channel

class ChannelImpl {
  private var state: ChannelMode = None

  fun send(item: JValue, listener: () -> Unit): Boolean {
    return when (val state = state) {
      is None -> {
        val queue = ArrayDeque<Pair<JValue, () -> Unit>>()
        queue.addLast(item to listener)
        this.state = Sending(queue)
        false
      }
      is Sending -> {
        state.blocked.addLast(item to listener)
        false
      }
      is Receiving -> {
        val source = state.listener.removeFirst()

        if (state.listener.isEmpty()) {
          this.state = None
        }

        source(item)
        true
      }
    }
  }

  fun receive(listener: (JValue) -> Unit): JValue? {
    return when (val state = state) {
      is None -> {
        this.state = Receiving(ArrayDeque<(JValue) -> Unit>().also { it.addLast(listener) })
        null
      }
      is Sending -> {
        val (value, src) = state.blocked.removeFirst()

        if (state.blocked.isEmpty()) {
          this.state = None
        }

        src()
        value
      }
      is Receiving -> {
        state.listener.addLast(listener)
        null
      }
    }
  }

  sealed interface ChannelMode

  // TODO: Support for closing a channel and error handling along with that
  object None: ChannelMode
  data class Sending(val blocked: ArrayDeque<Pair<JValue, () -> Unit>>): ChannelMode
  data class Receiving(val listener: ArrayDeque<(JValue) -> Unit>): ChannelMode

}

inline fun <reified T> JValue.unwrap(): T {
  return when (this) {
    is JObject -> {
      val raw = fields["@src"]

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

fun test() {
  println("Test!")
}

private val jAnyClass = JClass("Any", emptySet(), emptyMap(), emptyMap())

fun Any?.wrap(): JValue {
  return if (this == null) {
    JNull
  } else {
    val clazz = when (this) {
      is JAtom -> return this
      is JNull -> return this
      is JObject -> return this
      is String -> jString
      is Char -> jChar
      is Exception -> jError
      is List<*> -> jList
      is Set<*> -> jSet
      is Map<*, *> -> jMap
      is FileImpl -> jFile
      else -> jAnyClass
    }

    JObject(mapOf("@src" to this), clazz)
  }
}

private val jError = JClass("Error", emptySet(), emptyMap(), mapOf("new" to JNativeFunction { Exception( it[1].unwrap<String>()).wrap() }))

private val jString = JClass("String", emptySet(), mapOf(
    "size" to JNativeFunction {
      val (rawStr) = it
      val str = rawStr.unwrap<String>()
      str.length.wrap()
    },
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
  fields = emptySet(),
  instanceMethods = mapOf(
    "toString" to JNativeFunction { it[0].unwrap<Char>().toString().wrap() }
  ),
  staticMethods = mapOf()
)

private val createMutableList = JNativeFunction { ArrayList<JValue>().wrap() }
private val addMutableList = JNativeFunction {
  val (rawList, item) = it
  val list = rawList.unwrap<MutableList<JValue>>()
  list.add(item)
  list.wrap()
}
private val sizeList = JNativeFunction {
  val (rawList) = it
  val list = rawList.unwrap<List<JValue>>()
  list.size.wrap()
}
private val getList = JNativeFunction {
  val (rawList, rawIndex) = it
  val list = rawList.unwrap<List<JValue>>()
  val index = rawIndex.unwrap<Int>()
  if (index >= list.size) {
    println("uh oh")
  }
  list[index]
}


private inline fun Assembler.forEachList(listKey: String, crossinline before: Assembler.() -> Unit = {}, crossinline action: Assembler.() -> Unit) {
  loadLiteral(0.wrap()) // (index) [list]
  storeLocal("forEachList_index") // () [list, index]
  loadLiteral(sizeList) // (sizeList) [list, index]
  loadLocal(listKey) // (sizeList, list) [list, index]
  call(1) // (size) [list, index]
  storeLocal("forEachList_size") // () [list, index, size]

  `while`(condition = {
    loadLocal("forEachList_size") // (size) [list, index, size]
    loadLocal("forEachList_index") // (size, index) [list, index, size]
    greaterThan() // (isNotDone) [list, index, size]
  }, body = {
    // () [list, index, size]
    before()
    loadLiteral(getList) // (getList) [list, index, size]
    loadLocal(listKey) // (getList, this, index) [list, index, size]
    loadLocal("forEachList_index") // (getList, this, index) [list, index, size]
    dup() // (getList, this, index, index) [list, index, size]
    loadLiteral(1.wrap()) // (getList, this, index, index, 1) [list, index, size]
    add() // (getList, this, index, nextIndex) [list, index, size]
    storeLocal("forEachList_index") // (getList, this, index) [list, index, size]
    call(2) // (item) [list, index, size]
    action()
  })
}

private val jList = JClass(
  name = "List",
  fields = emptySet(),
  staticMethods =  mapOf(
    "of" to JNativeFunction { it.drop(1).wrap() }
  ),
  instanceMethods = mapOf(
    "size" to sizeList,
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
    "get" to getList,
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
    "zip" to JNativeFunction { args ->
      val (rawSelf, rawOther) = args
      val self = rawSelf.unwrap<List<JValue>>()
      val other = rawOther.unwrap<List<JValue>>()

      self.zip(other).wrap()
    },
    "slice" to JNativeFunction {
      val (rawList, rawBegin, rawEnd) = it;
      val list = rawList.unwrap<List<JValue>>()
      val begin = rawBegin.unwrap<Int>()
      val end = rawEnd.unwrap<Int>()

      list.subList(begin, end).wrap()
    },
    "findIndex" to Assembler(listOf("this", "tester")).apply {
      // () [this, tester]
      loadLiteral(sizeList) // (sizeList) [this, tester]
      loadLocal("this") // (sizeList, this) [this, tester]
      call(1) // (size) [this, tester]
      storeLocal("size") // () [this, tester, size]
      loadLiteral(0.wrap()) // (index) [this, tester, size]
      storeLocal("index") // () [this, tester, size, index]
      `while`(condition = {
        // () [this, tester, size, index]
        loadLocal("size") // (size) [this, tester, size, index]
        loadLocal("index") // (size, index) [this, tester, size, index]
        greaterThan() // (isNotDone) [this, tester, size, index]
      }, body = {
        // () [this, tester, size, index]

        loadLocal("tester") // (tester, getList) [this, tester, size, index]
        loadLiteral(getList) // (tester, getList) [this, tester, size, index]
        loadLocal("this") // (tester, getList, this) [this, tester, size, index]
        loadLocal("index") // (tester, getList, this, index) [this, tester, size, index]
        call(2) // (tester, item) [this, tester, size, index]
        call(1) // (passed) [this, tester, size, index]
        `if` {
          // () [this, tester, size, index]
          loadLocal("index") // (index) [this, tester, size, index]
          `return`() // () [this, tester, size, index]
        }
        loadLocal("index") // (index) [this, tester, size, index]
        loadLiteral(1.wrap()) // (index, 1) [this, tester, size, index]
        add() // (nextIndex) [this, tester, size, index]
        storeLocal("index") // () [this, tester, size, index]
      })
      loadLiteral((-1).wrap()) // (-1) [this, tester, size, index]
      `return`() // () [this, tester, size, index]
    }.assemble(),
    "filter" to Assembler(listOf("this", "tester")).apply {
      // () [this, tester]
      loadLiteral(createMutableList) // (createMutableList) [this, tester]
      call(0) // (mutableList) [this, tester]
      storeLocal("mutableList") // () [this, tester, mutableList]
      loadLiteral(0.wrap()) // (index) [this, tester, mutableList]
      storeLocal("index") // () [this, tester, mutableList, index]
      loadLiteral(sizeList) // (sizeList) [this, tester]
      loadLocal("this") // (sizeList, this) [this, tester]
      call(1) // (size) [this, tester]
      storeLocal("size") // () [this, tester, mutableList, index, size]

      `while`(condition = {
        loadLocal("size") // (size) [this, tester, mutableList, index, size]
        loadLocal("index") // (size, index) [this, tester, mutableList, index, size]
        greaterThan() // (isNotDone) [this, tester, mutableList, index, size]
      }, body = {
        // () [this, tester, mutableList, index, size]
        loadLiteral(getList) // (getList) [this, tester, mutableList, index, size]
        loadLocal("this") // (getList, this, index) [this, tester, mutableList, index, size]
        loadLocal("index") // (getList, this, index) [this, tester, mutableList, index, size]
        dup() // (getList, this, index, index) [this, tester, mutableList, index, size]
        loadLiteral(1.wrap()) // (getList, this, index, index, 1) [this, tester, mutableList, index, size]
        add() // (getList, this, index, nextIndex) [this, tester, mutableList, index, size]
        storeLocal("index") // (getList, this, index) [this, tester, mutableList, index, size]
        call(2) // (item) [this, tester, mutableList, index, size]
        dup() // (item, item) [this, tester, mutableList, index, size]
        loadLocal("tester") // (item, item, tester) [this, tester, mutableList, index, size]
        swap() // (item, tester, item) [this, tester, mutableList, index, size]
        call(1) // (item, passed) [this, tester, mutableList, index, size]
        `if` {
          // (item) [this, tester, mutableList, index, size]
          loadLiteral(addMutableList) // (item, addMutableList) [this, tester, mutableList, index, size]
          swap() // (addMutableList, item) [this, tester, mutableList, index, size]
          loadLocal("mutableList") // (addMutableList, item, mutableList) [this, tester, mutableList, index, size]
          swap() // (addMutableList, mutableList, item) [this, tester, mutableList, index, size]
          call(2) // (mutableList) [this, tester, mutableList, index, size]
        }
        pop() // () [this, tester, mutableList, index, size]
      })
      // () [this, tester, mutableList, index, size]
      loadLocal("mutableList") // (mutableList) [this, tester, mutableList, index, size]
      `return`() // () [this, tester, mutableList, index, size]
    }.assemble(),
    "map" to Assembler(listOf("this", "mapper")).apply {
      // () [this, mapper]
      loadLiteral(createMutableList) // (createMutableList) [this, mapper]
      call(0) // (mutableList) [this, mapper]
      storeLocal("mutableList") // () [this, mapper, mutableList]

      forEachList("this") {
        // (item) [this, mapper, mutableList]
        loadLiteral(addMutableList) // (item, addMutableList) [this, mapper, mutableList]
        swap() // (addMutableList, item) [this, mapper, mutableList]
        loadLocal("mutableList") // (addMutableList, item, mutableList) [this, mapper, mutableList]
        swap() // (addMutableList, mutableList, item) [this, mapper, mutableList]
        loadLocal("mapper") // (addMutableList, mutableList, item, mapper) [this, mapper, mutableList]
        swap() // (addMutableList, mutableList, mapper, item) [this, mapper, mutableList]
        call(1) // (addMutableList, mutableList, newItem) [this, mapper, mutableList]
        call(2) // (mutableList) [this, mapper, mutableList]
        pop() // () [this, mapper, mutableList]
      }
      // () [this, mapper, mutableList]
      loadLocal("mutableList") // (mutableList) [this, mapper, mutableList]
      `return`() // () [this, mapper, mutableList]
    }.assemble(),
    "fold" to assemble("this", "sum", "merger") {
      // () [this, sum, merger]
      forEachList("this", before = {
        // () [this, sum, merger]
        loadLocal("merger") // (merger) [this, sum, merger]
        loadLocal("sum") // (merger, sum) [this, sum, merger]
      }) {
        // (merger, sum, item) [this, sum, merger]
        call(2) // (newSum) [this, sum, merger]
        storeLocal("sum") // () [this, sum, merger]
      }
      loadLocal("sum") // (sum) [this, sum, merger]
      `return`() // () [this, sum, merger]
    },
    "forEach" to assemble("this", "action") {
      // () [this, action]
      forEachList("this", before = {
        // () [this, action]
        loadLocal("action") // (action) [this, action]
      }) {
        // (action, item) [this, action]
        call(1) // (result) [this, action]
        pop() // () [this, action]
      }
      loadLiteral(JNull) // (null) [this, action]
      `return`() // () [this, action]
    },
    "toString" to JNativeFunction { args ->
      args[0].unwrap<List<JValue>>().joinToString(", ", "[", "]") { it.toString() }.wrap()
    },
  )
)

private val jSet = JClass(
  name = "Set",
  fields = emptySet(),
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
    "concat" to JNativeFunction {
      val (rawLeft, rawRight) = it
      val left = rawLeft.unwrap<Set<JValue>>()
      val right = rawRight.unwrap<Set<JValue>>()

      (left + right).wrap()
    },
    "add" to JNativeFunction {
      val (rawSet, value) = it
      val set = rawSet.unwrap<Set<JValue>>()
      (set + value).wrap()
    },
    "map" to JNativeFunction {
      val (rawSet, rawFunc) = it
      val set = rawSet.unwrap<Set<JValue>>()
      val func = rawFunc as JNativeFunction

      val result = set.mapTo(HashSet()) { item ->
        func(listOf(item))
      }

      result.wrap()
    },
  )
)

private val jMap = JClass(
  name = "Map",
  fields = emptySet(),
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
    "entries" to JNativeFunction { args ->
      args[0].unwrap<Map<JValue, JValue>>().entries.map { it.toPair().toList().wrap() }.wrap()
    },
    "keys" to JNativeFunction { args ->
      args[0].unwrap<Map<JValue, JValue>>().keys.map { it.wrap() }.wrap()
    },
    "values" to JNativeFunction { args ->
      args[0].unwrap<Map<JValue, JValue>>().values.map { it.wrap() }.wrap()
    },
    "concat" to JNativeFunction { args ->
      val (rawFirst, rawSecond) = args
      val result = rawFirst.unwrap<Map<JValue, JValue>>() + rawSecond.unwrap<Map<JValue, JValue>>()
      result.wrap()
    }
  )
)

private val jFile = JClass(
  name = "File",
  fields = emptySet(),
  staticMethods = mapOf(
    "from" to JNativeFunction {
      val path = it[1].unwrap<String>()
      FileImpl(path).wrap()
    }
  ),
  instanceMethods = mapOf(
    "walkFiles" to JNativeFunction { args -> args[0].unwrap<FileImpl>().walkFiles().map { it.wrap() }.wrap() },
    "readText" to JNativeFunction { it[0].unwrap<FileImpl>().readText().wrap() },
    "path" to JNativeFunction { it[0].unwrap<FileImpl>().path.wrap() },
    "extension" to JNativeFunction { it[0].unwrap<FileImpl>().extension().wrap() },
    "relativePath" to JNativeFunction { args ->
      val (self, other) = args
      self.unwrap<FileImpl>().relativePath(other.unwrap<FileImpl>()).map { it.wrap() } .wrap()
    }
  )
)

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

val jPrintln = JNativeFunction { args ->
  println(args.joinToString(" "){ it.toString() })
  JNull
}

val jConstruct = JNativeFunction { args ->
  // args is [@JClass, string, value, string, value ..]

  val props = args.asSequence().drop(1).windowed(2, 2) { (key, value) -> key.unwrap<String>() to value }.toMap()

  when (val base = args.first()) {
    is JClass -> {
      if (props.keys != base.fields) {
        throw IllegalStateException("Attempt to construct something with missing or extra fields!")
      }

      JObject(props, base)
    }
    is JObject -> {
      JObject(base.fields + props, base.jClass)
    }
    else -> throw IllegalStateException("Attempt to construct something that's not a class or object")
  }
}

fun initCoreScope(): Scope {
  return Scope(hashMapOf(
    "_" to Wildcard,
    "Error" to jError,
    "List" to jList,
    "Set" to jSet,
    "Map" to jMap,
    "File" to jFile,
    "println" to jPrintln,
    "@template" to templateFun,
    "@construct" to jConstruct,
  ), null)
}

