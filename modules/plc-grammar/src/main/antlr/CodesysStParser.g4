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
    | jmpStatement
    | tryStatement
    | emptyStatement
    ;

/** `S=`, `R=`, `REF=` are `identifier '='`; the AST builder checks the operator name. */
assignmentTail
    : (ASSIGN | ATTEMPT_ASSIGN) (designator ASSIGN)* expression
    | IDENT EQ (designator IDENT EQ)* expression      // chains: a S= b R= c;
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
