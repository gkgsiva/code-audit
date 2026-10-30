package com.legacyaudit.cli;

import java.util.List;

/** A plain text table: the first row is the header. */
final class Table {

    private Table() {}

    static void print(List<String[]> rows) {
        int cols = rows.get(0).length;
        int[] width = new int[cols];
        for (String[] r : rows) {
            for (int i = 0; i < cols; i++) {
                width[i] = Math.max(width[i], r[i].length());
            }
        }
        for (int n = 0; n < rows.size(); n++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                sb.append(i > 0 ? "  " : "").append(String.format("%-" + width[i] + "s", rows.get(n)[i]));
            }
            System.out.println(sb.toString().stripTrailing());
            if (n == 0) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < cols; i++) {
                    line.append(i > 0 ? "  " : "").append("-".repeat(width[i]));
                }
                System.out.println(line);
            }
        }
    }
}
