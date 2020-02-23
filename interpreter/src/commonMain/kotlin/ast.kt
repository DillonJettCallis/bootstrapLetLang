
enum class AccessModifier {
  Public,
  Protected,
  Internal,
  Private
}

sealed class AstFragment {
  abstract val pos: Position
}

// Only available at top level
sealed class Declaration: AstFragment() {
  abstract val access: AccessModifier
}

// Only available in a block
sealed class Statement: AstFragment()

// Usable inside of anything
sealed class Expression: AstFragment() {
  abstract val type: Type
}

data class AstModule(val declarations: List<Declaration>)

data class MatchPattern(val base: Expression, val guard: Expression?, val body: Expression, val pos: Position)

data class NullLiteralExp(override val pos: Position): Expression() { override val type = NullType }
data class BooleanLiteralExp(val value: Boolean, override val pos: Position): Expression() { override val type = BooleanType }
data class NumberLiteralExp(val value: String, override val pos: Position): Expression() { override val type = FloatType }
data class StringLiteralExp(val value: String, override val pos: Position): Expression(){ override val type = StringType }
data class ListLiteralExp(val args: List<Expression>, override val type: Type, override val pos: Position): Expression()
data class CharLiteralExp(val value: Char, override val pos: Position): Expression(){ override val type = CharType }
data class IdentifierExp(val name: String, override val type: Type, override val pos: Position): Expression()
data class BinaryOpExp(val op: String, val left: Expression, val right: Expression, override val type: Type, override val pos: Position): Expression()
data class UnaryOpExp(val op: String, val ex: Expression, override val type: Type, override val pos: Position): Expression()
data class BlockExp(val body: List<Statement>, override val type: Type, override val pos: Position): Expression()
data class CallExp(val func: Expression, val arguments: List<Expression>, override val type: Type, override val pos: Position): Expression()
data class LambdaExp(val args: List<String>, val body: Expression, override val type: FunctionType, override val pos: Position): Expression()
data class IfExp(val condition: Expression, val thenExp: Expression, val elseExp: Expression?, override val type: Type, override val pos: Position): Expression()
data class ReturnExp(val ex: Expression, override val pos: Position): Expression() { override val type = NothingType }
data class ThrowExp(val ex: Expression, override val pos: Position): Expression() { override val type = NothingType }
data class ConstructExp(val base: Expression, val values: List<Pair<String, Expression>>, override val type: Type, override val pos: Position): Expression()
data class ConstructTupleExp(val values: List<Expression>, override val type: Type, override val pos: Position): Expression()
data class MatchExp(val base: Expression, val patterns: List<MatchPattern>, override val type: Type, override val pos: Position): Expression()

data class ExpressionStatement(val ex: Expression, override val pos: Position): Statement()
data class AssignmentStatement(val name: String, val body: Expression, override val pos: Position): Statement()
data class FunctionStatement(val name: String, val body: LambdaExp, override val pos: Position): Statement()
data class TypeStatement(val name: String, val value: Type, override val pos: Position): Statement()
data class ImportStatement(val packageName: String, val modulePath: List<String>, override val pos: Position): Statement()
data class DeconstructDataStatement(val base: Expression, val values: List<Pair<String, String>>, override val pos: Position): Statement()
data class DeconstructTupleStatement(val base: Expression, val names: List<String>, override val pos: Position): Statement()

data class AtomDeclare(val name: String, override val access: AccessModifier, override val pos: Position): Declaration()
data class DataDeclare(val name: String, val body: Map<String, Type>, override val access: AccessModifier, override val pos: Position): Declaration()
data class TypeDeclare(val type: TypeStatement, override val access: AccessModifier, override val pos: Position): Declaration()
data class FunctionDeclare(val func: FunctionStatement, override val access: AccessModifier, override val pos: Position): Declaration()
data class ImportDeclare(val import: ImportStatement, override val pos: Position): Declaration() { override val access = AccessModifier.Private }
data class ConstantDeclare(val assign: AssignmentStatement, override val access: AccessModifier, override val pos: Position): Declaration()
data class ProtocolDeclare(val name: String, val funcs: List<Pair<String, Type>>, override val access: AccessModifier, override val pos: Position): Declaration()
data class ImplDeclare(val base: Type, val proto: Type?, val funcs: List<FunctionDeclare>, override val access: AccessModifier, override val pos: Position): Declaration()

