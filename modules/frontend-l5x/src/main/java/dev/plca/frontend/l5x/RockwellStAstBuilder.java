package dev.plca.frontend.l5x;

import dev.plca.ast.Expression;
import dev.plca.ast.Expression.Argument;
import dev.plca.ast.Expression.ArgumentKind;
import dev.plca.ast.Identifier;
import dev.plca.ast.Statement;
import dev.plca.ast.Statement.AssignOp;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.SyntaxCodes;
import dev.plca.frontend.st.kit.BuilderSupport;
import dev.plca.frontend.st.kit.CoreTokens;
import dev.plca.grammar.RockwellStLexer;
import dev.plca.grammar.RockwellStParser.*;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Builds {@code plc-ast} statements from a Rockwell ST routine's parse tree (design rule 3). The grammar-independent
 * parts live in {@link BuilderSupport}; this class is the glue for the Rockwell contexts. Statement and expression glue
 * mirrors the CODESYS builder rule for rule — {@code RockwellStConformanceTest} checks that both give the same AST for
 * core ST.
 *
 * <p>Error recovery leaves partial trees: missing children are null. Every method tolerates that.
 */
final class RockwellStAstBuilder extends BuilderSupport {

    static final CoreTokens TOKENS = new CoreTokens(RockwellStLexer.VOCABULARY, RockwellStLexer.channelNames);

    RockwellStAstBuilder(SourceText text, CommonTokenStream tokens, DiagnosticSink sink) {
        super(text, tokens, sink, TOKENS);
    }

    List<Statement> statementsOnly(StatementsOnlyContext ctx) {
        return statementList(ctx.statementList());
    }

    /** An SFC transition's condition. */
    Expression conditionOnly(ConditionOnlyContext ctx) {
        return expression(ctx.expression(), span(ctx));
    }

    // ============================================================================================
    // Statements
    // ============================================================================================

    private List<Statement> statementList(StatementListContext ctx) {
        List<Statement> out = new ArrayList<>();
        if (ctx == null || ctx.children == null) {
            return out;
        }
        for (ParseTree c : ctx.children) {
            if (c instanceof StatementContext s) {
                statement(s, out);
            }
        }
        return out;
    }

    private List<Statement> caseBody(CaseBodyContext ctx) {
        List<Statement> out = new ArrayList<>();
        if (ctx != null) {
            for (StatementContext s : ctx.statement()) {
                statement(s, out);
            }
        }
        return out;
    }

    private void statement(StatementContext ctx, List<Statement> out) {
        if (ctx.getChildCount() > 0 && ctx.getChild(0) instanceof DesignatorStatementContext d) {
            designatorStatement(d, out);
        } else {
            out.add(statement(ctx));
        }
    }

    private Statement statement(StatementContext ctx) {
        Span span = span(ctx);
        if (ctx.getChildCount() == 0) {
            return new Statement.Invalid(span);
        }
        ParseTree c = ctx.getChild(0);
        return switch (c) {
            case IfStatementContext i -> ifStatement(i);
            case CaseStatementContext k -> caseStatement(k);
            case ForStatementContext f ->
                new Statement.For(
                        designator(f.designator(), span),
                        expression(exprAt(f.expression(), 0), span),
                        expression(exprAt(f.expression(), 1), span),
                        f.BY() == null ? null : expression(exprAt(f.expression(), 2), span),
                        statementList(f.statementList()),
                        span);
            case WhileStatementContext w ->
                new Statement.While(condition(w.condition(), span), statementList(w.statementList()), span);
            case RepeatStatementContext r ->
                new Statement.Repeat(statementList(r.statementList()), condition(r.condition(), span), span);
            case ExitStatementContext e -> new Statement.Exit(span);
            case ContinueStatementContext e -> new Statement.Continue(span);
            case ReturnStatementContext r ->
                new Statement.Return(r.expression() == null ? null : expression(r.expression(), span), span);
            case EmptyStatementContext e -> new Statement.Empty(span);
            default -> new Statement.Invalid(span);
        };
    }

