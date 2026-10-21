"""Language and category detection: extension first, then content sniffing."""

from __future__ import annotations

import re
from pathlib import PurePosixPath

from legacy_audit.model import Category

EXTENSIONS: dict[str, str] = {
    ".java": "java",
    ".jsp": "jsp",
    ".jspf": "jsp",
    ".jspx": "jsp",
    ".tag": "jsp",
    ".tld": "xml",
    ".xml": "xml",
    ".xsd": "xml",
    ".wsdl": "xml",
    ".xsl": "xml",
    ".xslt": "xml",
    ".dtd": "xml",
    ".xmi": "xml",
    ".xjb": "xml",
    ".wsdd": "xml",
    ".jrxml": "xml",
    ".bpmn": "xml",
    ".tagx": "jsp",
    ".properties": "properties",
    ".policy": "properties",
    ".yml": "yaml",
    ".yaml": "yaml",
    ".ini": "ini",
    ".cfg": "ini",
    ".conf": "ini",
    ".toml": "ini",
    ".json": "json",
    ".c": "c",
    ".h": "c",
    ".cpp": "cpp",
    ".cc": "cpp",
    ".cxx": "cpp",
    ".hpp": "cpp",
    ".hh": "cpp",
    ".hxx": "cpp",
    ".pc": "proc",
    ".sqc": "proc",  # DB2 embedded SQL in C
    ".ec": "proc",  # Informix ESQL/C
    ".sqb": "procobol",  # DB2 embedded SQL in COBOL
    ".pco": "procobol",
    ".cbl": "cobol",
    ".cob": "cobol",
    ".cobol": "cobol",
    ".cpy": "cobol",
    ".copy": "cobol",
    ".cbk": "cobol",
    ".jcl": "jcl",
    ".proc": "jcl",
    ".bms": "bms",
    ".sh": "shell",
    ".ksh": "shell",
    ".bash": "shell",
    ".csh": "shell",
    ".zsh": "shell",
    ".env": "shell",
    ".profile": "shell",
    ".awk": "awk",
    ".sed": "sed",
    ".tcl": "tcl",
    ".ps1": "powershell",
    ".vbs": "vbscript",
    ".pl": "perl",
    ".pm": "perl",
    ".py": "python",
    ".bat": "batch",
    ".cmd": "batch",
    ".sql": "sql",
    ".ddl": "sql",
    ".pks": "plsql",
    ".pkb": "plsql",
    ".pls": "plsql",
    ".plb": "plsql",
    ".prc": "plsql",
    ".fnc": "plsql",
    ".trg": "plsql",
    ".pkg": "plsql",
    ".pck": "plsql",
    ".spc": "plsql",
    ".bdy": "plsql",
    ".tps": "plsql",
    ".tpb": "plsql",
    ".typ": "plsql",
    ".vw": "sql",
    ".ctl": "sqlloader",
    ".jil": "jil",
    ".mqsc": "mqsc",
    ".mqs": "mqsc",
    ".js": "javascript",
    ".mjs": "javascript",
    ".cjs": "javascript",
    ".jsx": "javascript",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".mts": "typescript",
    ".cts": "typescript",
    ".prisma": "prisma",
    ".kt": "kotlin",
    ".scala": "scala",
    ".sqlj": "sqlj",
    ".cs": "csharp",
    ".csx": "csharp",
    ".vb": "vbnet",
    # Visual Basic 6: forms, modules, classes, user controls (.ctl, sniffed), designers.
    ".bas": "vb6",
    ".frm": "vb6",
    ".cls": "vb6",
    ".dob": "vb6",
    ".pag": "vb6",
    ".dsr": "vb6",
    ".vbp": "vb6-project",
    ".vbg": "vb6-project",
    ".vbw": "ini",
    # .NET projects and solutions (MSBuild), WPF/WinUI/JavaFX layouts, ASP.NET pages.
    ".csproj": "msbuild",
    ".vbproj": "msbuild",
    ".sln": "msbuild",
    ".props": "msbuild",
    ".targets": "msbuild",
    ".nuspec": "xml",
    ".resx": "xml",
    ".settings": "xml",
    ".config": "xml",
    ".xaml": "xaml",
    ".fxml": "fxml",
    ".aspx": "aspx",
    ".ascx": "aspx",
    ".master": "aspx",
    ".cshtml": "razor",
    ".vbhtml": "razor",
    ".razor": "razor",
    ".asmx": "aspnet",
    ".svc": "aspnet",
    ".ashx": "aspnet",
    ".asax": "aspnet",
    ".php": "php",
    ".rb": "ruby",
    ".go": "go",
    ".html": "html",
    ".htm": "html",
    ".xhtml": "html",
    ".vm": "velocity",
    ".ftl": "freemarker",
    ".css": "css",
    ".scss": "css",
    ".sass": "css",
    ".less": "css",
    ".gradle": "groovy",
    ".groovy": "groovy",
    ".mk": "make",
    ".mak": "make",
    ".md": "markdown",
    ".txt": "text",
    ".rst": "text",
    ".log": "text",
    ".lst": "text",
    ".lis": "text",
    ".out": "text",
    ".csv": "data",
    ".tsv": "data",
    ".psv": "data",
    ".dat": "data",
    ".del": "data",
    ".mf": "manifest",
}

