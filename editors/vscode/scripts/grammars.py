"""Generates the TextMate grammars in syntaxes/ and the Monaco (Monarch) tokenizers of the web workbench in
../web/src/generated/monarch.json (run: python3 scripts/grammars.py). Keyword lists live here once."""
import json
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent / "syntaxes"


def words(*ws):
    return r"(?i)\b(?:" + "|".join(sorted(set(ws), key=lambda w: (-len(w), w))) + r")\b"


CONTROL = ["IF", "THEN", "ELSIF", "ELSE", "END_IF", "CASE", "OF", "END_CASE", "FOR", "TO", "BY", "DO", "END_FOR",
           "WHILE", "END_WHILE", "REPEAT", "UNTIL", "END_REPEAT", "EXIT", "CONTINUE", "RETURN", "JMP",
           "__TRY", "__CATCH", "__FINALLY", "__ENDTRY"]
DECLARATION = ["PROGRAM", "END_PROGRAM", "FUNCTION_BLOCK", "END_FUNCTION_BLOCK", "FUNCTION", "END_FUNCTION",
               "METHOD", "END_METHOD", "PROPERTY", "END_PROPERTY", "GET", "END_GET", "SET", "END_SET", "INTERFACE",
               "END_INTERFACE", "ACTION", "END_ACTION", "TRANSITION", "END_TRANSITION", "CLASS", "END_CLASS", "TYPE",
               "END_TYPE", "STRUCT", "END_STRUCT", "UNION", "END_UNION", "NAMESPACE", "END_NAMESPACE", "USING",
               "CONFIGURATION", "END_CONFIGURATION", "RESOURCE", "END_RESOURCE", "TASK", "WITH", "ON", "EXTENDS",
               "IMPLEMENTS", "STEP", "END_STEP", "INITIAL_STEP", "FROM"]
VAR = ["VAR", "VAR_INPUT", "VAR_OUTPUT", "VAR_IN_OUT", "VAR_GLOBAL", "VAR_EXTERNAL", "VAR_TEMP", "VAR_STAT",
       "VAR_INST", "VAR_CONFIG", "VAR_ACCESS", "END_VAR"]
MODIFIER = ["CONSTANT", "RETAIN", "NON_RETAIN", "PERSISTENT", "AT", "ABSTRACT", "FINAL", "OVERRIDE", "PUBLIC",
            "PRIVATE", "PROTECTED", "INTERNAL", "READ_ONLY", "READ_WRITE"]
TYPES = ["BOOL", "BIT", "BYTE", "WORD", "DWORD", "LWORD", "SINT", "INT", "DINT", "LINT", "USINT", "UINT", "UDINT",
         "ULINT", "REAL", "LREAL", "TIME", "LTIME", "DATE", "LDATE", "TIME_OF_DAY", "TOD", "LTIME_OF_DAY", "LTOD",
         "DATE_AND_TIME", "DT", "LDATE_AND_TIME", "LDT", "STRING", "WSTRING", "CHAR", "WCHAR", "ARRAY", "POINTER",
         "REFERENCE", "REF_TO", "ANY", "ANY_NUM", "ANY_INT", "ANY_REAL", "ANY_BIT", "ANY_STRING", "ANY_DATE",
         "__XWORD", "__UXINT", "__XINT", "XWORD", "PVOID"]
OPERATORS = ["AND", "OR", "XOR", "NOT", "MOD", "AND_THEN", "OR_ELSE"]
CONSTANTS = ["TRUE", "FALSE", "NULL"]
SELF = ["THIS", "SUPER"]
SCL_BLOCKS = ["ORGANIZATION_BLOCK", "END_ORGANIZATION_BLOCK", "DATA_BLOCK", "END_DATA_BLOCK", "BEGIN", "REGION",
              "END_REGION", "TITLE", "VERSION", "AUTHOR", "FAMILY", "NAME", "KNOW_HOW_PROTECT", "NON_RETAIN",
              "DB_SPECIFIC", "GOTO", "UNLINKED", "S7_Optimized_Access"]

