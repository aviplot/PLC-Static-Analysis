/*
 * Rockwell Logix 5000 Structured Text (L5X <STContent> routines): IEC core (StLexer) + Rockwell tokens.
 * Rules defined here take priority over the imported core rules.
 *
 * Notes and evidence: docs/grammar/rockwell.md §6.
 */
lexer grammar RockwellStLexer;

options { caseInsensitive = true; }

import StLexer;

channels { COMMENTS, PRAGMAS, ERRORS }

/** Non-retentive assignment `a [:=] b;` (PM007). */
NONRETENTIVE_ASSIGN : '[' [ \t]* ':=' [ \t]* ']' ;
/** `#region text` / `#endregion` lines fold code in the ST editor: kept like pragmas, not parsed. */
REGION_DIRECTIVE    : '#' [ \t]* ( 'REGION' | 'ENDREGION' ) ~[\r\n]* -> channel(PRAGMAS) ;
/** Motion instruction settings written as one word: MAJ(…, %ofMaximum, …). An identifier for the parser. */
SETTING_WORD        : '%' [A-Z_] [A-Z0-9_]* ;
/** `\Prog.Tag`: a parameter of another program. */
BACKSLASH           : '\\' ;

// Comments do not nest in Studio 5000 (a Festo library opens `(*` and nests `(*…*)` inside it, relying on the first
// `*)` to close): the core's nesting comment rules are replaced.
BLOCK_COMMENT       : '(*' .*? ( '*)' | EOF ) -> channel(COMMENTS) ;
C_COMMENT           : '/*' .*? ( '*/' | EOF ) -> channel(COMMENTS) ;
