/*
 * Rockwell Logix 5000 rung text. One parse per routine: the routine's rungs are joined with newlines (rungs).
 * Expressions (CPT/CMP/FAL operands, calculated indexes) mirror the precedence of the ST core.
 *
 * Notes and evidence: docs/grammar/rockwell.md §3.
 */
parser grammar RungParser;

options { tokenVocab = RungLexer; }

rungs
    : rung* EOF
    ;

/** A single rung text (L5X TargetType="Rung", tests). */
singleRung
    : rung EOF
    ;

rung
    : element* SEMI
    ;

element
    : instruction
    | branch
    ;

/** Parallel legs; a leg may be empty (a plain wire). */
branch
    : LBRACK leg (COMMA leg)* RBRACK
    ;

leg
    : element*
    ;

/** An operand may be empty: GSV(ControllerDevice,,DeviceName,x), NOP(). */
instruction
    : mnemonic LPAREN operand (COMMA operand)* RPAREN
    ;

mnemonic
    : IDENT | AND | OR | XOR | NOT | MOD
    ;

operand
    : (expression | QUESTION | settingText)?
    ;

/**
 * Settings of safety and motion instructions written as words: DCS(…,EMERGENCY STOP,EQUIVALENT - ACTIVE HIGH,…),
 * MCS(…,Units per sec2,…), "% of Maximum". At least two adjacent words, so `A - B` stays a subtraction.
 */
settingText
    : (settingWord MINUS)* settingWord settingWord (MINUS? settingWord)*
    ;

settingWord
    : IDENT | PERCENT
    ;

// ---- expressions ----------------------------------------------------------------------------------

expression
    : primary                                           # primaryExpr
    | <assoc=right> expression POWER expression         # powerExpr
    | op=(MINUS | PLUS | NOT) expression                # unaryExpr
    | expression op=(STAR | SLASH | MOD) expression     # mulExpr
    | expression op=(PLUS | MINUS) expression           # addExpr
    | expression op=(LT | GT | LE | GE) expression      # relExpr
    | expression op=(EQ | NE) expression                # eqExpr
    | expression op=AND expression                      # andExpr
    | expression op=XOR expression                      # xorExpr
    | expression op=OR expression                       # orExpr
    ;

primary
    : literal
    | functionCall
    | tagPath
    | LPAREN expression RPAREN
    ;

/** ABS(x), SQR(x), TRN(x) inside CPT/CMP expressions. */
functionCall
    : IDENT LPAREN expression (COMMA expression)* RPAREN
    ;

/** `.6` is a real number when no tag precedes the dot. */
literal
    : INTEGER
    | BASED_INTEGER
    | REAL
    | DOT INTEGER
    | STRING_LITERAL
    | UNTERMINATED_STRING
    ;

tagPath
    : tagRoot postfix*
    ;

/** `\Prog` (another program's parameters), or a tag, module tag `Local:1:I` or status `S:FS`. */
tagRoot
    : BACKSLASH IDENT
    | IDENT (COLON (IDENT | INTEGER))*
    ;

postfix
    : DOT memberName                                    # memberOp
    | DOT INTEGER                                       # bitOp
    | DOT LBRACK expression RBRACK                      # indirectBitOp
    | LBRACK expression (COMMA expression)* RBRACK      # indexOp
    ;

memberName
    : IDENT | AND | OR | XOR | NOT | MOD
    ;
