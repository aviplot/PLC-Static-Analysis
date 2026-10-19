/*
 * Rockwell Logix 5000 rung text ("neutral text" of L5X <Rung><Text>): its own small grammar (grammar rule 6).
 * Tag names are never keywords; AND/OR/XOR/NOT/MOD are tokens because they are expression operators, and the parser
 * also accepts them as instruction names (AND(a,b,dest)).
 *
 * Notes and evidence: docs/grammar/rockwell.md §3.
 */
lexer grammar RungLexer;

options { caseInsensitive = true; }

// The shared parser kit (CoreTokens) looks these channel names up; rung text has no comments or pragmas.
channels { COMMENTS, PRAGMAS, ERRORS }

AND             : 'AND';
OR              : 'OR';
XOR             : 'XOR';
NOT             : 'NOT';
MOD             : 'MOD';

// 10#1: decimal radix written out (seen in exports with Decimal display style)
BASED_INTEGER   : ('2' | '8' | '10' | '16') '#' [0-9A-F_]+ ;
REAL            : DIGITS '.' DIGITS EXPONENT? | DIGITS EXPONENT ;
INTEGER         : DIGITS ;
STRING_LITERAL  : '\'' ( '$' . | ~['$\r\n] )* '\'' ;
// No closing quote on the line: reported by the AST builder.
UNTERMINATED_STRING : '\'' ( '$' . | ~['$\r\n] )* ;

POWER           : '**';
STAR            : '*';
SLASH           : '/';
PLUS            : '+';
MINUS           : '-';
NE              : '<>';
LE              : '<=';
GE              : '>=';
EQ              : '=';
LT              : '<';
GT              : '>';
LPAREN          : '(';
RPAREN          : ')';
LBRACK          : '[';
RBRACK          : ']';
COMMA           : ',';
SEMI            : ';';
DOT             : '.';
COLON           : ':';
BACKSLASH       : '\\';
QUESTION        : '?';
PERCENT         : '%';

IDENT           : IDENT_START IDENT_PART* ;

WS              : [ \t\r\n\f\u000B﻿]+ -> skip ;
ERROR_CHAR      : . -> channel(ERRORS) ;

fragment DIGITS      : [0-9] ( '_'? [0-9] )* ;
fragment EXPONENT    : 'E' [+-]? DIGITS ;
fragment IDENT_START : [A-Z_] | [À-῿぀-퟿豈-￯] ;
fragment IDENT_PART  : IDENT_START | [0-9] ;
