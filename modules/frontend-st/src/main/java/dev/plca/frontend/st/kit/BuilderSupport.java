package dev.plca.frontend.st.kit;

import dev.plca.ast.Comment;
import dev.plca.ast.Expression;
import dev.plca.ast.Expression.LiteralKind;
import dev.plca.ast.Identifier;
import dev.plca.ast.Pragma;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.diag.DiagnosticSink;
import dev.plca.core.source.SourceText;
import dev.plca.core.source.Span;
import dev.plca.frontend.st.SyntaxCodes;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

/**
 * The grammar-independent half of an AST builder: spans, hidden channels, names, literals and operators of the shared
 * ST core. Each dialect's builder extends it and adds the glue for its own generated context classes (ANTLR generates
 * separate classes per root grammar even for imported rules, so that glue cannot be shared — the conformance test keeps
 * the copies equivalent).
 *
 * <p>Error recovery leaves partial trees: missing children are null and rule contexts may carry an exception. Every
 * method here tolerates that.
 */
public abstract class BuilderSupport {

    protected final SourceText text;
    protected final CommonTokenStream tokens;
    protected final DiagnosticSink sink;
    protected final CoreTokens tok;

    protected BuilderSupport(SourceText text, CommonTokenStream tokens, DiagnosticSink sink, CoreTokens tok) {
        this.text = text;
        this.tokens = tokens;
        this.sink = sink;
        this.tok = tok;
    }

    // ============================================================================================
    // Hidden channels
    // ============================================================================================

    /** Comments, pragmas and stray characters from the hidden channels; reports lexical problems. */
    public void collectHidden(List<Comment> comments, List<Pragma> pragmas) {
        Token pendingError = null;
        Token lastError = null;
        for (Token t : tokens.getTokens()) {
            int channel = t.getChannel();
            if (channel == tok.commentsChannel) {
                boolean line = t.getType() == tok.lineComment;
                comments.add(new Comment(line ? Comment.Kind.LINE : Comment.Kind.BLOCK, tokenSpan(t)));
                String s = t.getText();
                if (!line && !(s.endsWith(t.getType() == tok.blockComment ? "*)" : "*/")) || !line && s.length() < 4) {
                    sink.report(Diagnostic.error(
                            SyntaxCodes.UNTERMINATED_COMMENT,
                            "comment is not closed before the end of the text",
                            text.span(
                                    t.getStartIndex(),
                                    Math.min(t.getStartIndex() + 2, text.text().length()))));
                }
            } else if (channel == tok.pragmasChannel) {
                pragmas.add(Pragma.of(t.getText(), tokenSpan(t)));
            } else if (channel == tok.errorsChannel) {
                if (lastError != null && lastError.getStopIndex() + 1 == t.getStartIndex()) {
                    lastError = t;
                } else {
                    reportStray(pendingError, lastError);
                    pendingError = t;
                    lastError = t;
                }
            }
        }
        reportStray(pendingError, lastError);
    }

    private void reportStray(Token first, Token last) {
        if (first == null) {
            return;
        }
        Span s = text.span(first.getStartIndex(), last.getStopIndex() + 1);
        String chars = text.text().substring(s.start(), s.end());
        sink.report(Diagnostic.error(
                SyntaxCodes.UNEXPECTED_CHARACTER,
                "unexpected character" + (s.length() > 1 ? "s" : "") + " '" + chars + "'",
                s));
    }

    /** Pragmas directly before {@code t} (between it and the previous significant token). */
    protected List<Pragma> pragmasBefore(Token t) {
        if (t == null || t.getTokenIndex() < 0) {
            return List.of();
        }
        List<Token> hidden = tokens.getHiddenTokensToLeft(t.getTokenIndex(), tok.pragmasChannel);
        if (hidden == null) {
            return List.of();
        }
        List<Pragma> out = new ArrayList<>(hidden.size());
        for (Token h : hidden) {
            out.add(Pragma.of(h.getText(), tokenSpan(h)));
        }
        return out;
    }

    // ============================================================================================
    // Names
    // ============================================================================================

    /** The name an identifier context spells, with its span; {@code <missing>} where recovery left nothing. */
    protected Identifier identifierOf(ParserRuleContext ctx) {
        if (ctx == null || ctx.getStart() == null || ctx.getStart().getType() == Token.EOF) {
            Span at = ctx == null ? text.span(0, 0) : span(ctx);
            return new Identifier("<missing>", at);
        }
        return new Identifier(ctx.getText(), span(ctx));
    }

    protected static Identifier missingIdentifier(Span fallback) {
        return new Identifier("<missing>", Span.at(fallback.textId(), fallback.end()));
    }

    // ============================================================================================
    // Literals
    // ============================================================================================

