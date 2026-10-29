"""Login. One small provider interface so the dummy login can be swapped for Okta (OpenID
Connect) with settings only.

The session is a signed cookie (HMAC-SHA256 over the user name and an expiry time); nothing is
kept server-side, so a restart keeps people logged in when ``LEGACY_AUDIT_SESSION_SECRET`` is set.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from dataclasses import dataclass
from typing import Protocol

from legacy_audit.hosted.settings import HostSettings, SettingsError

COOKIE = "la_session"


class AuthError(Exception):
    pass


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


@dataclass
class Sessions:
    secret: str
    hours: float

    def _sign(self, payload: str) -> str:
        return _b64(hmac.new(self.secret.encode(), payload.encode(), hashlib.sha256).digest())

    def issue(self, user: str) -> str:
        payload = _b64(
            json.dumps({"u": user, "exp": int(time.time() + self.hours * 3600)}).encode()
        )
        return f"{payload}.{self._sign(payload)}"

    def read(self, token: str | None) -> str | None:
        """The user name in a valid, unexpired cookie, else None."""
        if not token or token.count(".") != 1:
            return None
        payload, sig = token.split(".")
        if not hmac.compare_digest(sig, self._sign(payload)):
            return None
        try:
            data = json.loads(_unb64(payload))
        except (ValueError, json.JSONDecodeError):
            return None
        if not isinstance(data, dict) or data.get("exp", 0) < time.time():
            return None
        user = data.get("u")
        return user if isinstance(user, str) else None


class Provider(Protocol):
    """What a login method does. ``login_form`` True: the site shows its own name box and calls
    ``check``; False: the site redirects to ``start_url`` and the provider calls back."""

    name: str
    label: str
    login_form: bool
    banner: str | None

    def check(self, user_name: str) -> str: ...

    def start_url(self, next_url: str) -> str: ...


class DummyProvider:
    """Test login: a user name, no password. The site says so on every page."""

    name: str = "dummy"
    label: str = "Test login"
    login_form: bool = True
    banner: str | None = (
        "Test login: anyone can log in with any name. Not for production; use Okta."
    )

    def check(self, user_name: str) -> str:
        name = user_name.strip()
        if not name:
            raise AuthError("Type a user name")
        return name

    def start_url(self, next_url: str) -> str:
        raise AuthError("The test login has no external sign-in page")


class OktaProvider:
    """Placeholder for the Okta step: OpenID Connect authorization-code flow against
    ``OKTA_ISSUER`` with ``OKTA_CLIENT_ID``/``OKTA_CLIENT_SECRET``, callback at
    ``<public URL>/auth/callback``, Okta groups mapped to roles. Not built yet."""

    name: str = "okta"
    label: str = "Okta"
    login_form: bool = False
    banner: str | None = None

    def __init__(self, host: HostSettings) -> None:
        raise SettingsError(
            "LEGACY_AUDIT_AUTH=okta is not available yet; use dummy until the Okta step lands"
        )

    def check(self, user_name: str) -> str:  # pragma: no cover - not built
        raise AuthError("Okta")

    def start_url(self, next_url: str) -> str:  # pragma: no cover - not built
        raise AuthError("Okta")


def provider_for(host: HostSettings) -> Provider:
    if host.auth == "okta":
        return OktaProvider(host)
    return DummyProvider()
