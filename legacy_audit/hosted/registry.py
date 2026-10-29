"""Who may do what: users, the projects under the audits root with their owner and members, and
the activity log. Kept as small YAML/JSON-lines files in ``<audits root>/.legacy-audit/``.

A project is a folder under the audits root holding ``audit.yaml``. The registry adds who owns
it; a folder copied in by hand has no owner until an admin assigns one.
"""

from __future__ import annotations

import json
import re
import shutil
import threading
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import yaml

from legacy_audit.hosted.settings import ROLES, HostSettings, SettingsError

USERS_FILE = "users.yaml"
PROJECTS_FILE = "projects.yaml"
ACTIVITY_FILE = "activity.jsonl"
PROJECT_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$")
USER_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,80}$")
PROJECT_ROLES = ("owner", "contributor", "viewer")
RANK = {"viewer": 1, "contributor": 2, "owner": 3, "admin": 4}


def now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def slugify(name: str) -> str:
    s = re.sub(r"[^A-Za-z0-9._-]+", "-", name.strip()).strip("-._").lower()
    return s[:60] or "project"


@dataclass
class User:
    name: str
    role: str = "owner"
    disabled: bool = False
    created_at: str = field(default_factory=now)
    last_login: str | None = None


@dataclass
class ProjectMeta:
    id: str
    title: str
    owner: str | None = None
    members: dict[str, str] = field(default_factory=dict)  # user -> contributor | viewer
    created_at: str = field(default_factory=now)
    archived: bool = False
    hidden_reports: list[str] = field(default_factory=list)
    hidden_sections: list[str] = field(default_factory=list)


class Registry:
    def __init__(self, host: HostSettings) -> None:
        self.host = host
        self.dir = host.admin_dir
        self._lock = threading.RLock()

    # ---- files ----

    def _load(self, name: str) -> dict[str, Any]:
        path = self.dir / name
        if not path.exists():
            return {}
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        return data if isinstance(data, dict) else {}

    def _save(self, name: str, data: dict[str, Any]) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = self.dir / (name + ".tmp")
        tmp.write_text(yaml.safe_dump(data, sort_keys=True, allow_unicode=True), encoding="utf-8")
        tmp.replace(self.dir / name)

    # ---- users ----

    def users(self) -> list[User]:
        with self._lock:
            raw = self._load(USERS_FILE).get("users", {})
            out = [User(name=n, **v) for n, v in sorted(raw.items())]
        names = {u.name for u in out}
        out += [User(name=a, role="admin") for a in self.host.admins if a not in names]
        for u in out:
            if u.name in self.host.admins:
                u.role, u.disabled = "admin", False
        return sorted(out, key=lambda u: u.name.lower())

    def user(self, name: str) -> User | None:
        return next((u for u in self.users() if u.name == name), None)

    def save_user(self, user: User) -> User:
        if not USER_NAME.match(user.name):
            raise SettingsError(f"'{user.name}' is not an allowed user name")
        if user.role not in ROLES:
            raise SettingsError(f"role must be one of {', '.join(ROLES)}")
        with self._lock:
            data = self._load(USERS_FILE)
            users = data.setdefault("users", {})
            d = asdict(user)
            d.pop("name")
            users[user.name] = d
            self._save(USERS_FILE, data)
        return self.user(user.name) or user

    def delete_user(self, name: str) -> None:
        with self._lock:
            data = self._load(USERS_FILE)
            data.get("users", {}).pop(name, None)
            self._save(USERS_FILE, data)

    # ---- projects ----

    def project_dir(self, project_id: str) -> Path:
        if not PROJECT_ID.match(project_id):
            raise SettingsError(f"'{project_id}' is not a project folder name")
        return self.host.audits_root / project_id

    def _metas(self) -> dict[str, dict[str, Any]]:
        projects = self._load(PROJECTS_FILE).get("projects", {})
        return projects if isinstance(projects, dict) else {}

    def projects(self) -> list[ProjectMeta]:
        """Every project folder under the audits root (with audit.yaml), registered or not."""
        with self._lock:
            metas = self._metas()
        root = self.host.audits_root
        found: list[ProjectMeta] = []
        if root.is_dir():
            for d in sorted(root.iterdir(), key=lambda p: p.name.lower()):
                if not d.is_dir() or not PROJECT_ID.match(d.name) or d.name.startswith("."):
                    continue
                if not (d / "audit.yaml").exists():
                    continue
                m = metas.get(d.name)
                found.append(
                    ProjectMeta(id=d.name, **m) if m else ProjectMeta(id=d.name, title=d.name)
                )
        return found

    def project(self, project_id: str) -> ProjectMeta | None:
        return next((p for p in self.projects() if p.id == project_id), None)

    def save_project(self, meta: ProjectMeta) -> ProjectMeta:
        for user, role in meta.members.items():
            if role not in PROJECT_ROLES[1:]:
                raise SettingsError(f"{user}: a member is a contributor or a viewer")
        with self._lock:
            data = self._load(PROJECTS_FILE)
            d = asdict(meta)
            d.pop("id")
            data.setdefault("projects", {})[meta.id] = d
            self._save(PROJECTS_FILE, data)
        return meta

    def delete_project(self, project_id: str) -> None:
        folder = self.project_dir(project_id)
        with self._lock:
            data = self._load(PROJECTS_FILE)
            data.get("projects", {}).pop(project_id, None)
            self._save(PROJECTS_FILE, data)
        if folder.is_dir():
            shutil.rmtree(folder)

    def role_in(self, user: User, project: ProjectMeta) -> str | None:
        """The user's role on a project: admin, owner, contributor, viewer or None (no access)."""
        if user.role == "admin":
            return "admin"
        if project.owner == user.name:
            return "owner"
        role = project.members.get(user.name)
        if role is None:
            return None
        # a global viewer stays a viewer whatever a project says
        return "viewer" if user.role == "viewer" else role

    # ---- activity ----

    def log(self, user: str | None, action: str, project: str | None = None, **detail: Any) -> None:
        entry = {"time": now(), "user": user, "action": action, "project": project, **detail}
        with self._lock:
            self.dir.mkdir(parents=True, exist_ok=True)
            with (self.dir / ACTIVITY_FILE).open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(entry, default=str) + "\n")

    def activity_since(self, start: str) -> list[dict[str, Any]]:
        """Entries logged at or after ``start`` (ISO time), newest first. The file is in time
        order, so reading stops at the first older entry."""
        path = self.dir / ACTIVITY_FILE
        if not path.exists():
            return []
        out: list[dict[str, Any]] = []
        for line in reversed(path.read_text(encoding="utf-8").splitlines()):
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            if str(e.get("time") or "") < start:
                break
            out.append(e)
        return out

    def activity(self, limit: int = 500, project: str | None = None) -> list[dict[str, Any]]:
        path = self.dir / ACTIVITY_FILE
        if not path.exists():
            return []
        out: list[dict[str, Any]] = []
        for line in reversed(path.read_text(encoding="utf-8").splitlines()):
            try:
                e = json.loads(line)
            except json.JSONDecodeError:
                continue
            if project and e.get("project") != project:
                continue
            out.append(e)
            if len(out) >= limit:
                break
        return out
