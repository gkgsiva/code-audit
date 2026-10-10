"""Analyzer plugins, registered here in one place (see ``base`` for the contract)."""

from legacy_audit.analyzers.autosys import AutosysAnalyzer
from legacy_audit.analyzers.base import (
    Analyzer,
    AnalyzerContext,
    Facts,
    Ref,
    Symbol,
    register,
    registered,
    version_key,
)
from legacy_audit.analyzers.build import BuildAnalyzer
from legacy_audit.analyzers.c_cpp import CAnalyzer
from legacy_audit.analyzers.cft import CftAnalyzer
from legacy_audit.analyzers.cobol import CobolAnalyzer
from legacy_audit.analyzers.config import ConfigAnalyzer
from legacy_audit.analyzers.deps import DepsAnalyzer
from legacy_audit.analyzers.java import JavaAnalyzer
from legacy_audit.analyzers.jcl import JclAnalyzer
from legacy_audit.analyzers.mq import MqAnalyzer
from legacy_audit.analyzers.proc import ProcAnalyzer
from legacy_audit.analyzers.shell import ShellAnalyzer
from legacy_audit.analyzers.spring import SpringAnalyzer
from legacy_audit.analyzers.sql import SqlAnalyzer
from legacy_audit.analyzers.web import WebAnalyzer

for _analyzer in (
    BuildAnalyzer(),
    DepsAnalyzer(),
    ConfigAnalyzer(),
    JavaAnalyzer(),
    WebAnalyzer(),
    SpringAnalyzer(),
    SqlAnalyzer(),
    ShellAnalyzer(),
    CAnalyzer(),
    ProcAnalyzer(),
    CobolAnalyzer(),
    JclAnalyzer(),
    AutosysAnalyzer(),
    MqAnalyzer(),
    CftAnalyzer(),
):
    register(_analyzer)

__all__ = [
    "Analyzer",
    "AnalyzerContext",
    "Facts",
    "Ref",
    "Symbol",
    "register",
    "registered",
    "version_key",
]
