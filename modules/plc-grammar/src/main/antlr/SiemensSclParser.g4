/*
 * Siemens SCL parser: IEC core (StParser) + SCL blocks, header attributes, `#` locals, regions, slices.
 * Overrides only the core's extension-point rules and adds the SCL block rules; see StParser.g4.
 *
 * Notes and evidence: docs/grammar/siemens-scl.md.
 */
parser grammar SiemensSclParser;

options { tokenVocab = SiemensSclLexer; }

import StParser;

// ---- entry points -------------------------------------------------------------------------------

/**
 * A source file: blocks, with anything between them kept as stray tokens. A block broken beyond repair then costs only
 * the rest of that block: recovery cannot resynchronise a `libraryElement*` loop while still in error mode, so without
 * this the parser would skip every following block. The builder reports stray tokens.
 */
compilationUnit
    : (libraryElement | strayToken)* EOF
    ;

strayToken
    : ~(ORGANIZATION_BLOCK | FUNCTION_BLOCK | FUNCTION | DATA_BLOCK | TYPE | NAMESPACE | EOF)
    ;

// ---- extension points ---------------------------------------------------------------------------

/** TIA V19+ software units wrap blocks in `NAMESPACE a.b … END_NAMESPACE` (core rule). */
libraryElement
    : organizationBlock
    | sclFunctionBlock
    | sclFunction
    | dataBlock
    | dataTypeDeclaration
    | namespaceDeclaration
    ;

varSectionKeyword
    : VAR | VAR_INPUT | VAR_OUTPUT | VAR_IN_OUT | VAR_TEMP | VAR_STAT | VAR_GLOBAL | VAR_EXTERNAL
    ;

varQualifier
    : CONSTANT | RETAIN | NON_RETAIN | DB_SPECIFIC
    ;

/** `name AT other : T` overlays another variable; `AT %…` is the core form. */
location
    : AT (DIRECT_ADDRESS | identifier)
    ;

varDeclaration
    : variablePath (COMMA variablePath)* location? COLON typeSpec variableInit? SEMI
    ;

/** Declared names may be keywords (`continue : Bool;` in the Siemens LGF library). */
variablePath
    : declaredName (DOT declaredName)*
    ;

declaredName
    : identifier
    | ARRAY | STRING | WSTRING | TYPE | STRUCT | OF | TO | BY | DO | IF | THEN | ELSE | ELSIF | CASE | FOR | WHILE
    | REPEAT | UNTIL | EXIT | CONTINUE | RETURN | GOTO | NOT | AND | OR | XOR | MOD | TRUE | FALSE | REF_TO
    ;

/** `:= 1, 2, 3` and `:= 32(0)`: array initial values without brackets (TIA and classic). */
variableInit
    : ASSIGN (bareArrayInit | initializer)
    ;

bareArrayInit
    : arrayInitElement (COMMA arrayInitElement)+
    | INTEGER LPAREN initializer? RPAREN
    ;

/** Core forms plus `()`: an element that keeps its default (`[()]` for an array of structs). */
arrayInitElement
    : INTEGER LPAREN initializer? RPAREN
    | LPAREN RPAREN
    | initializer
    ;

/** TIA: `TYPE "x" VERSION : 0.1 STRUCT … END_STRUCT; END_TYPE` (no colon); IEC style with a colon also occurs. */
typeDefinition
    : identifier COLON? blockAttribute* typeSpec (ASSIGN initializer)? SEMI?
    ;

statementList
    : (statement | labelDefinition)*
    ;

pouBody
    : (statement | labelDefinition)*
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
    | gotoStatement
    | regionStatement
    | emptyStatement
    ;

/** Core forms plus compound assignment `x += 1;`. */
assignmentTail
    : (ASSIGN | ATTEMPT_ASSIGN) (designator ASSIGN)* expression
    | op=(ADD_ASSIGN | SUB_ASSIGN | MUL_ASSIGN | DIV_ASSIGN) expression
    ;

/**
 * Core named form `(a := 1, b := 2)`, plus TIA's positional form `(1, (), T#1s)` in declaration initial values, where
 * `()` keeps a member's default. A one-element positional list must not be an expression (`([()])`, `(())`), so
 * `(x)` stays a parenthesised expression.
 */
structInitializer
    : LPAREN structInitElement (COMMA structInitElement)* RPAREN
    | LPAREN positionalInit (COMMA positionalInit)+ RPAREN
    | LPAREN singlePositional RPAREN
    ;

singlePositional
    : LPAREN RPAREN
    | arrayInitializer
    | structInitializer
    ;

positionalInit
    : LPAREN RPAREN
    | initializer
    ;

/** `ELSE:` with a colon is accepted (31 corpus files). */
caseStatement
    : CASE expression OF caseElement* (ELSE COLON? statementList)? END_CASE
    ;

/** `#local`, `#"quoted local"`, `"Global"`, `%I0.0`, `P#DB1.DBX0.0 BYTE 4`. */
designatorRoot
    : identifier
    | HASH localName
    | DIRECT_ADDRESS
    | ANY_POINTER
    ;

/** Core forms plus `String`/`WString` as type operands: `TypeOf(#v) = String`. */
primaryExpression
    : literal
    | designator
    | LPAREN expression RPAREN
    | (STRING | WSTRING)
    ;

