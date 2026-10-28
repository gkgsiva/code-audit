"""Model providers for ``legacy-audit enrich``: one small HTTP client per API family.

Every provider takes a system prompt and a user prompt and returns the model's text. They use
the standard library only (``urllib``), honour ``HTTPS_PROXY`` like any Python program, and never
log or store the API key: it is read from the environment variable named in the config at call
time. Families:

- ``openai``: any OpenAI-compatible ``/chat/completions`` endpoint (OpenAI, Gemini, Mistral,
  Groq, LM Studio, vLLM, LiteLLM, a corporate gateway...), chosen by ``base_url``;
- ``ollama``: the same API on a local Ollama (``http://localhost:11434/v1``), no key;
- ``azure-openai``: an Azure OpenAI deployment (``model`` is the deployment name);
- ``anthropic``: the Anthropic Messages API.

``copilot`` is not a client: Copilot runs inside VS Code, where the analyst picks the model.
"""

from __future__ import annotations

import json
import os
import ssl
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass, replace
from typing import Any

from legacy_audit.config import AiConfig

ENV_PREFIX = "LEGACY_AUDIT_AI_"
DEFAULT_URL = {
    "openai": "https://api.openai.com/v1",
    "ollama": "http://localhost:11434/v1",
    "anthropic": "https://api.anthropic.com",
}
DEFAULT_KEY_ENV = {
    "openai": "OPENAI_API_KEY",
    "anthropic": "ANTHROPIC_API_KEY",
    "azure-openai": "AZURE_OPENAI_API_KEY",
}
ANTHROPIC_VERSION = "2023-06-01"


class AiError(Exception):
    """A provider could not be called or answered with an error (message is safe to print)."""


@dataclass(frozen=True)
class Settings:
    """The config's ``ai`` section after environment overrides."""

    provider: str
    model: str | None
    base_url: str | None
    api_key_env: str | None
    api_version: str
    temperature: float | None
    max_output_tokens: int
    json_mode: bool
    context_chars: int
    timeout_seconds: int
    repair_rounds: int
    ca_bundle: str | None

    @property
    def label(self) -> str:
        return f"{self.provider}:{self.model}" if self.model else self.provider

    @property
    def url(self) -> str:
        return (self.base_url or DEFAULT_URL.get(self.provider, "")).rstrip("/")

    @property
    def key_env(self) -> str | None:
        return self.api_key_env or DEFAULT_KEY_ENV.get(self.provider)


def settings(cfg: AiConfig, **overrides: str | None) -> Settings:
    """Config values, then ``LEGACY_AUDIT_AI_PROVIDER`` / ``_MODEL`` / ``_BASE_URL`` /
    ``_API_KEY_ENV`` from the environment, then command-line overrides (when not None)."""
    s = Settings(
        provider=cfg.provider,
        model=cfg.model,
        base_url=cfg.base_url,
        api_key_env=cfg.api_key_env,
        api_version=cfg.api_version,
        temperature=cfg.temperature,
        max_output_tokens=cfg.max_output_tokens,
        json_mode=cfg.json_mode,
        context_chars=cfg.context_chars,
        timeout_seconds=cfg.timeout_seconds,
        repair_rounds=cfg.repair_rounds,
        ca_bundle=cfg.ca_bundle,
    )
    changes: dict[str, str] = {}
    for name in ("provider", "model", "base_url", "api_key_env"):
        value = overrides.get(name) or os.environ.get(ENV_PREFIX + name.upper())
        if value:
            changes[name] = value
    s = replace(
        s,
        provider=changes.get("provider", s.provider),
        model=changes.get("model", s.model),
        base_url=changes.get("base_url", s.base_url),
        api_key_env=changes.get("api_key_env", s.api_key_env),
    )
    allowed = ("copilot", "openai", "anthropic", "azure-openai", "ollama")
    if s.provider not in allowed:
        raise AiError(f"Unknown AI provider {s.provider!r}; use one of {', '.join(allowed)}.")
    return s


Transport = Callable[[str, dict[str, str], dict[str, Any], Settings], dict[str, Any]]


