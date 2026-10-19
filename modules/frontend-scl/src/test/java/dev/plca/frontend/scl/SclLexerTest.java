package dev.plca.frontend.scl;

import static org.assertj.core.api.Assertions.assertThat;

import dev.plca.frontend.st.kit.Utf16CharStream;
import dev.plca.grammar.SiemensSclLexer;
import java.util.List;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Token-level edge cases of the SCL lexer (docs/grammar/siemens-scl.md §1–§5). */
class SclLexerTest {

    /** Default-channel tokens as {@code TYPE} or {@code TYPE[text]} for names and free-text tokens. */
    private static List<String> tokens(String text) {
        SiemensSclLexer lexer = new SiemensSclLexer(new Utf16CharStream(text, "t.scl"));
        lexer.removeErrorListeners();
        CommonTokenStream ts = new CommonTokenStream(lexer);
        ts.fill();
        return ts.getTokens().stream()
                .filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() != Token.EOF)
                .map(t -> {
                    String name = SiemensSclLexer.VOCABULARY.getSymbolicName(t.getType());
                    boolean showText = name.equals("IDENT")
                            || name.equals("QUOTED_IDENT")
                            || name.endsWith("_LINE")
                            || name.endsWith("LITERAL")
                            || name.endsWith("INTEGER")
                            || name.equals("DIRECT_ADDRESS")
                            || name.equals("SLICE");
                    return showText ? name + "[" + t.getText() + "]" : name;
                })
                .toList();
    }

    private static List<Token> hidden(String text) {
        SiemensSclLexer lexer = new SiemensSclLexer(new Utf16CharStream(text, "t.scl"));
        lexer.removeErrorListeners();
        CommonTokenStream ts = new CommonTokenStream(lexer);
        ts.fill();
        return ts.getTokens().stream()
                .filter(t -> t.getChannel() != Token.DEFAULT_CHANNEL)
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                // a region header takes the rest of the line, also with ';' and quotes in it
                "REGION Motor control (main; aux)|REGION_LINE[REGION Motor control (main; aux)]",
                "  REGION|REGION_LINE[REGION]",
                "REGION (Init)|REGION_LINE[REGION (Init)]",
                "REGION [1] Messages 1 .. 16|REGION_LINE[REGION [1] Messages 1 .. 16]",
                "END_REGION Validation of inputs|END_REGION",
                // ... but a variable called region is a name
                "region := 1;|IDENT[region] ASSIGN INTEGER[1] SEMI",
                "region : Int;|IDENT[region] COLON IDENT[Int] SEMI",
                "region, x : Int;|IDENT[region] COMMA IDENT[x] COLON IDENT[Int] SEMI",
                "region.%X0 := on;|IDENT[region] SLICE[.%X0] ASSIGN ON SEMI",
                "region(x);|IDENT[region] LPAREN IDENT[x] RPAREN SEMI",
                "region[1] := 2;|IDENT[region] LBRACK INTEGER[1] RBRACK ASSIGN INTEGER[2] SEMI",
                "x := region;|IDENT[x] ASSIGN IDENT[region] SEMI",
                "regional := 1;|IDENT[regional] ASSIGN INTEGER[1] SEMI",
                "END_REGION;|END_REGION SEMI",
                // TITLE only as the first word of a line
                "TITLE = Main Program Sweep (Cycle)|TITLE_LINE[TITLE = Main Program Sweep (Cycle)]",
                "TITLE := 'x';|IDENT[TITLE] ASSIGN STRING_LITERAL['x'] SEMI",
                "IF a = title THEN|IF IDENT[a] EQ IDENT[title] THEN",
                // quoted names are names; # is its own token
                "\"Motor DB\".speed|QUOTED_IDENT[\"Motor DB\"] DOT IDENT[speed]",
                "#\"local x\" := #y;|HASH QUOTED_IDENT[\"local x\"] ASSIGN HASH IDENT[y] SEMI",
                "W#16#FF|IDENT[W] HASH BASED_INTEGER[16#FF]",
                "BOOL#TRUE|IDENT[BOOL] HASH TRUE",
                // operands and literals
                "#w.%B1 := %DB1.DBX0.0;|HASH IDENT[w] SLICE[.%B1] ASSIGN DIRECT_ADDRESS[%DB1.DBX0.0] SEMI",
                "%IW64:P|DIRECT_ADDRESS[%IW64:P]",
                "S5T#2H_46M_30S|S5TIME_LITERAL[S5T#2H_46M_30S]",
                "DTL#2024-01-31-12:00:00.123456789|DT_LITERAL[DTL#2024-01-31-12:00:00.123456789]",
                // header words are keyword tokens that the parser also accepts as names
                "version := name;|VERSION ASSIGN NAME SEMI",
            })
    void lexes(String text, String expected) {
        assertThat(String.join(" ", tokens(text))).isEqualTo(expected);
    }

    @Test
    void regionOnlyAtLineStart() {
        assertThat(tokens("x := 1; REGION a"))
                .containsExactly("IDENT[x]", "ASSIGN", "INTEGER[1]", "SEMI", "IDENT[REGION]", "IDENT[a]");
        assertThat(tokens("x := 1;\n\tREGION a\n")).endsWith("REGION_LINE[REGION a]");
    }

    @Test
    void blockCommentsNest() {
        // commenting out code that contains comments (corpus: TIA export of the Siemens LKinCtrl library)
        assertThat(tokens("(* x := (*inner*) 6; *) y := 1;"))
                .containsExactly("IDENT[y]", "ASSIGN", "INTEGER[1]", "SEMI");
        assertThat(tokens("/* a /* b */ c */ y := 2;")).containsExactly("IDENT[y]", "ASSIGN", "INTEGER[2]", "SEMI");
    }

    @Test
    void commentSection() {
        assertThat(tokens("(/*\n  doc\n*/)\nx := 1;")).containsExactly("IDENT[x]", "ASSIGN", "INTEGER[1]", "SEMI");
        assertThat(hidden("(/* doc */)"))
                .singleElement()
                .satisfies(h -> assertThat(h.getType()).isEqualTo(SiemensSclLexer.SECTION_COMMENT));
    }

    @Test
    void doubleQuotesNeverStartAString() {
        // an unclosed quoted name does not run to the end of the text like an unterminated WSTRING would
        List<String> t = tokens("\"open\nx := 1;");
        assertThat(t).containsSubsequence("IDENT[open]", "IDENT[x]", "ASSIGN", "INTEGER[1]", "SEMI");
        assertThat(hidden("\"open\nx := 1;"))
                .anyMatch(h ->
                        h.getChannel() == SiemensSclLexer.ERRORS && h.getText().equals("\""));
    }

    @Test
    void attributesGoToPragmaChannel() {
        assertThat(tokens("Speed { ExternalWritable := 'False'} : Real;"))
                .containsExactly("IDENT[Speed]", "COLON", "IDENT[Real]", "SEMI");
        assertThat(hidden("Speed { ExternalWritable := 'False'} : Real;"))
                .singleElement()
                .satisfies(h -> assertThat(h.getChannel()).isEqualTo(SiemensSclLexer.PRAGMAS));
    }
}