st = {
    "$schema": "https://raw.githubusercontent.com/martinring/tmlanguage/master/tmlanguage.json",
    "name": "Structured Text (IEC 61131-3)",
    "scopeName": "source.iec-st",
    "patterns": [{"include": "#code"}],
    "repository": {
        "code": {"patterns": [
            {"include": "#comments"}, {"include": "#pragmas"}, {"include": "#strings"}, {"include": "#literals"},
            {"include": "#addresses"}, {"include": "#keywords"}, {"include": "#operators"}, {"include": "#calls"},
        ]},
        "comments": {"patterns": [
            {"include": "#block-comment"},
            {"name": "comment.block.c-style.iec-st", "begin": r"/\*", "end": r"\*/"},
            {"name": "comment.line.double-slash.iec-st", "match": r"//.*$"},
        ]},
        # IEC block comments nest in CODESYS: (* a (* b *) c *)
        "block-comment": {"name": "comment.block.iec-st", "begin": r"\(\*", "end": r"\*\)",
                          "patterns": [{"include": "#block-comment"}]},
        "pragmas": {"name": "meta.preprocessor.pragma.iec-st", "begin": r"\{", "end": r"\}",
                    "beginCaptures": {"0": {"name": "punctuation.definition.pragma.begin.iec-st"}},
                    "endCaptures": {"0": {"name": "punctuation.definition.pragma.end.iec-st"}},
                    "patterns": [
                        {"name": "keyword.other.pragma.iec-st",
                         "match": r"(?i)\b(?:attribute|IF|ELSIF|ELSE|END_IF|defined|hasattribute|hastype|hasvalue|warning|error|info|text|region|endregion|library)\b"},
                        {"name": "string.quoted.single.iec-st", "match": r"'[^']*'"},
                    ]},
        "strings": {"patterns": [
            {"name": "string.quoted.single.iec-st", "begin": "'", "end": "'",
             "patterns": [{"name": "constant.character.escape.iec-st", "match": r"\$(?:[0-9A-Fa-f]{2}|[$'LlNnPpRrTt])"}]},
            {"name": "string.quoted.double.iec-st", "begin": '"', "end": '"',
             "patterns": [{"name": "constant.character.escape.iec-st", "match": r"\$(?:[0-9A-Fa-f]{4}|[$\"LlNnPpRrTt])"}]},
        ]},
        "literals": {"patterns": [
            # T#1h2m3s, TIME#5ms, LT#1us
            {"name": "constant.numeric.duration.iec-st",
             "match": r"(?i)\b(?:LTIME|LT|TIME|T)#[+-]?(?:[0-9_.]+(?:d|h|ms|m|s|us|ns))+\b"},
            {"name": "constant.numeric.date.iec-st",
             "match": r"(?i)\b(?:LDATE_AND_TIME|DATE_AND_TIME|LDT|DT)#\d{4}-\d{1,2}-\d{1,2}-\d{1,2}:\d{1,2}(?::\d{1,2}(?:\.\d+)?)?"},
            {"name": "constant.numeric.date.iec-st", "match": r"(?i)\b(?:LDATE|DATE|D)#\d{4}-\d{1,2}-\d{1,2}\b"},
            {"name": "constant.numeric.date.iec-st",
             "match": r"(?i)\b(?:LTIME_OF_DAY|TIME_OF_DAY|LTOD|TOD)#\d{1,2}:\d{1,2}(?::\d{1,2}(?:\.\d+)?)?"},
            # 16#FF, 2#1010_0000, INT#16#7F, BOOL#1
            {"name": "constant.numeric.based.iec-st", "match": r"(?i)\b(?:[A-Z_]+#)?(?:2|8|16)#[0-9A-F_]+\b"},
            {"name": "constant.numeric.decimal.iec-st",
             "match": r"(?i)\b(?:[A-Z_]+#)?\d[\d_]*(?:\.\d[\d_]*)?(?:E[+-]?\d+)?\b"},
            {"name": "constant.language.iec-st", "match": words(*CONSTANTS)},
        ]},
        "addresses": {"name": "variable.other.address.iec-st",
                      "match": r"(?i)%[IQM](?:\*|[XBWDL]?\d+(?:\.\d+)*)"},
        "keywords": {"patterns": [
            {"name": "keyword.control.iec-st", "match": words(*CONTROL)},
            {"name": "storage.type.var.iec-st", "match": words(*VAR)},
            {"name": "keyword.other.declaration.iec-st", "match": words(*DECLARATION)},
            {"name": "storage.modifier.iec-st", "match": words(*MODIFIER)},
            {"name": "support.type.elementary.iec-st", "match": words(*TYPES)},
            {"name": "variable.language.iec-st", "match": words(*SELF)},
        ]},
        "operators": {"patterns": [
            {"name": "keyword.operator.word.iec-st", "match": words(*OPERATORS)},
            {"name": "keyword.operator.assignment.iec-st", "match": r"(?i):=|=>|\?=|\bS=|\bR=|\bREF="},
            {"name": "keyword.operator.comparison.iec-st", "match": r"<>|<=|>=|<|>|="},
            {"name": "keyword.operator.arithmetic.iec-st", "match": r"\*\*|\+|-|\*|/|&"},
            {"name": "keyword.operator.dereference.iec-st", "match": r"\^"},
        ]},
        "calls": {"match": r"\b([A-Za-z_][A-Za-z0-9_]*)\s*(?=\()",
                  "captures": {"1": {"name": "entity.name.function.call.iec-st"}}},
    },
}

