/*
 * IEC 61131-3 Ed. 3 Structured Text — shared parser core.
 *
 * Imported by dialect parsers. Extension points are small "list" rules (libraryElement, memberDeclaration,
 * typeSpec, varSectionKeyword, varQualifier, statement, statementList, assignmentTail, condition, softKeyword):
 * a dialect overrides one by re-listing the core sub-rules plus its own, never by copying whole rules.
 *
 * Notes and spec references: docs/grammar/codesys.md.
 */
parser grammar StParser;

// ================================================================================================
// Entry points
// ================================================================================================

/** A complete source text: .st files, PLCopen ST bodies with declarations. */
compilationUnit
    : libraryElement* EOF
    ;

/**
 * TwinCAT <Declaration>: a POU/method/property header and its VAR blocks (no END_...), TYPE blocks, or the VAR blocks
 * of a property accessor (which may start with its own access modifier). Deliberately lenient about order.
 */
declarationPart
    : declarationItem* EOF
    ;

declarationItem
    : dataTypeDeclaration
    | pouHeader SEMI?
    | varSection
    | modifier
    ;

/** TwinCAT <ST> implementation, bare action/snippet files. */
statementsOnly
    : statementList EOF
    ;

/** A lone expression: PLCopen initial values, graphical variables, inline SFC conditions. */
expressionOnly
    : expression EOF
    ;

/** A TwinCAT transition object written as its condition, which may end with ';' (`bStart AND bReady;`). */
transitionCondition
    : expression SEMI? EOF
    ;

// ================================================================================================
// Library elements and POUs (IEC 6.6)
//
// A POU's closing END_xxx may be replaced by end of input: CODESYS text exports (and the declaration +
// implementation views of the IDE) write a POU as header, VAR blocks and body with no closing keyword.
// ================================================================================================

libraryElement
    : programDeclaration
    | functionDeclaration
    | functionBlockDeclaration
    | classDeclaration
    | interfaceDeclaration
    | methodDeclaration
    | actionDeclaration
    | dataTypeDeclaration
    | varSection
    | namespaceDeclaration
    | usingDirective
    | configurationDeclaration
    ;

pouHeader
    : programHeader
    | functionHeader
    | functionBlockHeader
    | classHeader
    | interfaceHeader
    | methodHeader
    ;

programHeader       : PROGRAM modifier* identifier ;
functionHeader      : FUNCTION modifier* identifier (COLON typeSpec)? ;
functionBlockHeader : FUNCTION_BLOCK modifier* identifier inheritance ;
classHeader         : CLASS modifier* identifier inheritance ;
interfaceHeader     : INTERFACE modifier* identifier (EXTENDS qualifiedNameList)? ;
methodHeader        : METHOD modifier* identifier (COLON typeSpec)? ;

modifier
    : PUBLIC | PRIVATE | PROTECTED | INTERNAL | ABSTRACT | FINAL | OVERRIDE
    ;

inheritance
    : (EXTENDS qualifiedName | IMPLEMENTS qualifiedNameList)*
    ;

programDeclaration
    : programHeader SEMI? varSection* pouBody (END_PROGRAM | EOF)
    ;

functionDeclaration
    : functionHeader SEMI? varSection* pouBody (END_FUNCTION | EOF)
    ;

functionBlockDeclaration
    : functionBlockHeader SEMI? varSection* pouBody (END_FUNCTION_BLOCK | EOF)
    ;

classDeclaration
    : classHeader SEMI? varSection* pouBody (END_CLASS | EOF)
    ;

/** Members and statements may interleave: exporters put methods before or after the body. */
pouBody
    : (memberDeclaration | statement)*
    ;

memberDeclaration
    : methodDeclaration
    | actionDeclaration
    ;

methodDeclaration
    : methodHeader SEMI? varSection* statementList (END_METHOD | EOF)
    ;

actionDeclaration
    : ACTION identifier COLON? statementList (END_ACTION | EOF)
    ;

interfaceDeclaration
    : interfaceHeader SEMI? memberDeclaration* (END_INTERFACE | EOF)
    ;

namespaceDeclaration
    : NAMESPACE INTERNAL? qualifiedName libraryElement* END_NAMESPACE
    ;

usingDirective
    : USING qualifiedNameList SEMI
    ;

// ---- configuration elements (IEC 6.8) ----------------------------------------------------------

configurationDeclaration
    : CONFIGURATION identifier configurationItem* END_CONFIGURATION
    ;