    /** Adds one statement, or several for a chained assignment {@code a := b := 0;}. */
    private void designatorStatement(DesignatorStatementContext ctx, List<Statement> out) {
        Span span = span(ctx);
        Expression target = designator(ctx.designator(), span);
        AssignmentTailContext tail = ctx.assignmentTail();
        if (tail == null) {
            if (!(target instanceof Expression.Call) && !(target instanceof Expression.Invalid)) {
                sink.report(Diagnostic.warning(
                        SyntaxCodes.NOT_A_STATEMENT,
                        "statement has no effect: '" + ctx.designator().getText() + "' is neither assigned nor called",
                        target.span()));
            }
            out.add(new Statement.CallStatement(target, span));
            return;
        }
        Expression value = expression(tail.expression(), span(tail));
        if (tail.NONRETENTIVE_ASSIGN() != null) {
            out.add(new Statement.Assignment(target, AssignOp.NON_RETENTIVE, value, span));
            return;
        }
        AssignOp op = tail.ATTEMPT_ASSIGN() != null ? AssignOp.ATTEMPT : AssignOp.ASSIGN;
        List<DesignatorContext> chained = tail.designator();
        // evaluation order of `a := b := v`: b := v, then a := v
        for (int i = chained.size() - 1; i >= 0; i--) {
            out.add(new Statement.Assignment(designator(chained.get(i), span), op, value, span));
        }
        out.add(new Statement.Assignment(target, op, value, span));
    }

    /** IF / ELSIF / WHILE / UNTIL condition (the core grammar's `condition` extension point). */
    private Expression condition(ConditionContext ctx, Span fallback) {
        return expression(ctx == null ? null : ctx.expression(), fallback);
    }

    private Statement ifStatement(IfStatementContext ctx) {
        Span span = span(ctx);
        List<Statement.Branch> branches = new ArrayList<>();
        List<StatementListContext> lists = ctx.statementList();
        branches.add(new Statement.Branch(
                condition(ctx.condition(), span), statementList(listAt(lists, 0)), span(ctx.getStart(), ctx.THEN())));
        for (ElsifClauseContext e : ctx.elsifClause()) {
            branches.add(
                    new Statement.Branch(condition(e.condition(), span(e)), statementList(e.statementList()), span(e)));
        }
        List<Statement> elseBody = ctx.ELSE() == null ? null : statementList(listAt(lists, 1));
        return new Statement.If(branches, elseBody, span);
    }

    private Statement caseStatement(CaseStatementContext ctx) {
        Span span = span(ctx);
        List<Statement.CaseBranch> branches = new ArrayList<>();
        for (CaseElementContext e : ctx.caseElement()) {
            List<Statement.CaseLabel> labels = new ArrayList<>();
            for (CaseLabelContext l : e.caseLabel()) {
                labels.add(new Statement.CaseLabel(
                        expression(exprAt(l.expression(), 0), span(l)),
                        l.DOTDOT() == null ? null : expression(exprAt(l.expression(), 1), span(l)),
                        span(l)));
            }
            branches.add(new Statement.CaseBranch(labels, caseBody(e.caseBody()), span(e)));
        }
        List<Statement> elseBody = ctx.ELSE() == null ? null : statementList(ctx.statementList());
        return new Statement.Case(expression(ctx.expression(), span), branches, elseBody, span);
    }

    // ============================================================================================
    // Expressions
    // ============================================================================================

