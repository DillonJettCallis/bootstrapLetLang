
inline fun assemble(vararg args: String, crossinline body: Assembler.() -> Unit): JBytecodeFunction {
  val bler = Assembler(args.toList())
  body(bler)
  return bler.assemble()
}

class Assembler(private val args: List<String>) {

  private val code = ArrayList<Bytecode>()

  fun pop() {
    code += Pop
  }

  fun dup() {
    code += Dup
  }

  fun swap() {
    code += Swap
  }

  fun add() {
    code += Add
  }

  fun subtract() {
    code += Subtract
  }

  fun multiply() {
    code += Multiply
  }

  fun divide() {
    code += Divide
  }

  fun greaterThan() {
    code += GreaterThan
  }

  fun greaterThanOrEqualTo() {
    code += GreaterThanOrEqualTo
  }

  fun lessThan() {
    code += LessThan
  }

  fun lessThanOrEqualTo() {
    code += LessThanOrEqualTo
  }

  fun equalTo() {
    code += EqualTo
  }

  fun notEqualTo() {
    code += NotEqualTo
  }

  fun `is`() {
    code += Is
  }

  fun isNot() {
    code += IsNot
  }

  fun negate() {
    code += Negate
  }

  fun not() {
    code += Not
  }

  fun `return`() {
    code += Return
  }

  fun `throw`() {
    code += Throw
  }

  fun const() {
    code += Const
  }

  fun pushScope() {
    code += PushScope
  }

  fun popScope() {
    code += PopScope
  }

  fun yield() {
    code += Yield
  }

  fun send() {
    code += Send
  }

  fun receive() {
    code += Receive
  }

  fun spawn() {
    code += Spawn
  }

  fun debug() {
    code += Debug
  }

  fun loadLiteral(value: JValue) {
    code += LoadLiteral(value)
  }

  fun loadLocal(name: String, pos: Position = Position.native) {
    code += LoadLocal(name, pos)
  }

  fun storeLocal(name: String) {
    code += StoreLocal(name)
  }

  fun call(args: Int) {
    code += Call(args)
  }

  fun tailCall(args: Int) {
    code += TailCall(args)
  }

  fun access(name: String, pos: Position = Position.native) {
    code += Access(name, pos)
  }

  fun listAccess(index: Int) {
    code += ListAccess(index)
  }

  fun `if`(elseBlock: (Assembler.() -> Unit)? = null, thenBlock: Assembler.() -> Unit) {
    val elseLabel = Label()
    val endIfLabel = Label()

    code += IfLabeled(elseLabel)
    thenBlock(this)

    if (elseBlock != null) {
      code += JumpLabeled(endIfLabel)
      code += elseLabel
      elseBlock(this)
    } else {
      code += elseLabel
    }

    code += endIfLabel
  }

  fun `while`(condition: Assembler.() -> Unit, body: Assembler.() -> Unit) {
    val initLabel = Label()
    val endLabel = Label()

    code += initLabel
    condition(this)
    code += IfLabeled(endLabel)
    body(this)
    code += JumpLabeled(initLabel)
    code += endLabel
  }

  fun assemble(): JBytecodeFunction {
    assemble(code)
    return JBytecodeFunction(BytecodeFunction(args, code, Position.native), Scope(HashMap(), null))
  }
}