scl = {
    "$schema": st["$schema"],
    "name": "Siemens SCL",
    "scopeName": "source.siemens-scl",
    "patterns": [
        {"include": "source.iec-st#comments"},
        # { S7_Optimized_Access := 'TRUE' } block attributes
        {"include": "source.iec-st#pragmas"},
        # "Motor_DB".speed: double quotes are names in SCL, not wide strings
        {"name": "variable.other.global.siemens-scl", "match": r'"[^"\r\n]*"'},
        {"name": "variable.other.local.siemens-scl", "match": r"#(?:[A-Za-z_][A-Za-z0-9_]*|\"[^\"\r\n]*\")"},
        {"name": "variable.other.address.siemens-scl",
         "match": r"(?i)%(?:DB\d+\.DB[XBWD]\d+(?:\.\d+)?|[IQM][XBWD]?\d+(?:\.\d+)?|[IQ]W\d+:P)"},
        {"name": "keyword.other.declaration.siemens-scl", "match": words(*SCL_BLOCKS)},
        {"include": "source.iec-st#strings"},
        {"include": "source.iec-st#literals"},
        {"include": "source.iec-st#addresses"},
        {"include": "source.iec-st#keywords"},
        {"include": "source.iec-st#operators"},
        {"include": "source.iec-st#calls"},
    ],
}

rll = {
    "$schema": st["$schema"],
    "name": "Rockwell ladder rung text",
    "scopeName": "source.rockwell-rll",
    "patterns": [
        {"match": r"\b([A-Za-z_][A-Za-z0-9_]*)\s*(?=\()", "captures": {"1": {"name": "entity.name.function.instruction.rockwell-rll"}}},
        {"name": "punctuation.section.branch.rockwell-rll", "match": r"[\[\],]"},
        {"name": "constant.language.placeholder.rockwell-rll", "match": r"\?"},
        {"name": "constant.numeric.rockwell-rll", "match": r"(?<![A-Za-z_.:])[+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b"},
        {"name": "variable.other.program.rockwell-rll", "match": r"\\[A-Za-z_][A-Za-z0-9_]*"},
        {"name": "variable.other.tag.rockwell-rll", "match": r"[A-Za-z_][A-Za-z0-9_:]*"},
        {"name": "punctuation.terminator.rung.rockwell-rll", "match": ";"},
    ],
}


