/*
 * IEC 61131-3 Ed. 3 Structured Text — shared lexer core.
 *
 * Imported by the dialect lexers (CodesysStLexer, later SiemensSclLexer). Never generated on its own:
 * the importing grammar declares the channels and `caseInsensitive = true`, and its own rules take
 * priority over these (keywords it adds are matched before IDENT below).
 *
 * Notes and spec references: docs/grammar/codesys.md.
 */
lexer grammar StLexer;

// ---- POU and declaration keywords ---------------------------------------------------------------
PROGRAM             : 'PROGRAM';
END_PROGRAM         : 'END_PROGRAM';
FUNCTION            : 'FUNCTION';
END_FUNCTION        : 'END_FUNCTION';
FUNCTION_BLOCK      : 'FUNCTION_BLOCK';
END_FUNCTION_BLOCK  : 'END_FUNCTION_BLOCK';
METHOD              : 'METHOD';
END_METHOD          : 'END_METHOD';
INTERFACE           : 'INTERFACE';
END_INTERFACE       : 'END_INTERFACE';
CLASS               : 'CLASS';
END_CLASS           : 'END_CLASS';
NAMESPACE           : 'NAMESPACE';
END_NAMESPACE       : 'END_NAMESPACE';
USING               : 'USING';
ACTION              : 'ACTION';
END_ACTION          : 'END_ACTION';
TYPE                : 'TYPE';
END_TYPE            : 'END_TYPE';
STRUCT              : 'STRUCT';
END_STRUCT          : 'END_STRUCT';
CONFIGURATION       : 'CONFIGURATION';
END_CONFIGURATION   : 'END_CONFIGURATION';
RESOURCE            : 'RESOURCE';
END_RESOURCE        : 'END_RESOURCE';
TASK                : 'TASK';
ON                  : 'ON';
WITH                : 'WITH';
READ_ONLY           : 'READ_ONLY';
READ_WRITE          : 'READ_WRITE';

VAR                 : 'VAR';
VAR_INPUT           : 'VAR_INPUT';
VAR_OUTPUT          : 'VAR_OUTPUT';
VAR_IN_OUT          : 'VAR_IN_OUT';
VAR_GLOBAL          : 'VAR_GLOBAL';
VAR_EXTERNAL        : 'VAR_EXTERNAL';
VAR_TEMP            : 'VAR_TEMP';
VAR_ACCESS          : 'VAR_ACCESS';
VAR_CONFIG          : 'VAR_CONFIG';
END_VAR             : 'END_VAR';
CONSTANT            : 'CONSTANT';
RETAIN              : 'RETAIN';
NON_RETAIN          : 'NON_RETAIN';
AT                  : 'AT';

ARRAY               : 'ARRAY';
OF                  : 'OF';
STRING              : 'STRING';
WSTRING             : 'WSTRING';
REF_TO              : 'REF_TO';

EXTENDS             : 'EXTENDS';
IMPLEMENTS          : 'IMPLEMENTS';
THIS                : 'THIS';
SUPER               : 'SUPER';
ABSTRACT            : 'ABSTRACT';
FINAL               : 'FINAL';
OVERRIDE            : 'OVERRIDE';
PUBLIC              : 'PUBLIC';
PRIVATE             : 'PRIVATE';
PROTECTED           : 'PROTECTED';
INTERNAL            : 'INTERNAL';

// ---- statement keywords -------------------------------------------------------------------------
IF                  : 'IF';
THEN                : 'THEN';
ELSIF               : 'ELSIF';
ELSE                : 'ELSE';
END_IF              : 'END_IF';
CASE                : 'CASE';
END_CASE            : 'END_CASE';
FOR                 : 'FOR';
TO                  : 'TO';
BY                  : 'BY';
DO                  : 'DO';
END_FOR             : 'END_FOR';
WHILE               : 'WHILE';
END_WHILE           : 'END_WHILE';
REPEAT              : 'REPEAT';
UNTIL               : 'UNTIL';
END_REPEAT          : 'END_REPEAT';
EXIT                : 'EXIT';
CONTINUE            : 'CONTINUE';
RETURN              : 'RETURN';

// ---- word operators and boolean literals --------------------------------------------------------
// AND_THEN / OR_ELSE are CODESYS short-circuit operators. They live in the core so the shared
// expression rule can use them; dialects without them simply never produce code that uses them.
AND_THEN            : 'AND_THEN';
OR_ELSE             : 'OR_ELSE';
MOD                 : 'MOD';
NOT                 : 'NOT';
AND                 : 'AND';
OR                  : 'OR';
XOR                 : 'XOR';
TRUE                : 'TRUE';
FALSE               : 'FALSE';

