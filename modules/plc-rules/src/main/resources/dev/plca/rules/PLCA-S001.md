---
id: PLCA-S001
title: Hard-coded credential or IP address
severity: warning
category: security
dialects: all
tags: [security, credentials, configuration]
plcopen: -
cwe: CWE-798
parameters:
  namePattern: regex = (?i)(passw(or)?d|passwort|kennwort|pwd|secret|token|api_?key|credential) | Variable and parameter names that hold secrets
  allowedAddresses: list = 0.0.0.0, 255.255.255.255 | IPv4 addresses that are never reported (loopback and netmasks are always allowed)
---
## Description

A string literal is used as a password, token or other secret (assigned to, initialising, passed as or compared with
a variable or parameter whose name matches `namePattern`), or a string literal is an IPv4 address.

## Rationale

Credentials in the PLC program are visible to anyone who can read the project or upload it from the controller,
cannot be rotated without a program change, and are often identical across machines. A password check against a
literal is a back door. Hard-coded IP addresses tie the program to one network layout and hide communication
partners from reviews. Keep secrets in a protected store and network settings in configuration.

## Noncompliant

```st
FUNCTION_BLOCK FB_Login
VAR_INPUT
    sUser : STRING;
    sPassword : STRING;
END_VAR
VAR_OUTPUT
    bAuthorized : BOOL;
END_VAR
VAR
    sServerIp : STRING := '192.168.10.20';
END_VAR
bAuthorized := sUser = 'service' AND sPassword = 'Service#2024';
END_FUNCTION_BLOCK
```

## Compliant

```st
FUNCTION_BLOCK FB_Login
VAR_INPUT
    sUser : STRING;
    sPassword : STRING;
    sExpectedHash : STRING;
END_VAR
VAR_OUTPUT
    bAuthorized : BOOL;
END_VAR
bAuthorized := F_Hash(sPassword) = sExpectedHash;
END_FUNCTION_BLOCK
```
