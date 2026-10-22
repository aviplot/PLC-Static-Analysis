package dev.plca.frontend.plcopen;

import dev.plca.ast.Network;
import dev.plca.ast.Statement;
import dev.plca.ast.VarBlock;
import dev.plca.core.source.SourceText;
import dev.plca.frontend.st.kit.NetworkLowering;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The {@code body} of a POU, action, transition or method, by language. */
final class Bodies {

    record Translated(List<Statement> statements, String language, List<VarBlock> extraBlocks) {}

    private static final Set<String> LANGUAGES = Set.of("ST", "IL", "LD", "FBD", "SFC");

    private Bodies() {}

    static Translated translate(PlcOpenFrontend.Run run, XNode owner, String ownerName, List<VarBlock> blocks) {
        XNode body = owner.child("body");
        if (body == null) {
            return new Translated(null, null, List.of());
        }
        XNode lang = null;
        for (XNode c : body.children) {
            if (LANGUAGES.contains(c.tag)) {
                lang = c;
                break;
            }
        }
        if (lang == null) {
            return new Translated(null, null, List.of());
        }
        return switch (lang.tag) {
            case "ST" -> new Translated(st(run, lang), "ST", List.of());
            case "LD", "FBD" -> new Translated(networks(run, lang), lang.tag, List.of());
            case "SFC" -> SfcTranslator.translate(run, lang, ownerName);
            default -> {
                run.info(
                        PlcOpenCodes.UNPARSED_BODY,
                        "'" + ownerName + "' is written in " + lang.tag + ", which is not analysed",
                        run.elementSpan(lang));
                yield new Translated(null, lang.tag, List.of());
            }
        };
    }

    /** An ST body: the text of its {@code xhtml}/{@code p} element. */
    static List<Statement> st(PlcOpenFrontend.Run run, XNode lang) {
        XNode text = run.textElement(lang);
        if (text.contentStart < 0) {
            return List.of();
        }
        SourceText t = run.registry.registerXmlContent(run.file, text.contentStart, text.contentEnd);
        return run.st().statements(t, run.sink, run.comments, run.pragmas);
    }

    /** The networks of an LD/FBD body, each with its lowering. */
    static List<Statement> networks(PlcOpenFrontend.Run run, XNode lang) {
        Network.Language language = lang.tag.equals("LD") ? Network.Language.LD : Network.Language.FBD;
        List<Network> networks = NetworkBuilder.build(run, lang, language);
        List<Statement> out = new ArrayList<>(networks.size());
        int number = 0;
        for (Network n : networks) {
            List<Statement> lowered = new NetworkLowering(run.sink, run::nextTemp, n).lower();
            out.add(new Statement.Network(++number, n, lowered, n.span()));
        }
        return out;
    }
}
