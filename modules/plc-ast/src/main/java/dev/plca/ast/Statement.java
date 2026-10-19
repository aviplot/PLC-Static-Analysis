package dev.plca.ast;

import dev.plca.core.source.Span;
import java.util.List;

/** ST statements (IEC 7.3.3 plus CODESYS extensions). */
public sealed interface Statement extends Node {

    enum AssignOp {
        /** {@code :=} */
        ASSIGN,
        /** {@code ?=} assignment attempt */
        ATTEMPT,
        /** CODESYS {@code S=} */
        SET,
        /** CODESYS {@code R=} */
        RESET,
        /** CODESYS {@code REF=} */
        REF,
        /** Rockwell {@code [:=]}: an assignment whose target is also cleared at prescan and power-up. */
        NON_RETENTIVE,
        /** Siemens {@code +=} (and the other compound operators): {@code x := x + value}. */
        ADD,
        SUB,
        MUL,
        DIV;

        /** {@code +=}, {@code -=}, {@code *=}, {@code /=}: the target is read as well as written. */
        public boolean isCompound() {
            return this == ADD || this == SUB || this == MUL || this == DIV;
        }
    }

    /** {@code a := b := 0;} becomes two assignments sharing the value and span. */
    record Assignment(Expression target, AssignOp op, Expression value, Span span) implements Statement {}

    /** {@code fb(IN := x);}, {@code obj.M();} — {@code call} is normally an {@link Expression.Call}. */
    record CallStatement(Expression call, Span span) implements Statement {}

    record If(List<Branch> branches, List<Statement> elseBody, Span span) implements Statement {
        public If {
            branches = List.copyOf(branches);
            elseBody = elseBody == null ? null : List.copyOf(elseBody);
        }
    }

    /** {@code IF}/{@code ELSIF} condition and body. */
    record Branch(Expression condition, List<Statement> body, Span span) implements Node {
        public Branch {
            body = List.copyOf(body);
        }
    }

    record Case(Expression selector, List<CaseBranch> branches, List<Statement> elseBody, Span span)
            implements Statement {
        public Case {
            branches = List.copyOf(branches);
            elseBody = elseBody == null ? null : List.copyOf(elseBody);
        }
    }

    record CaseBranch(List<CaseLabel> labels, List<Statement> body, Span span) implements Node {
        public CaseBranch {
            labels = List.copyOf(labels);
            body = List.copyOf(body);
        }
    }

    /** A single value ({@code high} null) or a range {@code low..high}. */
    record CaseLabel(Expression low, Expression high, Span span) implements Node {}

    /** {@code variable} is normally a name; CODESYS also accepts {@code FOR a[0] := …} and {@code FOR gvl.i := …}. */
    record For(Expression variable, Expression from, Expression to, Expression by, List<Statement> body, Span span)
            implements Statement {
        public For {
            body = List.copyOf(body);
        }
    }

    record While(Expression condition, List<Statement> body, Span span) implements Statement {
        public While {
            body = List.copyOf(body);
        }
    }

    record Repeat(List<Statement> body, Expression condition, Span span) implements Statement {
        public Repeat {
            body = List.copyOf(body);
        }
    }

    record Exit(Span span) implements Statement {}

    record Continue(Span span) implements Statement {}

    /** {@code value} is non-null only for the non-IEC {@code RETURN expr;} some TwinCAT code uses. */
    record Return(Expression value, Span span) implements Statement {}

    /** CODESYS {@code JMP label;} */
    record Jump(Identifier label, Span span) implements Statement {}

    /** CODESYS jump target {@code label:} */
    record Label(Identifier name, Span span) implements Statement {}

    /** CODESYS {@code __TRY … __CATCH(e) … __FINALLY … __ENDTRY}; absent parts are null. */
    record Try(
            List<Statement> body,
            Identifier exception,
            List<Statement> catchBody,
            List<Statement> finallyBody,
            Span span)
            implements Statement {
        public Try {
            body = List.copyOf(body);
            catchBody = catchBody == null ? null : List.copyOf(catchBody);
            finallyBody = finallyBody == null ? null : List.copyOf(finallyBody);
        }
    }

    /** SCL {@code REGION name … END_REGION}: a named group of statements (outline only, no semantics). */
    record Region(String name, List<Statement> body, Span span) implements Statement {
        public Region {
            body = List.copyOf(body);
        }
    }

    /**
     * A ladder rung: its drawing ({@code ladder}, null when the rung text did not parse) and its lowering to statements
     * ({@code body}, design rule 4), which is what the semantic model and rules see. {@code number} is the rung number
     * the engineer sees; {@code type} the L5X rung type ({@code N}, or a pending-edit marker such as
     * {@code I}/{@code R}).
     */
    record Rung(int number, String type, Ladder ladder, List<Statement> body, Span span) implements Statement {
        public Rung {
            body = List.copyOf(body);
        }
    }

    /** A lone {@code ;} (also the {@code ;} after {@code END_IF}). */
    record Empty(Span span) implements Statement {}

    /** A region that did not parse; a diagnostic has been reported. */
    record Invalid(Span span) implements Statement {}
}
