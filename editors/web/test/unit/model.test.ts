import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  type Finding,
  type RuleInfo,
  buildTree,
  categoryOf,
  contains,
  countsByPath,
  emptyFilter,
  filterFiles,
  findingsIn,
  formatBytes,
  group,
  languageOf,
  matches,
  overlaps,
  parseRange,
  pathOf,
  severityOf,
  uploadable,
  uriOf,
} from '../../src/model.ts';

const r = (l1: number, c1: number, l2: number, c2: number) => ({
  start: { line: l1, character: c1 },
  end: { line: l2, character: c2 },
});

const f = (path: string, rule: string, severity: Finding['severity'], message: string, line = 0): Finding => ({
  path,
  rule,
  severity,
  message,
  range: r(line, 0, line, 5),
});

const RULES = new Map<string, RuleInfo>([
  ['PLCA-Q001', { id: 'PLCA-Q001', title: 'Unused variable', severity: 'warning', category: 'quality', dialects: [], enabledByDefault: true, plcopen: null, markdown: '' }],
  ['PLCA-S001', { id: 'PLCA-S001', title: 'Hard-coded credential', severity: 'error', category: 'security', dialects: [], enabledByDefault: true, plcopen: null, markdown: '' }],
]);

test('URIs are written as the server writes them (UrisTest) and read back', () => {
  assert.equal(uriOf('Line 1/Main #2 (ä).st'), 'file:///project/Line%201/Main%20%232%20(%C3%A4).st');
  assert.equal(pathOf('file:///project/Line%201/Main%20%232%20(%C3%A4).st'), 'Line 1/Main #2 (ä).st');
  assert.equal(uriOf('a+b.st'), 'file:///project/a+b.st');
  assert.equal(pathOf('file:///etc/passwd'), null);
  assert.equal(pathOf('file:///project/%E0%A4%A'), null);
  for (const p of ['plant/MAIN.st', 'x/y z/ü€.L5X', "it's;a=b,c@d:e.xml"]) {
    assert.equal(pathOf(uriOf(p)), p);
  }
});

test('severities and ranges', () => {
  assert.deepEqual([1, 2, 3, 4, undefined].map(severityOf), ['error', 'warning', 'info', 'hint', 'info']);
  assert.ok(contains(r(1, 0, 3, 0), r(2, 4, 2, 9)));
  assert.ok(contains(r(1, 0, 3, 0), r(1, 0, 3, 0)));
  assert.ok(!contains(r(1, 5, 3, 0), r(1, 4, 2, 0)));
  assert.ok(overlaps(r(1, 0, 1, 10), r(1, 10, 1, 12)));
  assert.ok(!overlaps(r(1, 0, 1, 10), r(1, 11, 1, 12)));
  assert.deepEqual(parseRange('3,4,5,6'), r(3, 4, 5, 6));
  assert.equal(parseRange('3,4,5'), null);
  assert.equal(parseRange('a,b,c,d'), null);
  assert.equal(parseRange(null), null);
});

test('categories of rule findings and of analyzer diagnostics', () => {
  assert.equal(categoryOf('PLCA-S001', RULES), 'security');
  assert.equal(categoryOf('PLC-SYN-001', RULES), 'syntax');
  assert.equal(categoryOf('PLC-L5X-002', RULES), 'syntax');
  assert.equal(categoryOf('PLC-SEM-002', RULES), 'model');
  assert.equal(categoryOf('PLC-CFG-001', RULES), 'configuration');
});

test('filters by severity, rule, category and words', () => {
  const list = [
    f('a/MAIN.st', 'PLCA-Q001', 'warning', "Variable 'x' is declared but never used"),
    f('a/FB.st', 'PLCA-S001', 'error', 'Password in a string literal'),
    f('b/P.st', 'PLC-SYN-001', 'error', "missing ';'"),
  ];
  const all = emptyFilter();
  assert.equal(list.filter((x) => matches(x, all, RULES)).length, 3);
  assert.deepEqual(
    list.filter((x) => matches(x, { ...all, severities: new Set(['error']) }, RULES)).map((x) => x.rule),
    ['PLCA-S001', 'PLC-SYN-001'],
  );
  assert.equal(list.filter((x) => matches(x, { ...all, rules: new Set(['PLCA-Q001']) }, RULES)).length, 1);
  assert.equal(list.filter((x) => matches(x, { ...all, categories: new Set(['syntax']) }, RULES)).length, 1);
  // words match message, path, rule id and rule title, in any order
  assert.equal(list.filter((x) => matches(x, { ...all, text: 'unused MAIN' }, RULES)).length, 1);
  assert.equal(list.filter((x) => matches(x, { ...all, text: 'credential' }, RULES)).length, 1);
  assert.equal(list.filter((x) => matches(x, { ...all, text: 'nothing-like-this' }, RULES)).length, 0);
});