configurationItem
    : varSection
    | resourceDeclaration
    | taskConfiguration
    | programConfiguration
    ;

resourceDeclaration
    : RESOURCE identifier ON qualifiedName configurationItem* END_RESOURCE
    ;

taskConfiguration
    : TASK identifier LPAREN argumentList? RPAREN SEMI
    ;

programConfiguration
    : PROGRAM (RETAIN | NON_RETAIN)? identifier (WITH identifier)? COLON qualifiedName
      (LPAREN argumentList? RPAREN)? SEMI
    ;

// ================================================================================================
// Variable declarations (IEC 6.5)
// ================================================================================================

/** Stray `;` between declarations are accepted (TwinCAT compiles `x : INT;;`). */
varSection
    : varSectionKeyword varQualifier* (varDeclaration | SEMI)* END_VAR
    ;

varSectionKeyword
    : VAR | VAR_INPUT | VAR_OUTPUT | VAR_IN_OUT | VAR_GLOBAL | VAR_EXTERNAL | VAR_TEMP | VAR_ACCESS | VAR_CONFIG
    ;

varQualifier
    : CONSTANT | RETAIN | NON_RETAIN
    ;

varDeclaration
    : variablePath (COMMA variablePath)* location? COLON typeSpec variableInit? SEMI
    | identifier COLON variablePath COLON typeSpec (READ_ONLY | READ_WRITE)? SEMI      // VAR_ACCESS
    ;

/** A plain name; VAR_CONFIG declares instance paths (`P1.fb.x AT %QX0.0 : BOOL;`). */
variablePath
    : identifier (DOT identifier)*
    ;

location
    : AT DIRECT_ADDRESS
    ;

variableInit
    : ASSIGN initializer
    ;

initializer
    : arrayInitializer
    | structInitializer
    | expression
    ;

arrayInitializer
    : LBRACK (arrayInitElement (COMMA arrayInitElement)*)? RBRACK
    ;

arrayInitElement
    : INTEGER LPAREN initializer? RPAREN      // repetition: 3(0)
    | initializer
    ;

structInitializer
    : LPAREN structInitElement (COMMA structInitElement)* RPAREN
    ;

structInitElement
    : identifier ASSIGN initializer
    ;

// ================================================================================================
// Types (IEC 6.4)
// ================================================================================================

dataTypeDeclaration
    : TYPE typeDefinition+ END_TYPE SEMI?
    ;

typeDefinition
    : identifier (EXTENDS qualifiedName)? COLON typeSpec (ASSIGN initializer)? SEMI?
    ;

typeSpec
    : arrayType
    | refToType
    | stringType
    | structType
    | enumType
    | namedType
    ;

arrayType       : ARRAY LBRACK arrayDimension (COMMA arrayDimension)* RBRACK OF typeSpec ;
arrayDimension  : expression DOTDOT expression | STAR ;
refToType       : REF_TO typeSpec ;
stringType      : (STRING | WSTRING) (LPAREN expression RPAREN | LBRACK expression RBRACK)? ;
structType      : STRUCT (varDeclaration | SEMI)* END_STRUCT ;
enumType        : LPAREN enumValue (COMMA enumValue)* RPAREN namedType? ;
enumValue       : identifier (ASSIGN expression)? ;
/** A named type, optionally restricted to a subrange: `INT(0..100)`. */
namedType       : qualifiedName (LPAREN expression DOTDOT expression RPAREN)? ;

// ================================================================================================
// Statements (IEC 7.3.3)
// ================================================================================================

statementList
    : statement*
    ;

statement
    : designatorStatement
    | ifStatement
    | caseStatement
    | forStatement
    | whileStatement
    | repeatStatement
    | exitStatement
    | continueStatement
    | returnStatement
    | emptyStatement
    ;

/** Assignment or call statement. Factored so the decision needs no lookahead across the designator. */
designatorStatement
    : designator assignmentTail? SEMI
    ;

/** `a := b := 0;` (chained assignment) is accepted: CODESYS compiles it. */
assignmentTail
    : (ASSIGN | ATTEMPT_ASSIGN) (designator ASSIGN)* expression
    ;

ifStatement
    : IF condition THEN statementList elsifClause* (ELSE statementList)? END_IF
    ;

elsifClause
    : ELSIF condition THEN statementList
    ;

