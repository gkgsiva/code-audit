Cross-check the enrichment against the facts and list contradictions. Do not change any file
unless the user asks.

1. Run `legacy-audit validate` and list its errors and warnings.
2. For each stage file in `audit-output/latest/enrichment/`, check beyond the validator:
   - a figure whose label does not match what its `metric` measures;
   - a flow, service mapping or risk that cites evidence which does not support the claim (open
     the cited lines);
   - components, services, integrations or batch jobs in the facts that no stage mentions;
   - plan waves that leave out a component, or effort that does not add up to `effort.json`;
   - assumptions that a provided input already answers, or coverage gaps not listed as
     assumptions or risks.
3. Answer with a table: stage, item, problem, evidence, suggested fix. End with the stages you
   recommend re-running.