// ---- literals (IEC 6.3) -------------------------------------------------------------------------
TIME_LITERAL        : ('T' | 'TIME' | 'LT' | 'LTIME') '#' '-'? DURATION_PART+ ;
DATE_LITERAL        : ('D' | 'DATE' | 'LD' | 'LDATE') '#' DIGITS '-' DIGITS '-' DIGITS ;
TOD_LITERAL         : ('TOD' | 'TIME_OF_DAY' | 'LTOD' | 'LTIME_OF_DAY') '#' DAYTIME ;
DT_LITERAL          : ('DT' | 'DATE_AND_TIME' | 'LDT' | 'LDATE_AND_TIME') '#' DIGITS '-' DIGITS '-' DIGITS '-' DAYTIME ;

// 10# is not IEC, but CODESYS/TwinCAT accept it and real code uses it.
BASED_INTEGER       : ('2' | '8' | '10' | '16') '#' [0-9A-F_]+ ;
REAL                : DIGITS '.' DIGITS EXPONENT? | DIGITS EXPONENT ;
INTEGER             : DIGITS ;

// TwinCAT compiles string literals that span lines, so newlines are allowed inside.
STRING_LITERAL      : '\'' ( '$' . | ~['$] )* '\'' ;
WSTRING_LITERAL     : '"' ( '$' . | ~["$] )* '"' ;
// No closing quote before the end of the text: reported by the AST builder.
UNTERMINATED_STRING : '\'' ( '$' . | ~['$] )* EOF | '"' ( '$' . | ~["$] )* EOF ;

DIRECT_ADDRESS      : '%' [IQM] [XBWDL]? ( '*' | DIGITS ( '.' DIGITS )* ) ;

// ---- operators and punctuation ------------------------------------------------------------------
ASSIGN              : ':=';
OUTPUT_ASSIGN       : '=>';
ATTEMPT_ASSIGN      : '?=';
NE                  : '<>';
LE                  : '<=';
GE                  : '>=';
EQ                  : '=';
LT                  : '<';
GT                  : '>';
POWER               : '**';
STAR                : '*';
SLASH               : '/';
PLUS                : '+';
MINUS               : '-';
AMPERSAND           : '&';
DEREF               : '^';
DOTDOT              : '..';
DOT                 : '.';
COMMA               : ',';
SEMI                : ';';
COLON               : ':';
LPAREN              : '(';
RPAREN              : ')';
LBRACK              : '[';
RBRACK              : ']';
HASH                : '#';

// ---- identifiers --------------------------------------------------------------------------------
IDENT               : IDENT_START IDENT_PART* ;

// ---- hidden: comments, pragmas, whitespace ------------------------------------------------------
// CODESYS nests (* *) and /* */; an unterminated one runs to EOF (reported by the AST builder).
BLOCK_COMMENT       : '(*' ( BLOCK_COMMENT | . )*? ( '*)' | EOF ) -> channel(COMMENTS) ;
C_COMMENT           : '/*' ( C_COMMENT | . )*? ( '*/' | EOF ) -> channel(COMMENTS) ;
LINE_COMMENT        : '//' ~[\r\n]* -> channel(COMMENTS) ;
// Ends at the first '}' (as in CODESYS), so unbalanced quotes inside cannot swallow code; may span lines.
PRAGMA              : '{' ~[}]* '}' -> channel(PRAGMAS) ;
WS                  : [ \t\r\n\f\u000B ﻿]+ -> skip ;

// Anything else: kept on its own channel so the parser carries on and the builder reports it.
ERROR_CHAR          : . -> channel(ERRORS) ;

fragment DIGITS     : [0-9] ( '_'? [0-9] )* ;
fragment EXPONENT   : 'E' [+-]? DIGITS ;
fragment DURATION_PART : [0-9] [0-9_]* ( '.' [0-9_]+ )? ( 'D' | 'H' | 'M' | 'S' | 'MS' | 'US' | 'NS' ) '_'? ;
fragment DAYTIME    : DIGITS ':' DIGITS ( ':' DIGITS ( '.' DIGITS )? )? ;
// Non-ASCII letters (umlauts, CJK, ...) occur in real identifiers; CODESYS warns but accepts them.
fragment IDENT_START : [A-Z_] | [\u00C0-\u1FFF\u3040-\uD7FF\uF900-\uFFEF] ;
fragment IDENT_PART  : IDENT_START | [0-9] ;
