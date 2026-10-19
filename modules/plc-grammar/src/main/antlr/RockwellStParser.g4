/*
 * Rockwell Logix 5000 Structured Text parser: IEC core (StParser) + Rockwell names. Routines have no POU header or VAR
 * sections (entry rule statementsOnly); their tags come from the L5X XML.
 *
 * Notes and evidence: docs/grammar/rockwell.md §6.
 */
parser grammar RockwellStParser;

options { tokenVocab = RockwellStLexer; }

import StParser;

@members {
    /** No whitespace between the previous token and the next one: `Local:1:I`, not a CASE label `x : …`. */
    private boolean adjacent() {
        org.antlr.v4.runtime.Token prev = _input.LT(-1);
        org.antlr.v4.runtime.Token next = _input.LT(1);
        return prev != null && next != null && prev.getStopIndex() + 1 == next.getStartIndex();
    }
}

// ---- extension points ---------------------------------------------------------------------------

assignmentTail
    : (ASSIGN | ATTEMPT_ASSIGN) (designator ASSIGN)* expression
    | NONRETENTIVE_ASSIGN expression
    ;

/** No THIS/SUPER: Rockwell ST has no objects, and both are tag names there. */
designatorRoot
    : moduleTag
    | identifier
    | DIRECT_ADDRESS
    | operatorCall
    | BACKSLASH identifier
    ;

/** An argument may be left out: GSV(WallClockTime, , CurrentValue, TNow) (as in rung text). */
argumentList
    : argument (COMMA argument?)*
    | (COMMA argument?)+
    ;

/**
 * Routines have no declarations, so the declaration keywords of the core are tag and member names here
 * (`Ref_Instance.Type`, a tag `Var`). TRUE and FALSE are names too (a project defines `TRUE := 1;`); in an expression
 * the literal alternative wins.
 */
softKeyword
    : CLASS | NAMESPACE | USING | CONFIGURATION | RESOURCE | TASK | ON | WITH | ACTION | OVERRIDE
    | READ_ONLY | READ_WRITE
    | PROGRAM | FUNCTION | FUNCTION_BLOCK | METHOD | INTERFACE | TYPE | STRUCT
    | VAR | VAR_INPUT | VAR_OUTPUT | VAR_IN_OUT | VAR_GLOBAL | VAR_EXTERNAL | VAR_TEMP | VAR_ACCESS | VAR_CONFIG
    | CONSTANT | RETAIN | NON_RETAIN | AT | ARRAY | STRING | WSTRING | REF_TO | EXTENDS | IMPLEMENTS
    | ABSTRACT | FINAL | PUBLIC | PRIVATE | PROTECTED | INTERNAL | THIS | SUPER | TRUE | FALSE
    ;

postfixOp
    : DOT identifier                                # memberOp
    | DOT INTEGER                                   # bitOp
    | DOT LBRACK expression RBRACK                  # indirectBitOp
    | DEREF                                         # derefOp
    | LBRACK expression (COMMA expression)* RBRACK  # indexOp
    | LPAREN argumentList? RPAREN                   # callOp
    ;

// ---- Rockwell-only rules ------------------------------------------------------------------------

/** Module and status tags: `Local:1:I`, `Rack:I1`, `S:FS` (written without blanks around the colons). */
moduleTag
    : identifier ({adjacent()}? COLON {adjacent()}? (identifier | INTEGER))+
    ;
