"""Generates the TextMate grammars in syntaxes/ (run: python3 scripts/grammars.py). Keyword lists live here once."""
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


def xml_with_cdata(name, scope, rules):
    """An XML document whose CDATA sections hold code: `rules` is (element regex, embedded scope) in order."""
    patterns = []
    for element, embedded in rules:
        patterns.append({
            "begin": r"(<)(" + element + r")\b([^>]*)(>)",
            "end": r"(</)(\2)(>)",
            "beginCaptures": {"1": {"name": "punctuation.definition.tag.xml"}, "2": {"name": "entity.name.tag.xml"},
                              "3": {"patterns": [{"include": "text.xml"}]}, "4": {"name": "punctuation.definition.tag.xml"}},
            "endCaptures": {"1": {"name": "punctuation.definition.tag.xml"}, "2": {"name": "entity.name.tag.xml"},
                            "3": {"name": "punctuation.definition.tag.xml"}},
            "patterns": [{
                "begin": r"<!\[CDATA\[", "end": r"\]\]>",
                "beginCaptures": {"0": {"name": "punctuation.definition.string.begin.xml"}},
                "endCaptures": {"0": {"name": "punctuation.definition.string.end.xml"}},
                "contentName": "meta.embedded.block." + embedded.split(".")[-1],
                "patterns": [{"include": embedded}],
            }],
        })
    patterns.append({"include": "text.xml"})
    return {"$schema": st["$schema"], "name": name, "scopeName": scope, "patterns": patterns}


twincat = xml_with_cdata("TwinCAT PLC object", "text.xml.twincat", [("Declaration|ST", "source.iec-st")])
l5x = xml_with_cdata("Rockwell L5X export", "text.xml.l5x", [("Text", "source.rockwell-rll"), ("Line", "source.iec-st")])

for file, grammar in [("iec-st", st), ("siemens-scl", scl), ("rockwell-rll", rll), ("twincat", twincat), ("l5x", l5x)]:
    (OUT / f"{file}.tmLanguage.json").write_text(json.dumps(grammar, indent=2) + "\n")
    print("wrote", file)
