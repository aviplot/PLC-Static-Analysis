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
    : ~(ORGANIZATION_BLOCK | FUNCTION_BLOCK | FUNCTION | DATA_BLOCK | TYPE | EOF)
    ;

// ---- extension points ---------------------------------------------------------------------------

libraryElement
    : organizationBlock
    | sclFunctionBlock
    | sclFunction
    | dataBlock
    | dataTypeDeclaration
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

/** `ELSE:` with a colon is accepted (31 corpus files). */
caseStatement
    : CASE expression OF caseElement* (ELSE COLON? statementList)? END_CASE
    ;

/** `#local`, `#"quoted local"`, `"Global"`, `%I0.0`. */
designatorRoot
    : identifier
    | HASH identifier
    | DIRECT_ADDRESS
    ;

postfixOp
    : DOT identifier                                # memberOp
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
    | VERSION | AUTHOR | FAMILY | NAME | KNOW_HOW_PROTECT | DB_SPECIFIC | CONST | LABEL
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
    | key=(AUTHOR | FAMILY | NAME | VERSION) COLON value=attributeValue             # keyValueAttribute
    | KNOW_HOW_PROTECT                                                              # knowHowAttribute
    | (RETAIN | NON_RETAIN)                                                         # retainAttribute
    ;

attributeValue
    : STRING_LITERAL | REAL | INTEGER | identifier
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