/** The condition of IF / ELSIF / WHILE / UNTIL; CODESYS extends it with ExST (an assignment as the condition). */
condition
    : expression
    ;

caseStatement
    : CASE expression OF caseElement* (ELSE statementList)? END_CASE
    ;

caseElement
    : caseLabel (COMMA caseLabel)* COLON caseBody
    ;

caseLabel
    : expression (DOTDOT expression)?
    ;

/** Statements of a CASE branch; never jump labels, which would be ambiguous with enum case labels. */
caseBody
    : statement*
    ;

forStatement
    : FOR designator ASSIGN expression TO expression (BY expression)? DO statementList END_FOR
    ;

whileStatement
    : WHILE condition DO statementList END_WHILE
    ;

repeatStatement
    : REPEAT statementList UNTIL condition END_REPEAT
    ;

exitStatement       : EXIT SEMI ;
continueStatement   : CONTINUE SEMI ;
/** `RETURN expr;` is not IEC; accepted leniently because TwinCAT projects in the corpus use it (verify). */
returnStatement     : RETURN expression? SEMI ;
emptyStatement      : SEMI ;

// ================================================================================================
// Expressions (IEC 7.3.2). Lowest precedence last; ** binds tighter than unary minus.
// ================================================================================================

expression
    : primaryExpression                                         # primaryExpr
    | <assoc=right> expression POWER expression                 # powerExpr
    | op=(MINUS | PLUS | NOT) expression                        # unaryExpr
    | expression op=(STAR | SLASH | MOD) expression             # mulExpr
    | expression op=(PLUS | MINUS) expression                   # addExpr
    | expression op=(LT | GT | LE | GE) expression              # relExpr
    | expression op=(EQ | NE) expression                        # eqExpr
    | expression op=(AND | AMPERSAND | AND_THEN) expression     # andExpr
    | expression op=XOR expression                              # xorExpr
    | expression op=(OR | OR_ELSE) expression                   # orExpr
    ;

primaryExpression
    : literal
    | designator
    | LPAREN expression RPAREN
    ;

/** An lvalue-shaped chain: a.b[i]^.c(x).d */
designator
    : designatorRoot postfixOp*
    ;

designatorRoot
    : identifier
    | THIS
    | SUPER
    | DIRECT_ADDRESS
    | operatorCall
    ;

/** Boolean/arithmetic operators in functional form, as generated by OpenPLC/MATIEC: `AND(a, b)`. */
operatorCall
    : (AND | OR | XOR | MOD) LPAREN argumentList? RPAREN
    ;

postfixOp
    : DOT identifier                                # memberOp
    | DOT INTEGER                                   # bitOp
    | DEREF                                         # derefOp
    | LBRACK expression (COMMA expression)* RBRACK  # indexOp
    | LPAREN argumentList? RPAREN                   # callOp
    ;

/** A trailing comma before `)` is accepted (seen in TwinCAT code). */
argumentList
    : argument (COMMA argument)* COMMA?
    ;

argument
    : identifier ASSIGN expression?                 # namedArgument
    | NOT? identifier OUTPUT_ASSIGN designator?     # outputArgument
    | expression                                    # positionalArgument
    ;

literal
    : INTEGER
    | BASED_INTEGER
    | REAL
    | TRUE
    | FALSE
    | STRING_LITERAL
    | WSTRING_LITERAL
    | UNTERMINATED_STRING
    | TIME_LITERAL
    | DATE_LITERAL
    | TOD_LITERAL
    | DT_LITERAL
    | typedLiteral
    ;

/** INT#5, WORD#16#FF, REAL#-1.5, BOOL#TRUE, STRING#'x', E_Color#Red */
typedLiteral
    : (identifier | STRING | WSTRING) HASH
      ( (MINUS | PLUS)? (INTEGER | BASED_INTEGER | REAL)
      | TRUE | FALSE | STRING_LITERAL | WSTRING_LITERAL | identifier )
    ;

// ================================================================================================
// Names
// ================================================================================================

qualifiedName
    : identifier (DOT identifier)*
    ;

qualifiedNameList
    : qualifiedName (COMMA qualifiedName)*
    ;

identifier
    : IDENT
    | softKeyword
    ;

/** Keywords that real code also uses as names. */
softKeyword
    : CLASS | NAMESPACE | USING | CONFIGURATION | RESOURCE | TASK | ON | WITH | ACTION | OVERRIDE
    | READ_ONLY | READ_WRITE
    ;