ARCHIVE_EXTENSIONS = {".jar", ".war", ".ear", ".zip", ".rar", ".tar", ".gz", ".tgz"}
BINARY_EXTENSIONS = ARCHIVE_EXTENSIONS | {
    ".class", ".o", ".obj", ".so", ".dll", ".exe", ".a", ".lib", ".dylib",
    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".tif", ".tiff",
    ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
    ".pyc", ".pyo", ".ser", ".jks", ".keystore", ".p12", ".pfx", ".der", ".jasper",
    ".gnt", ".int", ".idy", ".lbr", ".pdb", ".ilk",
    ".z", ".bz2", ".xz", ".7z", ".dmp", ".db", ".sqlite", ".mdb",
    ".ttf", ".woff", ".woff2", ".eot", ".mp3", ".mp4", ".avi",
    # VB6 binary form/control resources, ActiveX controls, type libraries, .NET resources.
    ".frx", ".ctx", ".dcx", ".dsx", ".pgx", ".dox", ".ocx", ".tlb", ".olb", ".res",
    ".resources", ".snk", ".nupkg", ".suo", ".baml",
}  # fmt: skip

FILENAMES: dict[str, str] = {
    "makefile": "make",
    "gnumakefile": "make",
    "crontab": "crontab",
    "manifest.mf": "manifest",
    "dockerfile": "dockerfile",
    "containerfile": "dockerfile",
    ".project": "xml",
    ".classpath": "xml",
    "cmakelists.txt": "make",
    ".profile": "shell",
    ".kshrc": "shell",
    ".bashrc": "shell",
    ".bash_profile": "shell",
    ".env": "shell",
    ".nvmrc": "text",
    ".htaccess": "ini",
    "web.config": "xml",
    "package-lock.json": "lockfile",
    "npm-shrinkwrap.json": "lockfile",
    "yarn.lock": "lockfile",
    "pnpm-lock.yaml": "lockfile",
    "packages.lock.json": "lockfile",  # NuGet lock file
    "packages.config": "xml",
    "nuget.config": "xml",
}

# Copies kept beside the real file (foo.java.bak, run.ksh.old, foo.c~, x.sql.20190312): listed,
# but their lines are not counted, so the estate is not measured twice.
BACKUP_EXTENSIONS = {
    ".bak", ".bkp", ".backup", ".old", ".orig", ".sav", ".save", ".prev", ".tmp", ".swp",
    ".rej", ".copy_of",
}  # fmt: skip
_DATED = re.compile(r"^\.\d+$")

# Languages listed in the inventory whose lines are not code: data files, backup copies and
# npm lock files (generated lists of installed packages, often tens of thousands of lines).
NO_LOC_LANGUAGES = {"data", "backup", "lockfile"}