def tag_block(element, patterns):
    """`<element ...>` … `</element>` with XML tag scopes and `patterns` for the content (not `<element/>`)."""
    return {
        "begin": r"(<)(" + element + r")\b([^>]*)(?<!/)(>)",
        "end": r"(</)(\2)(>)",
        "beginCaptures": {"1": {"name": "punctuation.definition.tag.xml"}, "2": {"name": "entity.name.tag.xml"},
                          "3": {"patterns": [{"include": "text.xml"}]}, "4": {"name": "punctuation.definition.tag.xml"}},
        "endCaptures": {"1": {"name": "punctuation.definition.tag.xml"}, "2": {"name": "entity.name.tag.xml"},
                        "3": {"name": "punctuation.definition.tag.xml"}},
        "patterns": patterns,
    }


def cdata(embedded):
    """A CDATA section whose content is code in the `embedded` scope."""
    return {
        "begin": r"<!\[CDATA\[", "end": r"\]\]>",
        "beginCaptures": {"0": {"name": "punctuation.definition.string.begin.xml"}},
        "endCaptures": {"0": {"name": "punctuation.definition.string.end.xml"}},
        "contentName": "meta.embedded.block." + embedded.split(".")[-1],
        "patterns": [{"include": embedded}],
    }


def xml_with_cdata(name, scope, rules):
    """An XML document whose CDATA sections hold code: `rules` is (element regex, embedded scope) in order."""
    patterns = [tag_block(element, [cdata(embedded)]) for element, embedded in rules]
    patterns.append({"include": "text.xml"})
    return {"$schema": st["$schema"], "name": name, "scopeName": scope, "patterns": patterns}


twincat = xml_with_cdata("TwinCAT PLC object", "text.xml.twincat", [("Declaration|ST", "source.iec-st")])
l5x = xml_with_cdata("Rockwell L5X export", "text.xml.l5x", [("Text", "source.rockwell-rll"), ("Line", "source.iec-st")])
# PLCopen TC6: an <ST> body holds its text in an XHTML element, as CDATA (Beremiz: <xhtml:p><![CDATA[…]]>) or as
# escaped text (CODESYS: <xhtml xmlns="…">a &lt; b</xhtml>), also in CODESYS <InterfaceAsPlainText> declarations.
# <xhtml> elsewhere (documentation) stays plain XML.
plcopen = {
    "$schema": st["$schema"], "name": "PLCopen XML", "scopeName": "text.xml.plcopen",
    "patterns": [
        tag_block("ST|InterfaceAsPlainText", [
            tag_block(r"xhtml:p|xhtml|p", [
                cdata("source.iec-st"),
                {"name": "meta.embedded.block.iec-st", "begin": r"(?=[^<\s])", "end": r"(?=<)", "patterns": [
                    {"name": "constant.character.entity.xml", "match": r"&(?:[a-zA-Z]+|#[0-9]+|#x[0-9a-fA-F]+);"},
                    {"include": "source.iec-st"},
                ]},
            ]),
            {"include": "text.xml"},
        ]),
        {"include": "text.xml"},
    ],
}

for file, grammar in [("iec-st", st), ("siemens-scl", scl), ("rockwell-rll", rll), ("twincat", twincat), ("l5x", l5x),
                      ("plcopen", plcopen)]:
    (OUT / f"{file}.tmLanguage.json").write_text(json.dumps(grammar, indent=2) + "\n")
    print("wrote", file)


# ---- Monarch (Monaco) tokenizers for editors/web ------------------------------------------------------------------
# Same keyword lists; Monarch regexes are JavaScript sources (no inline flags: ignoreCase covers case).

