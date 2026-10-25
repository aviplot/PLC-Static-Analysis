/*
 * CODESYS V3 / TwinCAT 3 Structured Text parser: IEC core (StParser) + CODESYS extensions.
 * Overrides only the core's extension-point rules; see StParser.g4.
 */
parser grammar CodesysStParser;

options { tokenVocab = CodesysStLexer; }

import StParser;

// ---- extension points ---------------------------------------------------------------------------

pouHeader
    : programHeader
    | functionHeader
    | functionBlockHeader
    | classHeader
    | interfaceHeader
    | methodHeader
    | propertyHeader
    ;

libraryElement
    : programDeclaration
    | functionDeclaration
    | functionBlockDeclaration
    | classDeclaration
    | interfaceDeclaration
    | methodDeclaration
    | propertyDeclaration
    | actionDeclaration
    | dataTypeDeclaration
    | varSection
    | namespaceDeclaration
    | usingDirective
    | configurationDeclaration
    ;

/**
 * Generic function blocks (CODESYS help "VAR_GENERIC CONSTANT"; TwinCAT 3.1.4026): the generic constants come
 * before EXTENDS / IMPLEMENTS, as in `FUNCTION_BLOCK F VAR_GENERIC CONSTANT … END_VAR IMPLEMENTS I`. The grammar takes
 * the clauses after any VAR section; the AST builder reports them unless they follow a leading VAR_GENERIC section.
 */
functionBlockDeclaration
    : functionBlockHeader SEMI? (varSection (inheritanceClause SEMI?)*)* pouBody (END_FUNCTION_BLOCK | EOF)
    ;

/** TwinCAT `<Declaration>`: same as the core, plus inheritance clauses after a VAR_GENERIC section. */
declarationItem
    : dataTypeDeclaration
    | pouHeader SEMI?
    | varSection
    | inheritanceClause SEMI?
    | modifier
    ;

inheritance
    : inheritanceClause*
    ;

/** Generic base: `EXTENDS FB_Base<maxlen>`. */
inheritanceClause
    : EXTENDS qualifiedName genericArguments?
    | IMPLEMENTS qualifiedNameList
    ;

memberDeclaration
    : methodDeclaration
    | propertyDeclaration
    | actionDeclaration
    ;

varSectionKeyword
    : VAR | VAR_INPUT | VAR_OUTPUT | VAR_IN_OUT | VAR_GLOBAL | VAR_EXTERNAL | VAR_TEMP | VAR_ACCESS | VAR_CONFIG
    | VAR_STAT | VAR_INST | VAR_GENERIC
    ;

/** Access specifiers on VAR sections (`VAR PUBLIC`) are IEC Ed. 3 (classes). */
varQualifier
    : CONSTANT | RETAIN | NON_RETAIN | PERSISTENT | PUBLIC | PRIVATE | PROTECTED | INTERNAL
    ;

typeSpec
    : arrayType
    | refToType
    | pointerType
    | referenceType
    | stringType
    | structType
    | unionType
    | enumType
    | namedType
    ;

statementList
    : (statement | labelDefinition)*
    ;

pouBody
    : (memberDeclaration | statement | labelDefinition)*
    ;

/**
 * `continueStatement` comes first: `CONTINUE;` also matches a designator statement now that `Continue` is a name
 * (see `identifier`), and ANTLR resolves that ambiguity to the lowest alternative.
 */
statement
    : continueStatement
    | designatorStatement
    | ifStatement
    | caseStatement
    | forStatement
    | whileStatement
    | repeatStatement
    | exitStatement
    | returnStatement
    | jmpStatement
    | tryStatement
    | emptyStatement
    ;

/** `S=`, `R=`, `REF=` are `identifier '='`; the AST builder checks the operator name. */
assignmentTail
    : (ASSIGN | ATTEMPT_ASSIGN) (designator ASSIGN)* expression
    | IDENT EQ (designator IDENT EQ)* expression      // chains: a S= b R= c;
    ;

/**
 * ExST (M12): an assignment as the whole condition, `IF b := (i = 1) THEN`. The value is the rest of the expression;
 * the AST builder checks that the left side is a variable. One token of lookahead after the expression decides.
 */