/** After `#` every word is a name: the Siemens LGF library has parameters `#array`, `#string`, `#type`. */
localName
    : identifier
    | ARRAY | STRING | WSTRING | TYPE | STRUCT | OF | TO | BY | DO | AT | IF | THEN | ELSE | ELSIF | CASE | FOR
    | WHILE | REPEAT | UNTIL | EXIT | CONTINUE | RETURN | GOTO | BEGIN | VAR | CONSTANT | RETAIN | NON_RETAIN
    | NOT | AND | OR | XOR | MOD | TRUE | FALSE | REF_TO | DATA_BLOCK | FUNCTION | FUNCTION_BLOCK | UNLINKED
    ;

/** Parameter names may be keywords: `retain := FALSE` (Siemens LMQTT), `Function := 3`, `Array => #x`, `At => #y`. */
argument
    : argumentName ASSIGN expression?               # namedArgument
    | NOT? argumentName OUTPUT_ASSIGN designator?   # outputArgument
    | expression                                    # positionalArgument
    ;

argumentName
    : localName | RETAIN | NON_RETAIN | CONSTANT | AT
    ;

/** `.#member` (lenient: seen in 9 repositories, not documented — verify). */
postfixOp
    : DOT HASH? localName                           # memberOp
    | DOT INTEGER                                   # bitOp
    | SLICE                                         # sliceOp
    | DEREF                                         # derefOp
    | LBRACK expression (COMMA expression)* RBRACK  # indexOp
    | LPAREN argumentList? RPAREN                   # callOp
    ;

literal
    : INTEGER
    | BASED_INTEGER
    | REAL
    | TRUE
    | FALSE
    | STRING_LITERAL
    | UNTERMINATED_STRING
    | TIME_LITERAL
    | S5TIME_LITERAL
    | DATE_LITERAL
    | TOD_LITERAL
    | DT_LITERAL
    | typedLiteral
    ;

/** W#16#FF, DINT#-1, WSTRING#'x', CHAR#'a' */
typedLiteral
    : (identifier | STRING | WSTRING) HASH
      ( (MINUS | PLUS)? (INTEGER | BASED_INTEGER | REAL)
      | TRUE | FALSE | STRING_LITERAL | identifier )
    ;

identifier
    : IDENT
    | QUOTED_IDENT
    | softKeyword
    ;

/** SCL reserves far fewer words than CODESYS: the IEC OOP and configuration keywords are names here. */
softKeyword
    : CLASS | NAMESPACE | USING | CONFIGURATION | RESOURCE | TASK | ON | WITH | ACTION | OVERRIDE
    | READ_ONLY | READ_WRITE | PROGRAM | METHOD | INTERFACE | EXTENDS | IMPLEMENTS | THIS | SUPER
    | ABSTRACT | FINAL | PUBLIC | PRIVATE | PROTECTED | INTERNAL | VAR_ACCESS | VAR_CONFIG
    | VERSION | AUTHOR | FAMILY | NAME | KNOW_HOW_PROTECT | DB_SPECIFIC | CONST | LABEL | UNLINKED
    ;

// ---- SCL blocks ---------------------------------------------------------------------------------

organizationBlock
    : ORGANIZATION_BLOCK identifier blockAttribute* declarationSection* BEGIN? pouBody
      (END_ORGANIZATION_BLOCK | EOF)
    ;

sclFunctionBlock
    : FUNCTION_BLOCK identifier blockAttribute* declarationSection* BEGIN? pouBody (END_FUNCTION_BLOCK | EOF)
    ;

sclFunction
    : FUNCTION identifier (COLON typeSpec)? blockAttribute* declarationSection* BEGIN? pouBody (END_FUNCTION | EOF)
    ;

/** A global DB (own STRUCT or VAR layout), an instance DB of `"FB"`, or a DB typed by `"UDT"`; then initial values. */
dataBlock
    : DATA_BLOCK identifier blockAttribute* dataBlockLayout? BEGIN? statementList (END_DATA_BLOCK | EOF)
    ;

dataBlockLayout
    : structType SEMI?
    | declarationSection+
    | qualifiedName
    ;

blockAttribute
    : TITLE_LINE                                                                    # titleAttribute
    | key=(AUTHOR | FAMILY | NAME | VERSION) COLON value=attributeValue?            # keyValueAttribute
    | KNOW_HOW_PROTECT                                                              # knowHowAttribute
    | UNLINKED                                                                      # unlinkedAttribute
    | (RETAIN | NON_RETAIN)                                                         # retainAttribute
    ;

/**
 * No keyword tokens: `VERSION :` may be empty and followed by another header word (`UNLINKED`) on the next line.
 * Dotted versions `0.0.1` lex as REAL `0.0` + `.1`.
 */
attributeValue
    : STRING_LITERAL | (REAL | INTEGER) (DOT INTEGER)* | IDENT | QUOTED_IDENT
    ;

declarationSection
    : varSection
    | constSection
    | labelSection
    ;

/** Classic: `CONST name := value; END_CONST`. */
constSection
    : CONST (constDeclaration | SEMI)* END_CONST SEMI?
    ;

constDeclaration
    : identifier ASSIGN expression SEMI
    ;

/** Classic: `LABEL a, b; END_LABEL` (jump labels declared up front). */
labelSection
    : LABEL identifier (COMMA identifier)* SEMI? END_LABEL SEMI?
    ;

// ---- SCL statements -----------------------------------------------------------------------------

gotoStatement
    : GOTO identifier SEMI
    ;

labelDefinition
    : identifier COLON
    ;

regionStatement
    : REGION_LINE statementList END_REGION SEMI?
    ;
