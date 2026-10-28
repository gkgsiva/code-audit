"""config analyzer: endpoints, queues, credentials (masked)."""

from __future__ import annotations

from legacy_audit.analyzers.config import ConfigAnalyzer
from tests.analyzers.helpers import entities, run

PROPS = """# app config
db.url=jdbc:oracle:thin:scott/tiger@dbhost01:1521:ORCL
db.password=s3cr3t!
pricing.url=http://pricing.acme.local:8080/services/Pricing
ftp.host=ftp.partner.example.net
ftp.port=21
mq.queue.out=ORDERS.OUT
mq.queueManager=QMPROD
app.title=Orders
"""


def test_properties_endpoints_and_credentials() -> None:
    facts = run(ConfigAnalyzer(), "conf/app.properties", PROPS)
    ints = {(i.type, i.target) for i in entities(facts, "integrations")}
    assert ("DB", "oracle://dbhost01:1521/ORCL") in ints
    assert ("SOAP", "http://pricing.acme.local:8080/services/Pricing") in ints
    assert ("FTP", "ftp.partner.example.net:21") in ints
    assert ("MQ", "ORDERS.OUT") in ints
    assert not any(t == "MQ" and target == "QMPROD" for t, target in ints)
    (finding,) = entities(facts, "findings")
    assert finding.rule_id == "config-credential" and finding.line == 3
    dumped = str(facts.dump())
    assert "s3cr3t" not in dumped and "tiger" not in dumped


def test_message_bundles_are_not_credentials() -> None:
    text = "login.password=Password\nerror.password.required=Password is required\n"
    facts = run(ConfigAnalyzer(), "src/ApplicationResources.properties", text)
    assert not entities(facts, "findings")


def test_placeholders_are_not_credentials() -> None:
    facts = run(ConfigAnalyzer(), "app.yaml", "db:\n  password: ${DB_PASSWORD}\n")
    assert not entities(facts, "findings")


def test_owned_inputs_are_left_to_their_analyzers() -> None:
    from tests.analyzers.helpers import record

    assert not ConfigAnalyzer().supports(record("audit-inputs/cft/partners.cfg"))
    assert ConfigAnalyzer().supports(record("conf/app.ini"))