condition
    : expression (ASSIGN expression)?
    ;

/** ExST (M12) inside parentheses: `IF (n := FIND(s, 'x')) > 0 THEN`, `RETURN(b := t <= 0);`. */
primaryExpression
    : literal
    | designator
    | LPAREN expression (ASSIGN expression)? RPAREN
    ;

/** `TYPE INTERNAL E_State : (...); END_TYPE` (M12): library-internal DUTs; the modifier is not kept. */
dataTypeDeclaration
    : TYPE modifier* typeDefinition+ END_TYPE SEMI?
    ;

/** `(Idle := 0, Busy := 1, ) WORD` (M12): a comma after the last value. */
enumType
    : LPAREN enumValue (COMMA enumValue)* COMMA? RPAREN namedType?
    ;

/** `fb : FB_X := ();`, `[15(())]` (M12): an empty initialiser leaves every member at its default. */
structInitializer
    : LPAREN (structInitElement (COMMA structInitElement)*)? RPAREN
    ;

/** `p : REFERENCE TO T REF= x;` initialises a reference in its declaration. */
variableInit
    : ASSIGN initializer
    | IDENT EQ expression
    ;

/**
 * Named type with an optional subrange `INT(0..9)`, TwinCAT generic arguments `FB<5>` (VAR_GENERIC, 4026+) and/or
 * FB_init constructor arguments `fb : FB_X(1, b := 2);`.
 */
namedType
    : qualifiedName genericArguments? (subrange | constructorArguments)?
    ;

subrange
    : LPAREN expression DOTDOT expression RPAREN
    ;

genericArguments
    : LT expression (COMMA expression)* GT
    ;

/** `ARRAY[1..2] OF FB_X[(a := 1), (a := 2)]`: FB_init arguments per element. */
arrayType
    : ARRAY LBRACK arrayDimension (COMMA arrayDimension)* RBRACK OF typeSpec arrayElementInit?
    ;

arrayElementInit
    : LBRACK constructorArguments (COMMA constructorArguments)* RBRACK
    ;

constructorArguments
    : LPAREN argumentList? RPAREN
    ;

/** A leading `.` addresses the global scope: `.gvar`. */
designatorRoot
    : identifier
    | THIS
    | SUPER
    | DIRECT_ADDRESS
    | operatorCall
    | DOT identifier
    ;

/** Types are valid arguments of SIZEOF and similar operators: `SIZEOF(POINTER TO T)`, `SIZEOF(STRING)`. */
argument
    : identifier ASSIGN expression?                 # namedArgument
    | NOT? identifier OUTPUT_ASSIGN designator?     # outputArgument
    | typeArgument                                  # typeArg
    | expression                                    # positionalArgument
    ;

typeArgument
    : pointerType
    | referenceType
    | stringType
    ;

/** `Continue` is a variable name in real code (`fbAxis.Continue := FALSE;`, motion FB inputs). */
identifier
    : IDENT
    | softKeyword
    | CONTINUE
    ;

softKeyword
    : CLASS | NAMESPACE | USING | CONFIGURATION | RESOURCE | TASK | ON | WITH | ACTION | OVERRIDE
    | READ_ONLY | READ_WRITE | GET | SET
    ;

// ---- CODESYS-only rules -------------------------------------------------------------------------

propertyHeader
    : PROPERTY modifier* identifier COLON typeSpec
    ;

/** Text form (no single standard exists): GET/SET blocks are optional, as is `PROPERTY` before them. */
propertyDeclaration
    : propertyHeader SEMI? varSection* propertyAccessor* (END_PROPERTY | EOF)
    ;

propertyAccessor
    : PROPERTY? modifier* (GET | SET) varSection* statementList (END_GET | END_SET)
    ;

pointerType     : POINTER TO typeSpec ;
referenceType   : REFERENCE TO typeSpec ;
unionType       : UNION (varDeclaration | SEMI)* END_UNION ;

jmpStatement
    : JMP identifier SEMI
    ;

labelDefinition
    : identifier COLON
    ;

tryStatement
    : TRY statementList (CATCH (LPAREN identifier RPAREN)? statementList)? (FINALLY statementList)? ENDTRY SEMI?
    ;