DURATION = r"(?:LTIME|LT|TIME|T)#[+-]?(?:[0-9_.]+(?:d|h|ms|m|s|us|ns))+"
DATE_TIME = r"(?:LDATE_AND_TIME|DATE_AND_TIME|LDT|DT)#\d{4}-\d{1,2}-\d{1,2}-\d{1,2}:\d{1,2}(?::\d{1,2}(?:\.\d+)?)?"
DATE = r"(?:LDATE|DATE|D)#\d{4}-\d{1,2}-\d{1,2}"
TOD = r"(?:LTIME_OF_DAY|TIME_OF_DAY|LTOD|TOD)#\d{1,2}:\d{1,2}(?::\d{1,2}(?:\.\d+)?)?"


def monarch_st(extra_root=(), extra_words=()):
    return {
        "ignoreCase": True,
        "defaultToken": "",
        "control": CONTROL,
        "vars": VAR,
        "declarations": DECLARATION + list(extra_words),
        "modifiers": MODIFIER,
        "types": TYPES,
        "wordops": OPERATORS,
        "constants": CONSTANTS,
        "self": SELF,
        "tokenizer": {
            "root": list(extra_root) + [
                [r"\(\*", "comment", "@comment"],
                [r"/\*", "comment", "@ccomment"],
                [r"//.*$", "comment"],
                [r"\{", "metatag", "@pragma"],
                ["'", "string", "@sq"],
                ['"', "string", "@dq"],
                [r"\b(?:" + DURATION + "|" + DATE_TIME + "|" + DATE + "|" + TOD + r")", "number"],
                [r"\b(?:[A-Z_]+#)?(?:2|8|16)#[0-9A-F_]+\b", "number.hex"],
                [r"\b(?:[A-Z_]+#)?\d[\d_]*(?:\.\d[\d_]*)?(?:E[+-]?\d+)?\b", "number"],
                [r"%[IQM](?:\*|[XBWDL]?\d+(?:\.\d+)*)", "variable.predefined"],
                [r"[A-Z_][A-Z0-9_]*", {"cases": {
                    "@control": "keyword",
                    "@vars": "keyword",
                    "@declarations": "keyword",
                    "@modifiers": "keyword",
                    "@types": "type",
                    "@wordops": "keyword",
                    "@constants": "constant",
                    "@self": "variable.predefined",
                    "@default": "identifier",
                }}],
                [r":=|=>|\?=|<>|<=|>=|\*\*|[<>=+\-*/&^]", "operator"],
                [r"[;,.:()\[\]]", "delimiter"],
            ],
            # IEC block comments nest in CODESYS: (* a (* b *) c *)
            "comment": [[r"\(\*", "comment", "@push"], [r"\*\)", "comment", "@pop"], [r"[^(*]+", "comment"],
                        [r"[(*]", "comment"]],
            "ccomment": [[r"\*/", "comment", "@pop"], [r"[^*]+", "comment"], [r"\*", "comment"]],
            "pragma": [[r"\}", "metatag", "@pop"], [r"[^}]+", "metatag"]],
            "sq": [[r"\$.", "string.escape"], ["'", "string", "@pop"], [r"[^'$]+", "string"]],
            "dq": [[r"\$.", "string.escape"], ['"', "string", "@pop"], [r"[^\"$]+", "string"]],
        },
    }


