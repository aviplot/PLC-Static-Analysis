/*
 * CODESYS V3 / TwinCAT 3 Structured Text lexer: IEC core (StLexer) + CODESYS keywords.
 * Rules defined here take priority over the imported core rules.
 */
lexer grammar CodesysStLexer;

options { caseInsensitive = true; }

import StLexer;

channels { COMMENTS, PRAGMAS, ERRORS }

PROPERTY        : 'PROPERTY';
END_PROPERTY    : 'END_PROPERTY';
GET             : 'GET';
END_GET         : 'END_GET';
SET             : 'SET';
END_SET         : 'END_SET';
UNION           : 'UNION';
END_UNION       : 'END_UNION';
POINTER         : 'POINTER';
REFERENCE       : 'REFERENCE';
VAR_STAT        : 'VAR_STAT';
VAR_INST        : 'VAR_INST';
VAR_GENERIC     : 'VAR_GENERIC';
PERSISTENT      : 'PERSISTENT';
JMP             : 'JMP';
TRY             : '__TRY';
CATCH           : '__CATCH';
FINALLY         : '__FINALLY';
ENDTRY          : '__ENDTRY';