    private Expression expression(ExpressionContext ctx, Span fallback) {
        if (ctx == null) {
            return new Expression.Invalid(fallback);
        }
        Span span = span(ctx);
        return switch (ctx) {
            case PrimaryExprContext p -> primary(p.primaryExpression(), span);
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
        return binary(op, expression(exprAt(operands, 0), span), expression(exprAt(operands, 1), span), span);
    }

    private Expression primary(PrimaryExpressionContext ctx, Span span) {
        if (ctx == null) {
            return new Expression.Invalid(span);
        }
        if (ctx.literal() != null) {
            return literal(ctx.literal());
        }
        if (ctx.designator() != null) {
            return designator(ctx.designator(), span);
        }
        return expression(ctx.expression(), span);
    }

    private Expression designator(DesignatorContext ctx, Span fallback) {
        if (ctx == null || ctx.designatorRoot() == null) {
            return new Expression.Invalid(ctx == null ? fallback : span(ctx));
        }
        DesignatorRootContext root = ctx.designatorRoot();
        Span rootSpan = span(root);
        Expression e;
        if (root.DIRECT_ADDRESS() != null) {
            e = new Expression.DirectAddress(root.getText(), rootSpan);
        } else if (root.operatorCall() != null) {
            OperatorCallContext oc = root.operatorCall();
            Token op = oc.getStart();
            Expression callee =
                    new Expression.NameRef(new Identifier(op.getText(), tokenSpan(op)), false, tokenSpan(op));
            e = new Expression.Call(callee, arguments(oc.argumentList()), rootSpan);
        } else if (root.moduleTag() != null) {
            // Local:1:I, S:FS: the name keeps its colons, as in rung text
            e = new Expression.NameRef(new Identifier(root.moduleTag().getText(), rootSpan), false, rootSpan);
        } else if (root.BACKSLASH() != null) {
            Identifier program = root.identifier() == null
                    ? missingIdentifier(rootSpan)
                    : new Identifier(root.identifier().getText(), Identifier.Form.PROGRAM, rootSpan);
            e = new Expression.NameRef(program, false, rootSpan);
        } else if (root.identifier() != null) {
            e = new Expression.NameRef(identifierOf(root.identifier()), false, rootSpan);
        } else {
            return new Expression.Invalid(rootSpan);
        }
        int start = rootSpan.start();
        for (PostfixOpContext op : ctx.postfixOp()) {
            Span s = text.span(start, Math.max(start, span(op).end()));
            e = switch (op) {
                case MemberOpContext m ->
                    new Expression.MemberAccess(
                            e, m.identifier() == null ? missingIdentifier(s) : identifierOf(m.identifier()), s);
                case BitOpContext b ->
                    new Expression.BitAccess(e, parseIntOr(b.INTEGER().getText(), -1), s);
                case IndirectBitOpContext b -> new Expression.IndirectBit(e, expression(b.expression(), s), s);
                case DerefOpContext d -> new Expression.Deref(e, s);
                case IndexOpContext i -> {
                    List<Expression> indices = new ArrayList<>();
                    for (ExpressionContext x : i.expression()) {
                        indices.add(expression(x, s));
                    }
                    yield new Expression.Index(e, indices, s);
                }
                case CallOpContext c -> new Expression.Call(e, arguments(c.argumentList()), s);
                default -> new Expression.Invalid(s);
            };
        }
        return e;
    }

    private List<Argument> arguments(ArgumentListContext ctx) {
        List<Argument> out = new ArrayList<>();
        if (ctx == null) {
            return out;
        }
        // an argument left out between commas is a positional argument without value
        boolean expecting = true;
        for (ParseTree child : ctx.children) {
            if (!(child instanceof ArgumentContext a)) {
                if (expecting) {
                    Token comma = ((org.antlr.v4.runtime.tree.TerminalNode) child).getSymbol();
                    out.add(new Argument(
                            ArgumentKind.POSITIONAL, null, false, null, Span.at(text.id(), comma.getStartIndex())));
                }
                expecting = true;
                continue;
            }
            expecting = false;
            Span span = span(a);
            out.add(
                    switch (a) {
                        case NamedArgumentContext n ->
                            new Argument(
                                    ArgumentKind.INPUT,
                                    identifierOf(n.identifier()),
                                    false,
                                    n.expression() == null ? null : expression(n.expression(), span),
                                    span);
                        case OutputArgumentContext o ->
                            new Argument(
                                    ArgumentKind.OUTPUT,
                                    identifierOf(o.identifier()),
                                    o.NOT() != null,
                                    o.designator() == null ? null : designator(o.designator(), span),
                                    span);
                        case PositionalArgumentContext p ->
                            new Argument(ArgumentKind.POSITIONAL, null, false, expression(p.expression(), span), span);
                        default ->
                            new Argument(ArgumentKind.POSITIONAL, null, false, new Expression.Invalid(span), span);
                    });
        }
        if (expecting && !out.isEmpty()) {
            // `f(a, )`: the last argument is left out
            int end = span(ctx).end();
            out.add(new Argument(ArgumentKind.POSITIONAL, null, false, null, Span.at(text.id(), end)));
        }
        return out;
    }

    private Expression literal(LiteralContext ctx) {
        if (ctx.typedLiteral() != null) {
            return typedLiteral(ctx.typedLiteral(), ctx.typedLiteral().HASH());
        }
        return coreLiteral(ctx.getStart(), span(ctx));
    }

    /** {@code #region text} / {@code #endregion} lines: region pragmas (the ST editor's folding markers). */
    @Override
    protected List<dev.plca.ast.Pragma> pragmasOf(Token t) {
        if (t.getType() == RockwellStLexer.REGION_DIRECTIVE) {
            String body = t.getText().substring(1).strip();
            int blank = body.indexOf(' ');
            String word = blank < 0 ? body : body.substring(0, blank);
            String rest = blank < 0 ? "" : body.substring(blank + 1).strip();
            return List.of(new dev.plca.ast.Pragma(
                    dev.plca.ast.Pragma.Kind.REGION,
                    word.toLowerCase(java.util.Locale.ROOT),
                    rest,
                    t.getText(),
                    tokenSpan(t)));
        }
        return super.pragmasOf(t);
    }

    private static ExpressionContext exprAt(List<ExpressionContext> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }

    private static StatementListContext listAt(List<StatementListContext> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }
}