test('groups by file in reading order and by rule, worst first', () => {
  const list = [
    f('b.st', 'PLCA-Q001', 'warning', 'w1', 9),
    f('a.st', 'PLCA-Q001', 'warning', 'w2', 3),
    f('a.st', 'PLCA-S001', 'error', 'e1', 1),
    f('b.st', 'PLCA-Q001', 'warning', 'w3', 2),
  ];
  const files = group(list, 'file');
  assert.deepEqual(files.map((g) => g.key), ['a.st', 'b.st']);
  assert.deepEqual(files[1].findings.map((x) => x.message), ['w3', 'w1']);
  const rules = group(list, 'rule');
  assert.deepEqual(rules.map((g) => [g.key, g.findings.length]), [['PLCA-S001', 1], ['PLCA-Q001', 3]]);
});

test('counts roll up into folders and the root', () => {
  const c = countsByPath([
    f('a/b/x.st', 'R', 'error', ''),
    f('a/y.st', 'R', 'warning', ''),
    f('z.st', 'R', 'info', ''),
  ]);
  assert.deepEqual(c.get('a/b/x.st'), { error: 1, warning: 0, info: 0, hint: 0 });
  assert.deepEqual(c.get('a/b/'), { error: 1, warning: 0, info: 0, hint: 0 });
  assert.deepEqual(c.get('a/'), { error: 1, warning: 1, info: 0, hint: 0 });
  assert.deepEqual(c.get(''), { error: 1, warning: 1, info: 1, hint: 0 });
});

test('findings in a diagram: counts by severity and the first in text order', () => {
  const list = [f('a', 'X', 'warning', 'w', 12), f('a', 'X', 'error', 'e', 15), f('a', 'X', 'info', 'i', 10), f('a', 'X', 'error', 'out', 30)];
  const got = findingsIn(list, r(10, 0, 20, 0));
  assert.deepEqual(got.counts, { error: 1, warning: 1, info: 1, hint: 0 });
  assert.equal(got.total, 3);
  assert.equal(got.first?.message, 'i');
  assert.equal(findingsIn(list, r(40, 0, 50, 0)).first, null);
});

test('languages by extension; PLCopen by its namespace', () => {
  assert.equal(languageOf('x/MAIN.ST'), 'iec-st');
  assert.equal(languageOf('Motor.scl'), 'siemens-scl');
  assert.equal(languageOf('Motor_DB.db'), 'siemens-scl');
  assert.equal(languageOf('FB.TcPOU'), 'twincat-xml');
  assert.equal(languageOf('PlcTask.TcTTO'), 'twincat-xml');
  assert.equal(languageOf('Line.L5X'), 'rockwell-l5x');
  assert.equal(languageOf('plc.xml', '<project xmlns="http://www.plcopen.org/xml/tc6_0201">'), 'plcopen-xml');
  assert.equal(languageOf('other.xml', '<root/>'), 'xml-plain');
  assert.equal(languageOf('plca.yaml'), 'yaml-plain');
});

test('the tree puts folders first and sorts by name', () => {
  const t = buildTree([
    { path: 'p/z.st', size: 1 },
    { path: 'p/A.st', size: 1 },
    { path: 'p/sub/b.st', size: 1 },
    { path: 'top.st', size: 1 },
  ]);
  assert.deepEqual(t.children.map((c) => c.path), ['p/', 'top.st']);
  assert.deepEqual(t.children[0].children.map((c) => c.name), ['sub', 'A.st', 'z.st']);
  assert.deepEqual(filterFiles([{ path: 'p/Motor.st' }, { path: 'q/Valve.st' }], 'p mot').map((x) => x.path), ['p/Motor.st']);
});

test('folder uploads keep sources and configuration only', () => {
  for (const p of ['a/MAIN.st', 'a/FB.TcPOU', 'b/Line.L5X', 'c/plc.xml', 'd/plca.yaml', 'd/base.json', 'Motor.scl']) {
    assert.ok(uploadable(p), p);
  }
  for (const p of ['a/.git/config', 'a/readme.md', 'a/bin/x.exe', '__MACOSX/a/._x.st', 'a/.vs/x.st']) {
    assert.ok(!uploadable(p), p);
  }
});

test('sizes are readable', () => {
  assert.equal(formatBytes(512), '512 B');
  assert.equal(formatBytes(1536), '1.5 KB');
  assert.equal(formatBytes(200 * 1024 * 1024), '200 MB');
  assert.equal(formatBytes(3.25 * 1024 * 1024), '3.3 MB');
});
