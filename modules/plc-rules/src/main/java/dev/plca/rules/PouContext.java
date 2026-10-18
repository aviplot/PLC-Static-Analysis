package dev.plca.rules;

import dev.plca.ast.Expression;
import dev.plca.ast.Statement;
import dev.plca.core.diag.Diagnostic;
import dev.plca.core.source.Span;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.SemanticModel;
import dev.plca.semantic.Statements;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Context of a {@link PouRule}: one POU (or method, action, property accessor) with a parsed body. */
public final class PouContext extends RuleContext {

    private final PouSymbol pou;

    PouContext(SemanticModel model, RuleDescriptor descriptor, Map<String, String> parameters, PouSymbol pou) {
        super(model, descriptor, parameters);
        this.pou = pou;
    }

    public PouSymbol pou() {
        return pou;
    }

    public List<Statement> body() {
        return pou.declaration().body();
    }

    /** Every statement of the body, pre-order. */
    public void forEachStatement(Consumer<Statement> action) {
        Statements.forEachDeep(body(), action);
    }

    /** Every expression of the body, including subexpressions and call arguments, pre-order. */
    public void forEachExpression(Consumer<Expression> action) {
        Walk.expressions(body(), action);
    }

    public void report(Span span, String message, Diagnostic.Related... related) {
        report(pou.qualifiedName(), span, message, List.of(related));
    }
}