BUILD_FILES = {
    "pom.xml",
    "build.xml",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "ivy.xml",
    "makefile",
    "gnumakefile",
    "package.json",
    "angular.json",
}
# npm lock files: the exact versions of every package installed (read by the deps analyzer).
NPM_LOCK_FILES = {"package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml"}
# NuGet package lists: packages.config (classic .NET Framework projects), central package
# versions and the NuGet lock file (read by the dotnet analyzer).
NUGET_FILES = {"packages.config", "packages.lock.json", "directory.packages.props"}
# Project files whose language is a build language (MSBuild, Visual Basic 6 projects).
BUILD_LANGUAGES = {"make", "msbuild", "vb6-project"}

SCREEN_LANGUAGES = {
    "jsp", "html", "bms", "velocity", "freemarker", "xaml", "fxml", "aspx", "razor",
}  # fmt: skip
SCRIPT_LANGUAGES = {
    "shell", "perl", "python", "batch", "awk", "sed", "tcl", "powershell", "vbscript",
}  # fmt: skip
JOB_LANGUAGES = {"jcl", "jil", "crontab"}
SQL_LANGUAGES = {"sql", "plsql"}
CONFIG_LANGUAGES = {
    "xml", "properties", "yaml", "ini", "json", "mqsc", "manifest", "sqlloader", "prisma",
    "dockerfile",
}  # fmt: skip
DOC_LANGUAGES = {"markdown", "text"}

_SHEBANG_LANG = {
    "sh": "shell", "ksh": "shell", "bash": "shell", "csh": "shell", "zsh": "shell",
    "perl": "perl", "python": "python", "python3": "python", "python2": "python",
}  # fmt: skip
_JCL_JOB = re.compile(r"^//[A-Z@#$][A-Z0-9@#$]{0,7}\s+JOB\b", re.MULTILINE)
_JIL = re.compile(r"^\s*insert_job\s*:", re.MULTILINE | re.IGNORECASE)
_COBOL_DIV = re.compile(r"^.{0,7}\s*IDENTIFICATION\s+DIVISION", re.MULTILINE | re.IGNORECASE)
_COBOL_FIXED = re.compile(r"^\d{6}[ *\-/]", re.MULTILINE)
_EXEC_SQL = re.compile(r"\bEXEC\s+SQL\b", re.IGNORECASE)
_VB6_HEADER = re.compile(r"^\s*(VERSION\s+5\.00|Begin\s+VB\.)", re.I)
_MQSC = re.compile(r"^\s*DEFINE\s+(QLOCAL|QREMOTE|QALIAS|CHANNEL|QMODEL)\b", re.M | re.I)


def detect_language(rel_path: str, head: str | None) -> str:
    """Return a language id. ``head`` is the decoded start of the file (None for binaries)."""
    p = PurePosixPath(rel_path)
    name = p.name.lower()
    ext = p.suffix.lower()
    if ext in BINARY_EXTENSIONS:
        return "archive" if ext in ARCHIVE_EXTENSIONS else "binary"
    if name in FILENAMES:
        return FILENAMES[name]
    if name.startswith("makefile.") and ext not in EXTENSIONS:  # makefile.aix, Makefile.unix
        return "make"
    if name.startswith(("dockerfile.", "containerfile.")) or ext == ".dockerfile":
        return "dockerfile"  # Dockerfile.prod, orders.dockerfile
    lang = EXTENSIONS.get(ext)
    if head is None:
        return lang or "binary"
    if is_backup(name):
        return "backup"
    # Content sniffing refines or replaces the extension guess.
    if lang == "c" and ext == ".c" and _EXEC_SQL.search(head):
        return "proc"
    if ext == ".ctl" and _VB6_HEADER.match(head.lstrip("\ufeff")):
        return "vb6"  # a Visual Basic 6 user control, not a SQL*Loader control file
    if lang in (None, "text", "data"):
        sniffed = sniff(head)
        if sniffed:
            return sniffed
    return lang or other_language(ext)


