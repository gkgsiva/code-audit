package com.legacyaudit.reports;

import java.util.LinkedHashMap;
import java.util.Map;

/** Light-theme values of the colour tokens, for the summary charts and the management deck. */
public final class Palette {

    private Palette() {}

    public static final Map<String, String> PALETTE = new LinkedHashMap<>();

    static {
        PALETTE.put("accent", "2A78D6");
        PALETTE.put("good", "0CA30C");
        PALETTE.put("warning", "FAB219");
        PALETTE.put("serious", "EC835A");
        PALETTE.put("critical", "D03B3B");
        PALETTE.put("info", "898781");
        PALETTE.put("text", "0B0B0B");
        PALETTE.put("muted", "6D6B66");
        PALETTE.put("surface", "FCFCFB");
        PALETTE.put("grid", "E1E0D9");
        PALETTE.put("series-1", "2A78D6");
        PALETTE.put("series-2", "EB6834");
        PALETTE.put("series-3", "1BAF7A");
        PALETTE.put("series-4", "EDA100");
        PALETTE.put("series-5", "E87BA4");
        PALETTE.put("series-6", "008300");
        PALETTE.put("series-7", "6250D6");
        PALETTE.put("series-8", "E34948");
        // heat-map steps (sequential blue) and flow-node fills / borders
        PALETTE.put("seq-0", "F3F2EE");
        PALETTE.put("seq-1", "CDE2FB");
        PALETTE.put("seq-2", "9EC5F4");
        PALETTE.put("seq-3", "6DA7EC");
        PALETTE.put("seq-4", "3987E5");
        PALETTE.put("seq-5", "256ABF");
        PALETTE.put("seq-6", "184F95");
        PALETTE.put("risk-0", "D3EFD3");
        PALETTE.put("risk-1", "FDE8B5");
        PALETTE.put("risk-2", "F9D2C1");
        PALETTE.put("risk-3", "F2BDBD");
        PALETTE.put("kind-screen", "DBE9FB/2A78D6");
        PALETTE.put("kind-service", "ECE3FB/6250D6");
        PALETTE.put("kind-table", "DCF3DC/0CA30C");
        PALETTE.put("kind-system", "FDE7D9/EC835A");
        PALETTE.put("kind-trigger", "FFF1CC/D69A00");
        PALETTE.put("kind-job", "D8F1F0/13867F");
        PALETTE.put("kind-muted", "F0EFEC/898781");
    }
}
