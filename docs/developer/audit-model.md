# audit-model

Package root: `com.legacyaudit.model`. Plain Java, no Spring. Every other module depends on it.

It holds three things: the **facts** a scan produces, the **configuration** a scan reads, and
**JSON written exactly as the Python tool wrote it**, so outputs can be compared byte for byte.

## Packages

| Package | Classes | Purpose |
| --- | --- | --- |
| `model` | `AppInfo` | Name and version of the tool |
| `model.config` | `ConfigLoader`, `ConfigReader`, `Fields`, `AuditConfig`, `LoadedConfig`, `Overrides`, `ConfigException` | Reading and checking `audit.yaml` and `audit-overrides.yaml` |
| `model.facts` | `Fact`, `Doc`, `Schema`, `Schemas`, `FileRecord`, `Component`, `Finding`, `Evidence`, `RunInfo`, enums | The facts and their fields |
| `model.json` | `PyJson` | JSON text as Python's `json.dumps` writes it |

## Configuration

- `ConfigLoader.load(path)` reads `audit.yaml` (SnakeYAML, YAML 1.1 like PyYAML) and returns a
  `LoadedConfig`: the checked `AuditConfig`, the file, its folder (relative paths in the
  config are resolved against it) and the config hash.
- `ConfigReader` turns the YAML mapping into an `AuditConfig`. It accepts every key the Python
  tool accepted and rejects any other with the same message, collecting all problems before it
  fails (`Fields` reads one mapping key by key and records `inventory.workers: ...`-style
  locations). Invalid input throws `ConfigException`.
- `AuditConfig` is a record. Only what the ported code uses is typed (application, source,
  include/exclude, components, folders, target, counting, inventory, copybook paths, rule
  packs). Every other section is still validated and kept in `dump()`; read it with
  `setting(section, key)`. Typed accessors are added as phases need them, for example
  `sqlDialect()` (`sql.dialect`, default `oracle`).
- `Overrides` reads `audit-overrides.yaml`: files to exclude or include, component
  assignments, renames and types. The engine applies them on every run and reports how many
  items each override matched.

## Facts

A fact is one thing the scan found: a file, a component, an SQL statement, a service, a
finding. Facts are written to `data/<entity>.json` in the run folder.

- `Fact` is the common interface. Every fact has an `id` and `evidence`. Ids are stable across
  runs: `Fact.makeId(kind, parts...)` hashes the kind and key fields (SHA-256, first 16 hex
  characters), exactly as Python did, so ids from both tools are equal.
- Core facts have their own classes: `FileRecord` (one file of the inventory), `Component`,
  `Finding`, `Evidence`, `RunInfo` (`run.json`), `InputCoverage`, `SourceInfo`.
- All other facts (entry points, services, screens, SQL statements, database objects,
  integrations, web services, code edges, batch jobs, libraries, tech items, metrics, code
  units, vulnerabilities ...) are `Doc`s: an ordered field map checked against a `Schema`.
  `Doc.of(Schemas.SQL_STATEMENT, "id", id, "text", text, ...)` fills fields in any order;
  unknown field names are an error, missing ones get the default. `Schemas` lists the fields,
  their order, types and defaults, taken from the Python models (generated, do not edit by
  hand; there are 22 schemas).
- `MapValues` reads fields back from parsed JSON, where numbers come back as `Integer`, `Long`
  or `Double`.
- Enums (`Category`, `ComponentType`, `Severity`, `Origin`, `CoverageStatus`) implement
  `Valued`: they are written as their lower-case value (`"source"`, `"online-mainframe"`).

## JSON like Python

`PyJson` writes JSON the way the Python tool did, which is what makes byte-for-byte comparison
possible:

- `pretty(value)`: `json.dumps(indent=2, sort_keys=True, ensure_ascii=False)`.
- `compact(value)`: the one-line form with sorted keys (`json.dumps(value, sort_keys=True)`),
  used in CSV cells and the file cache; `dumps(value, sortKeys)` is Python's default form.
- Keys are sorted by Unicode code point (`compareCodePoints`), not by Java's UTF-16 order.
- Numbers are written as Python writes them: `1.0` stays `1.0`, large and small doubles use
  Python's `repr` (`PyJson.repr`).

## Typical use

```java
LoadedConfig loaded = ConfigLoader.load(Path.of("audit.yaml"));
AuditConfig cfg = loaded.config();
String dialect = cfg.sqlDialect();

Doc stmt = Doc.of(Schemas.SQL_STATEMENT,
        "id", Fact.makeId("sql", file, line, text),
        "file", file,
        "text", text);
String json = PyJson.pretty(stmt.toMap());
```

## Changing the model

- A new field or fact type must match the Python model first (the facts JSON Schema and the
  CSV columns depend on the order). Add it to `Schemas` in the same position as in Python.
- Adding a typed config accessor: read it from `dump()` or add a record component and fill it in
  `ConfigReader`; keep the Python defaults.
