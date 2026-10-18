import unittest

from harvester import classify as c
from harvester import licenses


class ClassifyTest(unittest.TestCase):
    def test_codesys_st(self):
        src = b"FUNCTION_BLOCK FB_Motor\nVAR_INPUT\n bStart : BOOL;\nEND_VAR\nIF bStart THEN x := 1; END_IF\nEND_FUNCTION_BLOCK\n"
        self.assertEqual(c.classify("src/FB_Motor.st", src), c.Match(c.CODESYS, "st"))

    def test_smalltalk_st_rejected(self):
        src = b"Object subclass: #Foo\n  instanceVariableNames: 'a b'\n  classVariableNames: ''\n"
        self.assertIsNone(c.classify("Foo.st", src))

    def test_stringtemplate_st_rejected(self):
        self.assertIsNone(c.classify("t.st", b"class(name) ::= <<\npublic class $name$ {}\n>>\n"))

    def test_siemens_code_saved_as_st(self):
        src = b"DATA_BLOCK \"Motor_DB\"\nVERSION : 0.1\nVAR\n speed : INT;\nEND_VAR\nBEGIN\nEND_DATA_BLOCK\n"
        self.assertEqual(c.classify("Motor.st", src).dialect, c.SIEMENS)

    def test_twincat_pou(self):
        src = b'\xef\xbb\xbf<?xml version="1.0" encoding="utf-8"?>\n<TcPlcObject Version="1.1.0.1">\n<POU Name="MAIN"/>'
        self.assertEqual(c.classify("PLC/POUs/MAIN.TcPOU", src), c.Match(c.TWINCAT, "TcPOU"))
        self.assertIsNone(c.classify("x.TcPOU", b"<Other/>"))

    def test_scl_db_udt(self):
        self.assertEqual(c.classify("a.scl", b'FUNCTION_BLOCK "FB1"\nBEGIN\nEND_FUNCTION_BLOCK').format, "scl")
        self.assertEqual(c.classify("a.db", b'DATA_BLOCK "D"\nBEGIN\nEND_DATA_BLOCK').dialect, c.SIEMENS)
        self.assertIsNone(c.classify("app.db", b"SQLite format 3\x00\x10\x00"))
        self.assertEqual(c.classify("t.udt", b'TYPE "U"\nSTRUCT a : INT; END_STRUCT;\nEND_TYPE').format, "udt")
        self.assertIsNone(c.classify("t.udt", b"random bytes"))

    def test_rockwell(self):
        self.assertEqual(c.classify("a.L5X", b'<?xml?><RSLogix5000Content SchemaRevision="1.0">').dialect,
                         c.ROCKWELL)
        self.assertEqual(c.classify("a.L5K", b"IE_VER := 2.25;\nCONTROLLER Foo").dialect, c.ROCKWELL)

    def test_plcopen(self):
        src = b'<?xml version="1.0"?><project xmlns="http://www.plcopen.org/xml/tc6_0201"><fileHeader/>'
        self.assertEqual(c.classify("p.xml", src), c.Match(c.PLCOPEN, "plcopen-tc6_0201"))
        self.assertIsNone(c.classify("pom.xml", b"<project xmlns='http://maven.apache.org/POM/4.0.0'>"))

    def test_utf16(self):
        src = "PROGRAM P\nVAR\n x : INT;\nEND_VAR\nEND_PROGRAM\n".encode("utf-16")
        self.assertEqual(c.classify("p.st", src).dialect, c.CODESYS)

    def test_binary_rejected(self):
        self.assertIsNone(c.classify("x.st", b"END_VAR END_IF \x00\x01\x02"))

    def test_license_files(self):
        self.assertTrue(c.is_license_file("LICENSE"))
        self.assertTrue(c.is_license_file("LICENSE.md"))
        self.assertTrue(c.is_license_file("COPYING.LESSER"))
        self.assertFalse(c.is_license_file("docs/LICENSE"))
        self.assertFalse(c.is_license_file("licensed_features.txt"))

    def test_lines(self):
        self.assertEqual(c.count_lines(""), 0)
        self.assertEqual(c.count_lines("a\nb"), 2)
        self.assertEqual(c.count_lines("a\nb\n"), 2)


class LicenseTest(unittest.TestCase):
    def test_policy(self):
        self.assertEqual(licenses.policy("MIT"), licenses.FIXTURE_OK)
        self.assertEqual(licenses.policy("GPL-3.0"), licenses.LOCAL_ONLY)
        self.assertEqual(licenses.policy("LGPL-3.0"), licenses.LOCAL_ONLY)
        self.assertEqual(licenses.policy(licenses.NOASSERTION), licenses.LOCAL_ONLY)
        self.assertEqual(licenses.policy(None), licenses.LOCAL_ONLY)

    def test_github_and_gitlab_mapping(self):
        self.assertEqual(licenses.from_github(None), licenses.NOASSERTION)
        self.assertEqual(licenses.from_github({"spdx_id": "Apache-2.0"}), "Apache-2.0")
        self.assertEqual(licenses.from_gitlab({"key": "mit"}), "MIT")
        self.assertEqual(licenses.from_gitlab({"key": "something-odd"}), licenses.NOASSERTION)


if __name__ == "__main__":
    unittest.main()
