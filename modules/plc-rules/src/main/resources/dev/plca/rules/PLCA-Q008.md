---
id: PLCA-Q008
title: Unused POU
severity: warning
category: quality
dialects: all
tags: [maintainability, dead-code]
plcopen: CP2
cwe: CWE-561
---
## Description

A program, function block, function, class or Rockwell routine that nothing runs: it is not scheduled in a task,
not called, not instantiated and not referenced from code that runs.

## Rationale

Code that never runs still has to be read, reviewed and maintained, and a routine that was meant to run but is not
called (a missing `JSR`, a program left out of the task) is a bug that testing on site may not reveal. PLCopen Coding
Guidelines rule CP2 *All code shall be used in the application*.

Reachability starts at the entry points: the programs of the configured tasks (or every PROGRAM when the project has no
task configuration), Siemens organisation blocks and Rockwell programs. From there it follows calls, `JSR`s, instance
declarations (anywhere, including global variable lists and structures), `EXTENDS`/`IMPLEMENTS` and references to
POUs in expressions. Methods, actions and properties are not reported on their own. A dialect whose code in the
project has no entry point — a folder of library blocks — is not checked.

## Noncompliant

```st
PROGRAM P_Main
VAR
    nCount : INT;
END_VAR
nCount := F_Used(nCount);
END_PROGRAM

FUNCTION F_Used : INT
VAR_INPUT
    n : INT;
END_VAR
F_Used := n + 1;
END_FUNCTION

FUNCTION F_Forgotten : INT
VAR_INPUT
    n : INT;
END_VAR
F_Forgotten := n - 1;
END_FUNCTION
```

## Compliant

```st
PROGRAM P_Main
VAR
    nCount : INT;
END_VAR
nCount := F_Used(nCount);
END_PROGRAM

FUNCTION F_Used : INT
VAR_INPUT
    n : INT;
END_VAR
F_Used := n + 1;
END_FUNCTION
```