monarch = {
    "iec-st": monarch_st(),
    # SCL: "Motor_DB".speed are names, #local variables, absolute DB addresses
    "siemens-scl": monarch_st(extra_root=[
        [r"\(\*", "comment", "@comment"],
        [r"\{", "metatag", "@pragma"],
        ['"[^"\\r\\n]*"', "variable"],
        [r'#(?:[A-Z_][A-Z0-9_]*|"[^"\r\n]*")', "variable.parameter"],
        [r"%(?:DB\d+\.DB[XBWD]\d+(?:\.\d+)?|[IQM][XBWD]?\d+(?:\.\d+)?)", "variable.predefined"],
    ], extra_words=SCL_BLOCKS),
    "rockwell-rll": {
        "defaultToken": "",
        "tokenizer": {"root": [
            [r"[A-Za-z_][A-Za-z0-9_]*(?=\s*\()", "keyword"],
            [r"[\[\],]", "delimiter.bracket"],
            [r"\?", "constant"],
            [r"[+-]?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b", "number"],
            [r"\\[A-Za-z_][A-Za-z0-9_]*", "variable.predefined"],
            [r"[A-Za-z_][A-Za-z0-9_:]*", "identifier"],
            [r"[;()]", "delimiter"],
        ]},
    },
}


def monarch_xml(code):
    """XML whose code elements (regex -> embedded language id) hold their code in CDATA sections."""
    tag = [
        [r"[\w:.-]+", "attribute.name"],
        ["=", "delimiter"],
        ['"[^"]*"', "attribute.value"],
        ["'[^']*'", "attribute.value"],
        [r"/?>", "delimiter", "@pop"],
        [r"\s+", ""],
    ]
    tokenizer = {
        "root": [
            [r"<!--", "comment", "@xmlcomment"],
            [r"<\?", "metatag", "@pi"],
            [r"<!\[CDATA\[", "delimiter.cdata", "@cdata"],
        ] + [
            # the element names its language: the codetag state's argument ($S2)
            [r"(<)(" + element + r")(?=[\s/>])", ["delimiter", {"token": "tag", "next": "@codetag." + language}]]
            for element, language in code.items()
        ] + [
            [r"(<)([\w:.-]+)", ["delimiter", {"token": "tag", "next": "@tag"}]],
            [r"(</)([\w:.-]+)(\s*)(>)", ["delimiter", "tag", "", "delimiter"]],
            [r"&\w+;|&#\w+;", "string.escape"],
            [r"[^<&]+", ""],
        ],
        "tag": tag,
        "codetag": [r for r in tag if r[0] != r"/?>"] + [["/>", "delimiter", "@pop"],
                                                          [">", {"token": "delimiter", "switchTo": "@code.$S2"}]],
        "code": [
            [r"<!\[CDATA\[", {"token": "delimiter.cdata", "next": "@embedded.$S2", "nextEmbedded": "$S2"}],
            [r"\]\]>", "delimiter.cdata"],
            [r"(</)([\w:.-]+)(\s*)(>)", ["delimiter", "tag", "", {"token": "delimiter", "next": "@pop"}]],
            [r"[^<\]]+", ""],
            [r"[<\]]", ""],
        ],
        "embedded": [[r"\]\]>", {"token": "@rematch", "next": "@pop", "nextEmbedded": "@pop"}]],
        "cdata": [[r"\]\]>", "delimiter.cdata", "@pop"], [r"[^\]]+", "string"], [r"\]", "string"]],
        "xmlcomment": [[r"-->", "comment", "@pop"], [r"[^-]+", "comment"], ["-", "comment"]],
        "pi": [[r"\?>", "metatag", "@pop"], [r"[^?]+", "metatag"], [r"\?", "metatag"]],
    }
    return {"defaultToken": "", "tokenizer": tokenizer}


twincat_m = monarch_xml({"Declaration": "iec-st", "ST": "iec-st"})
l5x_m = monarch_xml({"Text": "rockwell-rll", "Line": "iec-st"})
monarch["twincat-xml"] = twincat_m
monarch["rockwell-l5x"] = l5x_m
monarch["plcopen-xml"] = monarch_xml({"xhtml:p": "iec-st", "p": "iec-st"})

WEB = pathlib.Path(__file__).resolve().parent.parent.parent / "web" / "src" / "generated"
WEB.mkdir(parents=True, exist_ok=True)
(WEB / "monarch.json").write_text(json.dumps(monarch, indent=1) + "\n")
print("wrote", WEB / "monarch.json")
