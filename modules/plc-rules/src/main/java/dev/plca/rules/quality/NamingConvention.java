package dev.plca.rules.quality;

import dev.plca.ast.Declaration.PouKind;
import dev.plca.ast.VarBlock;
import dev.plca.core.diag.Diagnostic;
import dev.plca.rules.ProjectContext;
import dev.plca.rules.ProjectRule;
import dev.plca.semantic.ElementaryKind;
import dev.plca.semantic.GvlSymbol;
import dev.plca.semantic.PouSymbol;
import dev.plca.semantic.Type;
import dev.plca.semantic.TypeSymbol;
import dev.plca.semantic.VariableSymbol;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PLCA-Q015 (PLCopen N2, N10): variable names that do not start with the prefix for their type, and user type, function
 * block and function names without their kind's prefix. Every prefix is a regex parameter; a scope prefix
 * ({@code scopePrefix}, e.g. {@code g_} for globals) may come first. A prefix must be followed by an upper-case letter,
 * a digit or {@code _} ({@code bStart}, {@code b_Start}). Opt-in: the defaults follow the widespread CODESYS/Beckhoff
 * style, and teams differ.
 */
public final class NamingConvention implements ProjectRule {

    @Override
    public String id() {
        return "PLCA-Q015";
    }

    @Override
    public void check(ProjectContext ctx) {
        String scope = ctx.stringParam("scopePrefix");
        for (PouSymbol p : ctx.model().pous()) {
            if (p.isBuiltin() || p.declaration() == null) {
                continue;
            }
            String typeParam = switch (p.kind()) {
                case FUNCTION_BLOCK -> "functionBlockPrefix";
                case FUNCTION -> "functionPrefix";
                case INTERFACE -> "interfacePrefix";
                default -> null;
            };
            if (typeParam != null && p.owner() == null) {
                typeName(ctx, p, p.name(), p.declaration().name().span(), typeParam);
            }
            for (VariableSymbol v : p.variables().values()) {
                if (v.declaration() != null && !v.isResult() && p.kind() != PouKind.ROUTINE) {
                    variable(ctx, p, v, scope);
                }
            }
        }
        for (GvlSymbol g : ctx.model().global().gvls()) {
            if (g.isDataBlocks()) {
                continue;
            }
            for (VariableSymbol v : g.variables().values()) {
                if (v.declaration() != null) {
                    variable(ctx, null, v, scope);
                }
            }
        }
        for (TypeSymbol t : ctx.model().global().types()) {
            if (!(t.declaration() instanceof dev.plca.ast.Declaration.TypeDecl d)) {
                continue;
            }
            String param = switch (t.kind()) {
                case STRUCT -> "structPrefix";
                case ENUM -> "enumPrefix";
                case UNION -> "unionPrefix";
                default -> "aliasPrefix";
            };
            typeName(ctx, null, t.name(), d.name().span(), param);
        }
    }

    private static void typeName(
            ProjectContext ctx, PouSymbol p, String name, dev.plca.core.source.Span span, String param) {
        Pattern prefix = ctx.patternParam(param);
        if (!Pattern.compile("^(?:" + prefix.pattern() + ")").matcher(name).find()) {
            ctx.report(
                    p,
                    span,
                    "Name '" + name + "' does not start with the prefix " + prefix.pattern() + " (" + param + ")",
                    List.of());
        }
    }

    private static void variable(ProjectContext ctx, PouSymbol p, VariableSymbol v, String scope) {
        if (v.section() == VarBlock.Section.EXTERNAL) {
            return; // named by its global declaration
        }
        String param = param(v.type());
        if (param == null) {
            return;
        }
        Pattern prefix = ctx.patternParam(param);
        Pattern full = Pattern.compile("^(?:" + scope + ")(?:" + prefix.pattern() + ")(?=[A-Z0-9_])");
        if (!full.matcher(v.name()).find()) {
            ctx.report(
                    p,
                    v.declaration().name().span(),
                    v.type().display() + " variable '" + v.name() + "' does not start with the prefix "
                            + prefix.pattern() + " (" + param + ")",
                    List.<Diagnostic.Related>of());
        }
    }

    /** The parameter naming the prefix for a variable of this type; null when no convention applies. */
    private static String param(Type t) {
        Type r = t.resolved();
        return switch (r) {
            case Type.Array a -> "arrayPrefix";
            case Type.Pointer ptr ->
                ptr.kind() == dev.plca.ast.TypeRef.PointerKind.REFERENCE ? "referencePrefix" : "pointerPrefix";
            case Type.Struct s -> "structVariablePrefix";
            case Type.Enum e -> "enumVariablePrefix";
            case Type.Instance i -> i.pou().kind() == PouKind.INTERFACE ? "interfaceVariablePrefix" : "instancePrefix";
            case Type.StringType s -> "stringPrefix";
            default -> {
                ElementaryKind k = Type.elementary(r);
                if (k == null) {
                    yield null;
                }
                yield switch (k.category()) {
                    case BOOL -> "boolPrefix";
                    case SIGNED, UNSIGNED, BIT_STRING -> "integerPrefix";
                    case REAL -> "realPrefix";
                    case DURATION, DATE -> "timePrefix";
                    case CHAR -> "stringPrefix";
                };
            }
        };
    }
}
