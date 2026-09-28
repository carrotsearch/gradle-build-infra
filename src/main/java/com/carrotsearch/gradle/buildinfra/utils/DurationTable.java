package com.carrotsearch.gradle.buildinfra.utils;

import java.io.StringWriter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a two-column table of durations and labels, with the duration column right-aligned to a
 * common width so that tables from different reports line up.
 */
public final class DurationTable {
  /** Width of the duration column (fits typical compact and normal durations). */
  private static final String DURATION_COLUMN_FORMAT = "%10s";

  /** A single row. */
  public record Row(long durationMillis, String label) {}

  private DurationTable() {}

  public static String format(UnitFormatter durationFormat, List<Row> rows) {
    var tabular =
        TabularOutput.to(new StringWriter())
            .columnSeparator("  ")
            .noAutoFlush()
            .outputHeaders(false)
            .addColumn("duration", spec -> spec.alignRight().format(DURATION_COLUMN_FORMAT))
            .addColumn("label", TabularOutput.ColumnSpec::alignLeft)
            .build();

    for (Row row : rows) {
      tabular.append(durationFormat.format(row.durationMillis()), row.label()).nextRow();
    }

    return tabular
        .flush()
        .getWriter()
        .toString()
        .lines()
        .map(String::stripTrailing)
        .collect(Collectors.joining("\n"));
  }
}
