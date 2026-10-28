"""The consolidated batch list (report/batches.py) and integration purposes."""

from __future__ import annotations

import pytest

from legacy_audit.analyzers.integrations import describe, integration
from legacy_audit.report.batches import _hint_in_text


@pytest.mark.parametrize(
    ("text", "lang", "expected"),
    [
        ('@Scheduled(cron = "0 30 2 * * MON-FRI")\npublic void run() {}', "java",
         "Runs every weekday (Mon to Fri) at 02:30."),
        ("@Scheduled(fixedRate = 60000)", "java", "Runs every 60 s"),
        ('<property name="cronExpression" value="0 0 6 1 * ?"/>', "xml",
         "Runs on day 1 of every month at 06:00."),
        ("billing.cron=0 15 1 * * ?", "properties", "Runs every day at 01:15."),
        ("#!/bin/ksh\n# Nightly load of the usage files\nset -e", "shell", "Nightly, says"),
        ("-- runs at 2am after the extract\nselect 1 from dual;", "sql", "Runs at 2am, says"),
        ("echo nightly", "shell", None),  # code, not a comment
    ],
)  # fmt: skip
def test_schedule_hints_from_code(text: str, lang: str, expected: str | None) -> None:
    hit = _hint_in_text(text, lang)
    if expected is None:
        assert hit is None
    else:
        assert hit is not None and hit[0].startswith(expected), hit


@pytest.mark.parametrize(
    ("type", "target", "purpose", "start"),
    [
        ("HTTP", "https://acme.okta.com/oauth2/default", "login / authentication",
         "Login / authentication with Okta (OAuth 2.0 / OpenID Connect) at acme.okta.com"),
        ("HTTP", "https://login.microsoftonline.com/tenant/oauth2/v2.0/token",
         "login / authentication", "Login / authentication with Microsoft Entra ID"),
        ("HTTP", "https://sso.acme.local/auth/realms/acme", "login / authentication",
         "Login / authentication with Identity provider"),
        ("HTTP", "https://pricing.acme.local/api/v2/prices", "HTTP API",
         "Calls the HTTPS endpoint /api/v2/prices on pricing.acme.local;"),
        ("SOAP", "http://pricing.acme.local/axis/services/PricingService", "web service",
         "Calls the PricingService web service on pricing.acme.local (HTTP);"),
        ("FTP", "ftp://files.acme.local", "file transfer", "Sends files to ftp://files.acme.local"),
        ("SMTP", "smtp.acme.local", "email", "Sends email through smtp.acme.local"),
    ],
)  # fmt: skip
def test_integration_purpose_and_description(
    type: str, target: str, purpose: str, start: str
) -> None:
    it = integration(type=type, target=target, direction="outbound", file="a.java", line=1)
    got_purpose, text = describe(it)
    assert got_purpose == purpose
    assert text.startswith(start), text
    assert text.endswith("1 place in the code.")


def test_okta_named_only_by_its_configuration_key() -> None:
    it = integration(
        type="HTTP", target="https://login.acme.com/oauth2/default", direction="outbound",
        file="app.properties", line=3, details={"key": "okta.oauth2.issuer"},
    )  # fmt: skip
    assert describe(it)[1].startswith("Login / authentication with")