def _post(url: str, headers: dict[str, str], body: dict[str, Any], s: Settings) -> dict[str, Any]:
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers},
        method="POST",
    )
    ctx = ssl.create_default_context(cafile=s.ca_bundle) if s.ca_bundle else None
    try:
        with urllib.request.urlopen(req, timeout=s.timeout_seconds, context=ctx) as resp:
            data: dict[str, Any] = json.loads(resp.read().decode("utf-8"))
            return data
    except urllib.error.HTTPError as err:
        detail = err.read().decode("utf-8", errors="replace")[:600]
        raise AiError(f"HTTP {err.code} from {_host(url)}: {detail}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as err:
        reason = getattr(err, "reason", err)
        raise AiError(f"Cannot reach {_host(url)}: {reason}") from None
    except ValueError:
        raise AiError(f"{_host(url)} did not answer with JSON") from None


def _host(url: str) -> str:
    return url.split("?")[0]


class Client:
    """Calls one provider. ``transport`` is swapped in tests."""

    def __init__(self, s: Settings, transport: Transport | None = None) -> None:
        if s.provider == "copilot":
            raise AiError(
                "The AI provider is 'copilot': run /audit-enrich in VS Code Copilot Chat and pick "
                "the model there, or set ai.provider (openai, anthropic, azure-openai, ollama) "
                "in audit.yaml or LEGACY_AUDIT_AI_PROVIDER to run it here."
            )
        if not s.model:
            raise AiError(
                f"No model set for provider {s.provider}: set ai.model in audit.yaml, "
                "LEGACY_AUDIT_AI_MODEL, or --model."
            )
        if s.provider == "azure-openai" and not s.base_url:
            raise AiError("azure-openai needs ai.base_url (https://<resource>.openai.azure.com).")
        self.s = s
        self.transport = transport or _post
        self._drop: set[str] = set()  # parameters an endpoint rejected

    def _key(self) -> str | None:
        env = self.s.key_env
        if self.s.provider == "ollama" and not self.s.api_key_env:
            return None
        key = os.environ.get(env) if env else None
        if not key and self.s.provider != "openai":
            raise AiError(f"Set the environment variable {env} to your API key.")
        if not key and self.s.base_url is None:
            raise AiError(f"Set the environment variable {env} to your API key.")
        return key

    def complete(self, system: str, user: str) -> str:
        if self.s.provider == "anthropic":
            return self._anthropic(system, user)
        return self._chat(system, user)

    def _chat(self, system: str, user: str) -> str:
        key = self._key()
        if self.s.provider == "azure-openai":
            url = (
                f"{self.s.url}/openai/deployments/{self.s.model}/chat/completions"
                f"?api-version={self.s.api_version}"
            )
            headers = {"api-key": key or ""}
        else:
            url = f"{self.s.url}/chat/completions"
            headers = {"Authorization": f"Bearer {key}"} if key else {}
        for _ in range(4):
            body: dict[str, Any] = {
                "messages": [
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ]
            }
            if self.s.provider != "azure-openai":
                body["model"] = self.s.model
            tokens = "max_completion_tokens" if "max_tokens" in self._drop else "max_tokens"
            body[tokens] = self.s.max_output_tokens
            if self.s.temperature is not None and "temperature" not in self._drop:
                body["temperature"] = self.s.temperature
            if self.s.json_mode and "response_format" not in self._drop:
                body["response_format"] = {"type": "json_object"}
            try:
                data = self.transport(url, headers, body, self.s)
            except AiError as err:
                # some models reject a parameter (newer OpenAI models: max_tokens, temperature;
                # some local servers: response_format); drop it and try again
                text = str(err)
                bad = next(
                    (p for p in ("max_tokens", "temperature", "response_format")
                     if p in text and p not in self._drop and "HTTP 400" in text),
                    None,
                )  # fmt: skip
                if bad is None:
                    raise
                self._drop.add(bad)
                continue
            try:
                return str(data["choices"][0]["message"]["content"] or "")
            except (KeyError, IndexError, TypeError):
                raise AiError(f"Unexpected answer from {self.s.label}: {str(data)[:300]}") from None
        raise AiError(f"{self.s.label} kept rejecting the request parameters.")

    def _anthropic(self, system: str, user: str) -> str:
        key = self._key()
        body: dict[str, Any] = {
            "model": self.s.model,
            "system": system,
            "messages": [{"role": "user", "content": user}],
            "max_tokens": self.s.max_output_tokens,
        }
        if self.s.temperature is not None:
            body["temperature"] = self.s.temperature
        headers = {"x-api-key": key or "", "anthropic-version": ANTHROPIC_VERSION}
        data = self.transport(f"{self.s.url}/v1/messages", headers, body, self.s)
        try:
            return "".join(b.get("text", "") for b in data["content"] if b.get("type") == "text")
        except (KeyError, TypeError):
            raise AiError(f"Unexpected answer from {self.s.label}: {str(data)[:300]}") from None


def extract_json(text: str) -> Any:
    """The JSON object in a model's answer (tolerates ```json fences and text around it)."""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("\n", 1)[1] if "\n" in t else ""
        t = t.rsplit("```", 1)[0]
    try:
        return json.loads(t)
    except ValueError:
        pass
    start, end = t.find("{"), t.rfind("}")
    if start < 0 or end <= start:
        raise AiError("The model did not answer with a JSON object.")
    try:
        return json.loads(t[start : end + 1])
    except ValueError as err:
        raise AiError(f"The model's JSON is not valid: {err}") from None
