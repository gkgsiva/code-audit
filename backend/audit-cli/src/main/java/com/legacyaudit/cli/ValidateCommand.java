package com.legacyaudit.cli;

import com.legacyaudit.ai.enrich.Validator;
import com.legacyaudit.engine.enrich.Stages;
import com.legacyaudit.engine.enrich.Status;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/** {@code legacy-audit validate}: check the enrichment against the scan and mark stale stages. */
@Command(name = "validate", description = "Check the Copilot enrichment against the scan; mark stale stages in status.json.")
final class ValidateCommand implements Callable<Integer> {

    @Option(names = {"-s", "--stage"}, description = "Only these stages (repeatable).")
    List<String> stages;

    @Option(names = "--restamp", description = "Record the current facts as the stages' inputs (after re-running them).")
    boolean restamp;

    @Mixin
    RunOptions opts;

    @Override
    public Integer call() {
        Path runDir;
        try {
            runDir = opts.runDir();
        } catch (RunOptions.Failure e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
        Validator.Report rep = Validator.validate(runDir, stages, restamp);
        rep.messages.forEach(m -> (m.level().equals("error") ? System.err : System.out).println(m));
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"#", "stage", "file", "state"});
        Map<?, ?> all = (Map<?, ?>) rep.status.get("stages");
        for (Stages.Stage st : Status.activeStages(runDir)) {
            Map<?, ?> e = (Map<?, ?>) all.get(st.name());
            String state;
            if (!Boolean.TRUE.equals(e.get("present"))) {
                state = "missing";
            } else if (rep.messages.stream().anyMatch(m -> m.stage().equals(st.name()) && m.level().equals("error"))) {
                state = "invalid";
            } else if (Boolean.TRUE.equals(e.get("stale"))) {
                List<String> why = new ArrayList<>();
                ((List<?>) e.get("changed")).forEach(c -> why.add(String.valueOf(c)));
                ((List<?>) e.get("stale_upstream")).forEach(u -> why.add("stage:" + u));
                state = "stale (" + String.join(", ", why) + ")";
            } else {
                state = "valid";
            }
            rows.add(new String[] {String.valueOf(st.number()), st.name(), st.file(), state});
        }
        Table.print(rows);
        System.out.println(rep.checked.size() + " checked, " + rep.errors().size() + " error(s), " + rep.warnings().size()
                + " warning(s); status -> " + runDir.resolve("enrichment").resolve("status.json"));
        return rep.errors().isEmpty() ? 0 : 1;
    }
}
