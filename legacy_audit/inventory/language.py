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
    ".properties": "properties",
    ".yml": "yaml",
    ".yaml": "yaml",
    ".ini": "ini",
    ".cfg": "ini",
    ".conf": "ini",
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
    ".pco": "procobol",
    ".cbl": "cobol",
    ".cob": "cobol",
    ".cobol": "cobol",
    ".cpy": "cobol",
    ".copy": "cobol",
    ".jcl": "jcl",
    ".proc": "jcl",
    ".bms": "bms",
    ".sh": "shell",
    ".ksh": "shell",
    ".bash": "shell",
    ".csh": "shell",
    ".zsh": "shell",
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
    ".jil": "jil",
    ".mqsc": "mqsc",
    ".mqs": "mqsc",
    ".js": "javascript",
    ".ts": "typescript",
    ".html": "html",
    ".htm": "html",
    ".xhtml": "html",
    ".vm": "velocity",
    ".ftl": "freemarker",
    ".css": "css",
    ".gradle": "groovy",
    ".groovy": "groovy",
    ".mk": "make",
    ".mak": "make",
    ".md": "markdown",
    ".txt": "text",
    ".rst": "text",
    ".csv": "text",
    ".log": "text",
    ".mf": "manifest",
}

ARCHIVE_EXTENSIONS = {".jar", ".war", ".ear", ".zip", ".rar", ".tar", ".gz", ".tgz"}
BINARY_EXTENSIONS = ARCHIVE_EXTENSIONS | {
    ".class", ".o", ".obj", ".so", ".dll", ".exe", ".a", ".lib", ".dylib",
    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".tif", ".tiff",
    ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
    ".ttf", ".woff", ".woff2", ".eot", ".mp3", ".mp4", ".avi",
}  # fmt: skip

FILENAMES: dict[str, str] = {
    "makefile": "make",
    "gnumakefile": "make",
    "crontab": "crontab",
    "manifest.mf": "manifest",
    "dockerfile": "dockerfile",
    ".project": "xml",
    ".classpath": "xml",
    "cmakelists.txt": "make",
}

BUILD_FILES = {
    "pom.xml",
    "build.xml",
    "build.gradle",
    "build.gradle.kts",
    "settings.gradle",
    "ivy.xml",
    "makefile",
    "gnumakefile",
}

SCREEN_LANGUAGES = {"jsp", "html", "bms", "velocity", "freemarker"}
SCRIPT_LANGUAGES = {"shell", "perl", "python", "batch"}
JOB_LANGUAGES = {"jcl", "jil", "crontab"}
SQL_LANGUAGES = {"sql", "plsql"}
CONFIG_LANGUAGES = {"xml", "properties", "yaml", "ini", "json", "mqsc", "manifest"}
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
    lang = EXTENSIONS.get(ext)
    if head is None:
        return lang or "binary"
    # Content sniffing refines or replaces the extension guess.
    if lang == "c" and ext == ".c" and _EXEC_SQL.search(head):
        return "proc"
    if lang in (None, "text"):
        sniffed = sniff(head)
        if sniffed:
            return sniffed
    return lang or "unknown"


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


_TEST_DIRS = {"test", "tests", "testing", "__tests__", "unittest", "it"}
_GENERATED_DIRS = {"generated", "gen-src", "generated-sources", "gen"}
_GENERATED_MARK = re.compile(r"generated by|do not edit|@generated|auto-generated", re.I)
_TEST_NAME = re.compile(r"(^test_.*|.*(Test|Tests|IT|TestCase)\.\w+$|.*_test\.\w+$)")


def detect_category(rel_path: str, language: str, head: str | None) -> Category:
    p = PurePosixPath(rel_path)
    name = p.name.lower()
    dirs = {d.lower() for d in p.parts[:-1]}
    if language in ("binary", "archive"):
        return Category.BINARY
    if name in BUILD_FILES or language == "make" or p.suffix.lower() in (".gradle", ".kts"):
        return Category.BUILD
    if dirs & _GENERATED_DIRS or (head is not None and _GENERATED_MARK.search(head[:2000])):
        return Category.GENERATED
    if language not in DOC_LANGUAGES and (dirs & _TEST_DIRS or _TEST_NAME.match(p.name)):
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
    if language == "unknown":
        return Category.OTHER
    return Category.SOURCE
