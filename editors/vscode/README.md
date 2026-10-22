# plca for VS Code

Static analysis of PLC programs while you edit: syntax, model and rule diagnostics, go to definition, find references,
hover, document outline and workspace symbols, from the plca language server.

| Files | Language |
|---|---|
| `.st` | IEC 61131-3 Structured Text (CODESYS, generic) |
| `.TcPOU`, `.TcDUT`, `.TcGVL`, `.TcIO` | TwinCAT PLC objects (ST, LD/FBD networks, CFC, SFC) |
| `.scl`, `.db`, `.udt` | Siemens SCL sources from TIA Portal |
| `.L5X`, `.L5K` | Rockwell Studio 5000 exports (ladder, ST, FBD and SFC routines, tags, AOIs) |
| `.xml` | PLCopen TC6 XML (recognised by its namespace) |

Rules, severities and suppressions come from `plca.yaml` in the workspace folder, exactly as for `plca analyze`, and a
baseline named there hides known findings. "Show documentation of PLCA-…" on a finding opens the rule's description.

## Requirements

Java 21 or newer: set `plca.java.home`, or `JAVA_HOME`, or put `java` on the PATH.

## Settings

| Setting | Default | |
|---|---|---|
| `plca.java.home` | | Java used to run the server |
| `plca.config` | `plca.yaml` | Rule configuration, relative to the workspace folder |
| `plca.threads` | processors − 1 | Threads for whole-project analysis |
| `plca.maxFileSizeMb` | 20 | Larger files are analysed from disk only, not from editor buffers |
| `plca.server.path` | | Development: a `plca` launcher to use instead of the bundled server |
| `plca.trace.server` | `off` | LSP message trace in the plca output |

The editor shows rule findings of the edited file about 0.1 s after typing stops, and findings that depend on the
whole project (unused POUs, double coils, cross-task writes) after about 0.75 s or on save.

## License

GNU General Public License v3.0 or later, like the rest of plca: see `LICENSE.txt`.