    /** A single-token literal of the core (number, boolean, string, date/time). */
    protected Expression coreLiteral(Token t, Span span) {
        String s = t.getText();
        int type = t.getType();
        if (type == tok.integer || type == tok.basedInteger) {
            return new Expression.Literal(LiteralKind.INTEGER, s, null, span);
        }
        if (type == tok.real) {
            return new Expression.Literal(LiteralKind.REAL, s, null, span);
        }
        if (type == tok.trueKw || type == tok.falseKw) {
            return new Expression.Literal(LiteralKind.BOOL, s, null, span);
        }
        if (type == tok.stringLiteral) {
            return new Expression.Literal(LiteralKind.STRING, s, null, span);
        }
        if (type == tok.wstringLiteral) {
            return new Expression.Literal(LiteralKind.WSTRING, s, null, span);
        }
        if (type == tok.unterminatedString) {
            sink.report(Diagnostic.error(
                    SyntaxCodes.UNTERMINATED_STRING, "string is not closed before the end of the text", span));
            return new Expression.Literal(s.startsWith("\"") ? LiteralKind.WSTRING : LiteralKind.STRING, s, null, span);
        }
        if (type == tok.timeLiteral) {
            return prefixed(LiteralKind.TIME, s, span);
        }
        if (type == tok.dateLiteral) {
            return prefixed(LiteralKind.DATE, s, span);
        }
        if (type == tok.todLiteral) {
            return prefixed(LiteralKind.TIME_OF_DAY, s, span);
        }
        if (type == tok.dtLiteral) {
            return prefixed(LiteralKind.DATE_AND_TIME, s, span);
        }
        return new Expression.Invalid(span);
    }

    /** {@code T#1s} → value {@code 1s}, prefix {@code T}. */
    protected static Expression prefixed(LiteralKind kind, String s, Span span) {
        int hash = s.indexOf('#');
        return new Expression.Literal(kind, s.substring(hash + 1), s.substring(0, hash), span);
    }

    /** {@code INT#5}, {@code WORD#16#FF}, {@code E_Color#Red}: the prefix is the first child, {@code hash} the '#'. */
    protected Expression typedLiteral(ParserRuleContext ctx, TerminalNode hash) {
        Span span = span(ctx);
        String prefix = ctx.getChild(0).getText();
        int at = hash == null ? -1 : hash.getSymbol().getTokenIndex();
        if (at < 0 || ctx.getStop().getTokenIndex() <= at) {
            return new Expression.Invalid(span);
        }
        Token last = ctx.getStop();
        String value = tokens.getText(tokens.get(at + 1), last);
        int type = last.getType();
        LiteralKind kind;
        if (type == tok.integer || type == tok.basedInteger) {
            kind = LiteralKind.INTEGER;
        } else if (type == tok.real) {
            kind = LiteralKind.REAL;
        } else if (type == tok.trueKw || type == tok.falseKw) {
            kind = LiteralKind.BOOL;
        } else if (type == tok.stringLiteral) {
            kind = LiteralKind.STRING;
        } else if (type == tok.wstringLiteral) {
            kind = LiteralKind.WSTRING;
        } else {
            kind = LiteralKind.ENUM;
        }
        return new Expression.Literal(kind, value, prefix, span);
    }

    // ============================================================================================
    // Expressions
    // ============================================================================================

    /** A binary expression; an operator token the core does not know yields {@code Invalid}. */
    protected Expression binary(Token op, Expression left, Expression right, Span span) {
        Expression.BinaryOp b = tok.binaryOp(op);
        return b == null ? new Expression.Invalid(span) : new Expression.Binary(b, left, right, span);
    }

    protected Expression unary(Token op, Expression operand, Span span) {
        Expression.UnaryOp u = tok.unaryOp(op);
        return u == null ? new Expression.Invalid(span) : new Expression.Unary(u, operand, span);
    }

    // ============================================================================================
    // Spans
    // ============================================================================================

    protected Span span(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        int len = text.text().length();
        int s = start == null || start.getType() == Token.EOF || start.getStartIndex() < 0
                ? len
                : Math.min(start.getStartIndex(), len);
        if (stop == null || start == null || stop.getTokenIndex() < start.getTokenIndex() || stop.getStopIndex() < 0) {
            return text.span(s, s);
        }
        int e = Math.min(Math.max(stop.getStopIndex() + 1, s), len);
        return text.span(s, e);
    }

    protected Span span(Token from, TerminalNode to) {
        int s = Math.max(from.getStartIndex(), 0);
        int e = to == null ? s : Math.max(to.getSymbol().getStopIndex() + 1, s);
        return text.span(
                Math.min(s, text.text().length()), Math.min(e, text.text().length()));
    }

    protected Span tokenSpan(Token t) {
        return SyntaxErrorListener.tokenSpan(text, t);
    }

    protected static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.replace("_", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
