/*
 * Siemens SCL lexer (TIA Portal external sources; classic STEP 7 SCL where cheap): IEC core (StLexer) + SCL tokens.
 * Rules defined here take priority over the imported core rules, and a rule with a core rule's name replaces it.
 *
 * Notes and evidence: docs/grammar/siemens-scl.md.
 */
lexer grammar SiemensSclLexer;

options { caseInsensitive = true; }

import StLexer;

channels { COMMENTS, PRAGMAS, ERRORS }

@members {
    /** True if only blanks precede the token start on its line: header and region lines must start a line. */
    private boolean atLineStart() {
        for (int i = _tokenStartCharIndex - 1; i >= 0; i--) {
            int c = _input.getText(org.antlr.v4.runtime.misc.Interval.of(i, i)).charAt(0);
            if (c == '\n' || c == '\r') {
                return true;
            }
            if (c != ' ' && c != '\t' && c != '﻿') {
                return false;
            }
        }
        return true;
    }

    /**
     * After {@code REGION} at the start of a line: false when the rest of the line makes it a variable named
     * {@code region}: {@code region := 1;}, {@code region : Int;}, {@code region, x : Int;}, {@code region = x},
     * {@code region.x}, {@code region[1]}, or a call {@code region(…)} (a region name may start with "(" after a blank).
     */
    private boolean regionHeader() {
        if (!atLineStart()) {
            return false;
        }
        int k = 1;
        while (_input.LA(k) == ' ' || _input.LA(k) == '\t') {
            k++;
        }
        int c = _input.LA(k);
        return !(c == ':' || c == ',' || c == '=' || c == '.' || c == '[' || c == '(' && k == 1);
    }
}

// ---- block and section keywords -----------------------------------------------------------------
ORGANIZATION_BLOCK      : 'ORGANIZATION_BLOCK';
END_ORGANIZATION_BLOCK  : 'END_ORGANIZATION_BLOCK';
DATA_BLOCK              : 'DATA_BLOCK';
END_DATA_BLOCK          : 'END_DATA_BLOCK';
BEGIN                   : 'BEGIN';
END_REGION              : 'END_REGION';
GOTO                    : 'GOTO';
CONST                   : 'CONST';
END_CONST               : 'END_CONST';
LABEL                   : 'LABEL';
END_LABEL               : 'END_LABEL';
VAR_STAT                : 'VAR_STAT';
DB_SPECIFIC             : 'DB_SPECIFIC';

// ---- block header attributes (soft keywords) ----------------------------------------------------
VERSION                 : 'VERSION';
AUTHOR                  : 'AUTHOR';
FAMILY                  : 'FAMILY';
NAME                    : 'NAME';
KNOW_HOW_PROTECT        : 'KNOW_HOW_PROTECT';

/** `TITLE = free text` up to the end of the line, only as the first word of a line. */
TITLE_LINE              : 'TITLE' {atLineStart()}? [ \t]* '=' ~[\r\n]* ;

/** `REGION [free text]` up to the end of the line, only as the first word of a line and not used as a variable. */
REGION_LINE             : 'REGION' {regionHeader()}? ( [ \t] ~[\r\n]* )? ;

// ---- names --------------------------------------------------------------------------------------
/** `"Motor_DB"`, `"Controll_vertikale Achse"`: a name, never a string, in SCL. Ties with the core WSTRING_LITERAL. */
QUOTED_IDENT            : '"' ~["\r\n]* '"' ;

// ---- literals and addresses ---------------------------------------------------------------------
S5TIME_LITERAL          : ('S5T' | 'S5TIME') '#' DURATION_PART+ ;

/** Core forms plus `DTL#2019-01-31-12:00:00.123456789`. */
DT_LITERAL              : ('DT' | 'DTL' | 'DATE_AND_TIME' | 'LDT' | 'LDATE_AND_TIME') '#' DIGITS '-' DIGITS '-' DIGITS '-' DAYTIME ;

/** Core `%I/%Q/%M` forms plus `%DB1.DBX0.0` / `%DB1.DBW2`, each with an optional peripheral `:P` (verify). */
DIRECT_ADDRESS
    : '%' ( [IQM] [XBWDL]? ( '*' | DIGITS ( '.' DIGITS )* )
          | 'DB' DIGITS '.' 'DB' [XBWD] DIGITS ( '.' DIGITS )?
          ) ( ':P' )?
    ;

/** Slice access `x.%X3`, `w.%B1`: wins over DOT by being longer. */
SLICE                   : '.%' [XBWD] DIGITS ;

// ---- core rules SCL replaces --------------------------------------------------------------------
/** SCL has no `"…"` strings (it writes `WSTRING#'…'`); the core rule is disabled so `"` always starts a name. */
WSTRING_LITERAL         : '"' '"' {false}? ;
UNTERMINATED_STRING     : '\'' ( '$' . | ~['$] )* EOF ;

/** Not nested in SCL: `(*(*text*)` is one comment followed by code (17 corpus files). */
BLOCK_COMMENT           : '(*' .*? ( '*)' | EOF ) -> channel(COMMENTS) ;
C_COMMENT               : '/*' .*? ( '*/' | EOF ) -> channel(COMMENTS) ;
