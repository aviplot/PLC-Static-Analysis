package dev.plca.frontend.l5x;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Identifier;
import dev.plca.ast.Ladder;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.kit.BuilderSupport;
import dev.plca.grammar.RungParser.*;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * Builds {@link Ladder} drawings (with {@code plc-ast} expressions as operands) from a rung parse tree. Lowering to
 * statements is {@link RungLowering}'s job.
 *
 * <p>Error recovery leaves partial trees: missing children are null. Every method tolerates that.
 */
final class RungAstBuilder extends BuilderSupport {

    RungAstBuilder(SourceText text, CommonTokenStream tokens, DiagnosticSink sink) {
        super(text, tokens, sink, RungParserDriver.TOKENS);
    }

    /** The drawing of one parsed rung. */
    Ladder rung(RungContext ctx) {
        return new Ladder(elements(ctx.element()), span(ctx));
    }

    /** Start offset of a rung in the routine text: where its first token begins. */
    static int start(RungContext ctx) {
        return ctx.getStart() == null ? -1 : ctx.getStart().getStartIndex();
    }

    private List<Ladder.Element> elements(List<ElementContext> list) {
        List<Ladder.Element> out = new ArrayList<>(list.size());
        for (ElementContext e : list) {
            if (e.instruction() != null) {
                out.add(instruction(e.instruction()));
            } else if (e.branch() != null) {
                out.add(branch(e.branch()));
            }
        }
        return out;
    }

    private Ladder.Instruction instruction(InstructionContext ctx) {
        Identifier mnemonic = ctx.mnemonic() == null
                ? missingIdentifier(span(ctx))
                : new Identifier(ctx.mnemonic().getText(), span(ctx.mnemonic()));
        List<Ladder.Operand> operands = new ArrayList<>();
        for (OperandContext o : ctx.operand()) {
            Span s = span(o);
            if (o.expression() != null) {
                operands.add(new Ladder.Operand(Ladder.OperandKind.VALUE, expression(o.expression(), s), s));
            } else if (o.settingText() != null) {
                // DCS(…,EMERGENCY STOP,…): a setting word list, not a tag
                Expression setting = new Expression.Literal(
                        Expression.LiteralKind.ENUM,
                        o.settingText().getText().isEmpty() ? "" : text.text().substring(s.start(), s.end()),
                        null,
                        s);
                operands.add(new Ladder.Operand(Ladder.OperandKind.VALUE, setting, s));
            } else if (o.QUESTION() != null) {
                operands.add(new Ladder.Operand(Ladder.OperandKind.PLACEHOLDER, null, s));
            } else {
                operands.add(new Ladder.Operand(Ladder.OperandKind.EMPTY, null, s));
            }
        }
        // NOP(), AFI(), TND(): "()" holds no operand rather than one empty operand
        if (operands.size() == 1 && operands.getFirst().kind() == Ladder.OperandKind.EMPTY) {
            operands.clear();
        }
        return new Ladder.Instruction(mnemonic, operands, span(ctx));
    }

    private Ladder.Branch branch(BranchContext ctx) {
        List<Ladder.Leg> legs = new ArrayList<>();
        for (LegContext l : ctx.leg()) {
            legs.add(new Ladder.Leg(elements(l.element()), span(l)));
        }
        return new Ladder.Branch(legs, span(ctx));
    }

    // ---- expressions ----------------------------------------------------------------------------

    private Expression expression(ExpressionContext ctx, Span fallback) {
        if (ctx == null) {
            return new Expression.Invalid(fallback);
        }
        Span span = span(ctx);
        return switch (ctx) {
            case PrimaryExprContext p -> primary(p.primary(), span);
            case PowerExprContext p -> binary(p.POWER().getSymbol(), p.expression(), span);
            case UnaryExprContext u -> unary(u.op, expression(u.expression(), span), span);
            case MulExprContext m -> binary(m.op, m.expression(), span);
            case AddExprContext a -> binary(a.op, a.expression(), span);
            case RelExprContext r -> binary(r.op, r.expression(), span);
            case EqExprContext e -> binary(e.op, e.expression(), span);
            case AndExprContext a -> binary(a.op, a.expression(), span);
            case XorExprContext x -> binary(x.op, x.expression(), span);
            case OrExprContext o -> binary(o.op, o.expression(), span);
            default -> new Expression.Invalid(span);
        };
    }

    private Expression binary(Token op, List<ExpressionContext> operands, Span span) {
        return binary(
                op,
                expression(operands.isEmpty() ? null : operands.get(0), span),
                expression(operands.size() < 2 ? null : operands.get(1), span),
                span);
    }

    private Expression primary(PrimaryContext ctx, Span span) {
        if (ctx == null) {
            return new Expression.Invalid(span);
        }
        if (ctx.literal() != null) {
            return literal(ctx.literal());
        }
        if (ctx.functionCall() != null) {
            FunctionCallContext f = ctx.functionCall();
            List<Argument> args = new ArrayList<>();
            for (ExpressionContext e : f.expression()) {
                Span s = span(e);
                args.add(new Argument(ArgumentKind.POSITIONAL, null, false, expression(e, s), s));
            }
            Identifier name =
                    new Identifier(f.IDENT().getText(), tokenSpan(f.IDENT().getSymbol()));
            return new Expression.Call(new Expression.NameRef(name, false, name.span()), args, span(f));
        }
        if (ctx.tagPath() != null) {
            return tagPath(ctx.tagPath());
        }
        return expression(ctx.expression(), span);
    }

    private Expression literal(LiteralContext ctx) {
        Span span = span(ctx);
        if (ctx.DOT() != null) {
            return new Expression.Literal(Expression.LiteralKind.REAL, ctx.getText(), null, span);
        }
        return coreLiteral(ctx.getStart(), span);
    }

    private Expression tagPath(TagPathContext ctx) {
        TagRootContext root = ctx.tagRoot();
        if (root == null) {
            return new Expression.Invalid(span(ctx));
        }
        Span rootSpan = span(root);
        Expression e;
        if (root.BACKSLASH() != null) {
            TerminalNode id = root.IDENT().isEmpty() ? null : root.IDENT(0);
            e = id == null
                    ? new Expression.Invalid(rootSpan)
                    : new Expression.NameRef(
                            new Identifier(id.getText(), Identifier.Form.PROGRAM, rootSpan), false, rootSpan);
        } else {
            // a tag, or a module/status tag whose name keeps its colons: Local:1:I, S:FS
            e = new Expression.NameRef(new Identifier(root.getText(), rootSpan), false, rootSpan);
        }
        int start = rootSpan.start();
        for (PostfixContext op : ctx.postfix()) {
            Span s = text.span(start, Math.max(start, span(op).end()));
            e = switch (op) {
                case MemberOpContext m ->
                    new Expression.MemberAccess(
                            e,
                            m.memberName() == null
                                    ? missingIdentifier(s)
                                    : new Identifier(m.memberName().getText(), span(m.memberName())),
                            s);
                case BitOpContext b ->
                    new Expression.BitAccess(e, parseIntOr(b.INTEGER().getText(), -1), s);
                case IndirectBitOpContext b -> new Expression.IndirectBit(e, expression(b.expression(), s), s);
                case IndexOpContext i -> {
                    List<Expression> indices = new ArrayList<>();
                    for (ExpressionContext x : i.expression()) {
                        indices.add(expression(x, s));
                    }
                    yield new Expression.Index(e, indices, s);
                }
                default -> new Expression.Invalid(s);
            };
        }
        return e;
    }
}
