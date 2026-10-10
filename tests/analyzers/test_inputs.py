"""Analyst-supplied inputs: Autosys JIL, MQSC definitions and CFT configuration exports."""

from __future__ import annotations

from legacy_audit.analyzers.autosys import AutosysAnalyzer, parse_jil, predecessors
from legacy_audit.analyzers.cft import CftAnalyzer
from legacy_audit.analyzers.mq import MqAnalyzer
from tests.analyzers.helpers import entities, run

JIL = """/* billing */
insert_job: BIL_BOX   job_type: BOX
date_conditions: 1
days_of_week: mo,tu,we,th,fr
start_times: "02:00"

insert_job: BIL_WAIT   job_type: FW
box_name: BIL_BOX
watch_file: /data/in/usage_*.dat
machine: batchsrv01

insert_job: BIL_RUN   job_type: CMD
box_name: BIL_BOX
command: /opt/billing/bin/run_invoice.sh $DATE
machine: batchsrv01
condition: s(BIL_WAIT) & n(BIL_OTHER^PRD)
description: "Invoice run: nightly"

update_job: BIL_RUN
condition: s(BIL_WAIT)
"""


def test_jil_jobs_boxes_watchers_and_updates() -> None:
    facts = run(AutosysAnalyzer(), "audit-inputs/autosys/bil.jil", JIL)
    jobs = {j.name: j for j in entities(facts, "batch_jobs")}
    assert {n: j.kind for n, j in jobs.items()} == {
        "BIL_BOX": "box",
        "BIL_WAIT": "file-watcher",
        "BIL_RUN": "job",
    }
    assert (
        jobs["BIL_BOX"].schedule
        == "date_conditions: 1; days_of_week: mo,tu,we,th,fr; start_times: 02:00"
    )
    run_job = jobs["BIL_RUN"]
    assert (run_job.box, run_job.machine) == ("BIL_BOX", "batchsrv01")
    # update_job replaces the condition.
    assert (run_job.condition, run_job.predecessors) == ("s(BIL_WAIT)", ["BIL_WAIT"])
    (watch,) = entities(facts, "integrations")
    assert (watch.type, watch.target, watch.direction) == (
        "FILE",
        "/data/in/usage_*.dat",
        "inbound",
    )
    refs = {(r.kind, r.source_label, r.target) for r in facts.refs}
    assert refs == {
        ("contains", "BIL_WAIT", "BIL_BOX"),
        ("contains", "BIL_RUN", "BIL_BOX"),
        ("depends-on", "BIL_RUN", "BIL_WAIT"),
        ("invokes", "BIL_RUN", "run_invoice.sh"),
    }


def test_jil_values_with_colons_and_conditions() -> None:
    jobs, unparsed = parse_jil(
        'insert_job: J1 job_type: CMD\ndescription: "rerun command: x"\ncommand: x.sh\n'
    )
    ((line, verb, name, attrs),) = jobs
    assert (line, verb, name, unparsed) == (1, "insert_job", "J1", [])
    assert attrs["description"] == ("rerun command: x", 2)
    assert attrs["command"] == ("x.sh", 3)
    assert predecessors("s(A) & (f(B^PRD) | d(C)) & v(X) = 1 & e(D, 2)") == ["A", "B", "C", "D"]


MQSC = """* billing queues
DEFINE QLOCAL('BILL.IN') +
       DESCR('Usage in') +
       MAXDEPTH(5000) REPLACE
DEFINE QREMOTE('BILL.OUT') RNAME('LEDGER.IN') RQMNAME('QMLEDG') XMITQ('QMLEDG.XMIT')
DEFINE QLOCAL('QMLEDG.XMIT') USAGE(XMITQ)
DEFINE CHANNEL('QMBILL.QMLEDG') CHLTYPE(SDR) CONNAME('ledger.acme.local(1414)') XMITQ('QMLEDG.XMIT')
"""


def test_mqsc_queues_and_channels() -> None:
    facts = run(MqAnalyzer(), "audit-inputs/mq/qm.mqsc", MQSC)
    ints = {(i.type, i.target): i for i in entities(facts, "integrations")}
    # Transmission queues are plumbing, not integrations (still findable as symbols).
    assert set(ints) == {("MQ", "BILL.IN"), ("MQ", "BILL.OUT"), ("MQ-CHANNEL", "QMBILL.QMLEDG")}
    assert ints["MQ", "BILL.IN"].direction == "defined"
    assert ints["MQ", "BILL.IN"].details["descr"] == "Usage in"
    assert ints["MQ", "BILL.OUT"].details["rqmname"] == "QMLEDG"
    assert ints["MQ-CHANNEL", "QMBILL.QMLEDG"].details["conname"] == "ledger.acme.local(1414)"
    assert {s.name for s in facts.symbols} == {"BILL.IN", "BILL.OUT", "QMLEDG.XMIT"}


CFT = """CFTPART ID=BANK1, NSPART=BANKONE, PROT=PESITANY, SAP=1761
CFTTCP  ID=BANK1, HOST=cft.bank1.example
CFTSEND ID=SETTLE, FNAME=/data/out/settle.dat
CFTRECV ID=ACK, FNAME=/data/in/ack.dat
"""


def test_cft_export_flows_and_partners() -> None:
    facts = run(CftAnalyzer(), "audit-inputs/cft/cft.cfg", CFT)
    flows = {
        (i.target, i.direction, i.details.get("file")) for i in entities(facts, "integrations")
    }
    assert flows == {
        ("*/SETTLE", "outbound", "/data/out/settle.dat"),
        ("*/ACK", "inbound", "/data/in/ack.dat"),
    }
    (partner,) = facts.symbols
    assert (partner.kind, partner.name) == ("cft-partner", "BANK1")
    assert partner.data == {"host": "cft.bank1.example", "nspart": "BANKONE"}
