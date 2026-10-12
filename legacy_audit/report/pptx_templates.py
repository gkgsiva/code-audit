"""Corporate PowerPoint templates the PPTX is built on.

Templates are ``.potx`` (or ``.pptx``) files. They are found, in order, in
``$LEGACY_AUDIT_TEMPLATES_DIR``, the copy packaged inside ``legacy_audit/_pptx_templates`` (wheel
installs) and ``templates/`` of a source checkout. ``branding.pptx_template`` in audit.yaml (or
``--template`` / the reports page) picks one by name (``blue``), file name or path; the default is
the first template found. Every slide is built on one of the template's own layouts, so the deck
carries its master, theme colours, fonts, pictures and logo and nothing else.
"""

from __future__ import annotations

import io
import os
import zipfile
from dataclasses import dataclass
from pathlib import Path

SUFFIXES = (".potx", ".pptx")
TEMPLATE_CT = b"presentationml.template.main+xml"
PRESENTATION_CT = b"presentationml.presentation.main+xml"


class TemplateError(Exception):
    """The chosen template is missing or cannot be used."""


@dataclass(frozen=True)
class Template:
    id: str  # the file name without its extension, e.g. RG_TEMPLATE_PPT_HD_BLUE
    label: str  # what people pick from, e.g. Blue
    path: Path


def template_dirs() -> list[Path]:
    env = os.environ.get("LEGACY_AUDIT_TEMPLATES_DIR")
    here = Path(__file__).resolve().parent.parent
    return ([Path(env)] if env else []) + [here / "_pptx_templates", here.parent / "templates"]


def _labels(stems: list[str]) -> list[str]:
    """Drop the part every name shares (RG_TEMPLATE_PPT_HD_) so people see Blue, Orange, Purple."""
    prefix = os.path.commonprefix(stems) if len(stems) > 1 else ""  # noqa: RUF071 (names)
    cut = max(prefix.rfind("_"), prefix.rfind("-"), prefix.rfind(" ")) + 1
    out = []
    for s in stems:
        rest = s[cut:] or s
        out.append(rest.replace("_", " ").replace("-", " ").strip().title() or s)
    return out


def list_templates() -> list[Template]:
    """The templates found in the first folder that has any, sorted by name."""
    for d in template_dirs():
        if not d.is_dir():
            continue
        files = sorted(
            (p for p in d.iterdir() if p.suffix.lower() in SUFFIXES and p.is_file()),
            key=lambda p: p.name.lower(),
        )
        files = [p for p in files if not p.name.startswith("~$")]  # PowerPoint lock files
        if files:
            labels = _labels([p.stem for p in files])
            return [Template(p.stem, lab, p) for p, lab in zip(files, labels, strict=True)]
    return []


def resolve_template(choice: str | None, config_dir: Path | None = None) -> Template:
    """The template for ``choice``: a label (``blue``), a file name or stem, or a path."""
    found = list_templates()
    if not choice:
        if not found:
            raise TemplateError(
                "no PowerPoint template found; put the corporate .potx files in templates/ "
                "or set LEGACY_AUDIT_TEMPLATES_DIR"
            )
        return found[0]
    want = choice.strip()
    for t in found:
        if want.lower() in (t.id.lower(), t.label.lower(), t.path.name.lower()):
            return t
    path = Path(want)
    if not path.is_absolute() and config_dir is not None:
        path = config_dir / path
    if path.suffix.lower() in SUFFIXES and path.is_file():
        return Template(path.stem, path.stem, path)
    names = ", ".join(t.label for t in found) or "none found"
    raise TemplateError(f"PowerPoint template '{choice}' not found (available: {names})")


def template_bytes(path: Path) -> io.BytesIO:
    """The template as a presentation package: a .potx differs only by its main content type."""
    src = zipfile.ZipFile(path)
    buf = io.BytesIO()
    with src, zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as out:
        for info in src.infolist():
            data = src.read(info.filename)
            if info.filename == "[Content_Types].xml":
                data = data.replace(TEMPLATE_CT, PRESENTATION_CT)
            out.writestr(info, data)
    buf.seek(0)
    return buf


def thumbnail(path: Path) -> bytes | None:
    """The preview picture PowerPoint stores in the file (docProps/thumbnail.jpeg), if any."""
    try:
        with zipfile.ZipFile(path) as zf:
            for name in ("docProps/thumbnail.jpeg", "docProps/thumbnail.jpg"):
                if name in zf.namelist():
                    return zf.read(name)
    except (OSError, zipfile.BadZipFile):
        return None
    return None