def other_language(ext: str) -> str:
    """Label for a text file no rule recognises: named by its extension, never "unknown"."""
    return f"other ({ext})" if ext else "other (no extension)"


def is_other(language: str) -> bool:
    return language.startswith("other (")


def is_backup(name: str) -> bool:
    """``name`` is the lower-case file name."""
    if name.endswith("~"):
        return True
    p = PurePosixPath(name)
    if p.suffix in BACKUP_EXTENSIONS:
        return True
    # run.ksh_old, Foo.java-bak: a known extension with a backup word after it.
    m = re.match(r"^(\.\w+)[_-](\w+)$", p.suffix)
    if m and m.group(1) in EXTENSIONS and "." + m.group(2) in BACKUP_EXTENSIONS:
        return True
    # x.sql.20190312, foo.c.1: a known extension followed by a date or a number.
    return bool(_DATED.match(p.suffix)) and PurePosixPath(p.stem).suffix in EXTENSIONS


def sniff(head: str) -> str | None:
    first = head.lstrip("\ufeff").split("\n", 1)[0]
    if first.startswith("#!"):
        words = first[2:].split()
        prog = PurePosixPath(words[0]).name if words else ""
        if prog == "env" and len(words) > 1:
            prog = words[1]
        return _SHEBANG_LANG.get(prog, "shell")
    if _JIL.search(head):
        return "jil"
    if _JCL_JOB.search(head):
        return "jcl"
    if _COBOL_DIV.search(head) or (
        len(_COBOL_FIXED.findall(head)) >= 3 and "DIVISION" in head.upper()
    ):
        return "cobol"
    if _MQSC.search(head):
        return "mqsc"
    return None


_TEST_DIRS = {"test", "tests", "testing", "__tests__", "unittest", "it", "e2e", "cypress"}
# .NET test projects are folders named after the project: Billing.Tests, Billing.UnitTests.
_TEST_DIR_SUFFIX = (".tests", ".test", ".unittests", ".integrationtests", ".specs")
_GENERATED_DIRS = {"generated", "gen-src", "generated-sources", "gen"}
_GENERATED_MARK = re.compile(r"generated by|do not edit|@generated|auto-generated", re.I)
_TEST_NAME = re.compile(
    r"(^test_.*|.*(Test|Tests|IT|TestCase)\.\w+$|.*_test\.\w+$|"
    r".*\.(spec|test|e2e-spec|cy)\.(ts|tsx|js|jsx|mjs|cjs)$)"
)


def detect_category(rel_path: str, language: str, head: str | None) -> Category:
    p = PurePosixPath(rel_path)
    name = p.name.lower()
    dirs = {d.lower() for d in p.parts[:-1]}
    if language in ("binary", "archive"):
        return Category.BINARY
    if (
        name in BUILD_FILES
        or name in NPM_LOCK_FILES
        or name in NUGET_FILES
        or language in BUILD_LANGUAGES
        or p.suffix.lower() in (".gradle", ".kts")
    ):
        return Category.BUILD
    if dirs & _GENERATED_DIRS or (head is not None and _GENERATED_MARK.search(head[:2000])):
        return Category.GENERATED
    if language not in DOC_LANGUAGES and (
        dirs & _TEST_DIRS
        or any(d.endswith(_TEST_DIR_SUFFIX) for d in dirs)
        or _TEST_NAME.match(p.name)
    ):
        return Category.TEST
    if language in SCREEN_LANGUAGES:
        return Category.SCREEN
    if language in SQL_LANGUAGES:
        return Category.SQL
    if language in JOB_LANGUAGES:
        return Category.JOB
    if language in SCRIPT_LANGUAGES:
        return Category.SCRIPT
    if language in CONFIG_LANGUAGES:
        return Category.CONFIG
    if language in DOC_LANGUAGES:
        return Category.DOC
    if language in NO_LOC_LANGUAGES or is_other(language):
        return Category.OTHER
    return Category.SOURCE
