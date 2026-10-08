/*
 * Copyright (c) 2004-2022, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors 
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.analytics.table;

import static org.hisp.dhis.db.model.DataType.CHARACTER_11;
import static org.hisp.dhis.db.model.DataType.DOUBLE;
import static org.hisp.dhis.db.model.DataType.INTEGER;
import static org.hisp.dhis.db.model.constraint.Nullable.NOT_NULL;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.db.model.Column;
import org.hisp.dhis.db.model.Index;
import org.hisp.dhis.db.model.Logged;
import org.hisp.dhis.db.model.Table;
import org.hisp.dhis.db.sql.SqlBuilder;
import org.hisp.dhis.system.util.Clock;
import org.hisp.dhis.util.ObjectUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Manages the outlier statistics table, which holds the count, mean, population standard deviation,
 * median and median absolute deviation (MAD) of numeric values per data element, org unit, category
 * option combo and attribute option combo. The table is built from the finished analytics table in
 * the analytics database, after the analytics table has been swapped in, and is joined to the
 * analytics table by the outlier detection query.
 *
 * <p>The build runs in two passes over the numeric analytics rows: the first computes count, mean,
 * standard deviation and median per group, the second joins the rows to their group median to
 * compute the MAD. The result is indexed and swapped in.
 */
@Slf4j
@Component
public class OutlierStatsTableManager {
  public static final String TABLE_NAME = "analytics_outlier_stats";

  private static final String MEDIAN_TABLE_NAME = TABLE_NAME + "_med";

  private static final String TEMP_TABLE_NAME = TABLE_NAME + "_temp";

  private static final List<String> GROUP_COLS = List.of("dx", "ou", "co", "ao");

  private final JdbcTemplate jdbcTemplate;

  private final SqlBuilder sqlBuilder;

  public OutlierStatsTableManager(
      @Qualifier("analyticsJdbcTemplate") JdbcTemplate jdbcTemplate, SqlBuilder sqlBuilder) {
    this.jdbcTemplate = jdbcTemplate;
    this.sqlBuilder = sqlBuilder;
  }

  /** Builds the outlier statistics table from the analytics table and swaps it in. */
  public void generateTable() {
    Clock clock = new Clock(log).startClock();

    Table medianTable = getTable(MEDIAN_TABLE_NAME, List.of());
    Table tempTable = getTable(TEMP_TABLE_NAME, List.of(new Column("mad", DOUBLE)));

    execute(sqlBuilder.dropTableIfExists(medianTable));
    execute(sqlBuilder.dropTableIfExists(tempTable));
    execute(sqlBuilder.createTable(medianTable));
    execute(sqlBuilder.createTable(tempTable));

    execute(getMedianInsertSql());
    clock.logTime("Populated outlier statistics pass 1 (count, mean, std dev, median)");

    if (sqlBuilder.supportsAnalyze()) {
      execute(sqlBuilder.analyzeTable(medianTable));
    }

    execute(getMadInsertSql());
    clock.logTime("Populated outlier statistics pass 2 (MAD)");

    execute(sqlBuilder.dropTableIfExists(medianTable));

    if (sqlBuilder.requiresIndexesForAnalytics()) {
      execute(
          sqlBuilder.createIndex(
              Index.builder()
                  // Suffix keeps the name unique while the previous table still exists
                  .name("in_" + TABLE_NAME + "_" + System.currentTimeMillis())
                  .tableName(TEMP_TABLE_NAME)
                  .columns(GROUP_COLS)
                  .build()));
    }

    if (sqlBuilder.supportsAnalyze()) {
      execute(sqlBuilder.analyzeTable(tempTable));
    }

    execute(sqlBuilder.swapTable(tempTable, TABLE_NAME));
    clock.logTime("Outlier statistics table generated");
  }

  /** Pass 1: count, mean, population standard deviation and median per group. */
  private String getMedianInsertSql() {
    return TextUtils.replace(
        """
        insert into ${medianTable} (dx, ou, co, ao, n, mean, std_dev, median) \
        select ax.dx, ax.ou, ax.co, ax.ao, \
        count(*) as n, \
        avg(ax.value) as mean, \
        stddev_pop(ax.value) as std_dev, \
        percentile_cont(0.5) within group (order by ax.value) as median \
        from analytics ax \
        where ax.value is not null \
        and ${numericClause} \
        group by ax.dx, ax.ou, ax.co, ax.ao;""",
        Map.of("medianTable", MEDIAN_TABLE_NAME, "numericClause", getNumericDataElementClause()));
  }

  /**
   * Pass 2: median absolute deviation per group, joined to the pass 1 results. The MAD is grouped
   * on the four key columns only and joined back to the pass 1 row afterwards; grouping on the pass
   * 1 statistics columns as well is two orders of magnitude slower on PostgreSQL.
   */
  private String getMadInsertSql() {
    return TextUtils.replace(
        """
        insert into ${tempTable} (dx, ou, co, ao, n, mean, std_dev, median, mad) \
        select s.dx, s.ou, s.co, s.ao, s.n, s.mean, s.std_dev, s.median, d.mad \
        from ${medianTable} s \
        inner join (\
        select ax.dx, ax.ou, ax.co, ax.ao, \
        percentile_cont(0.5) within group (order by abs(ax.value - m.median)) as mad \
        from analytics ax \
        inner join ${medianTable} m \
        on ax.dx = m.dx and ax.ou = m.ou and ax.co = m.co and ax.ao = m.ao \
        where ax.value is not null \
        group by ax.dx, ax.ou, ax.co, ax.ao) d \
        on s.dx = d.dx and s.ou = d.ou and s.co = d.co and s.ao = d.ao;""",
        Map.of("tempTable", TEMP_TABLE_NAME, "medianTable", MEDIAN_TABLE_NAME));
  }

  /**
   * Restricts rows to numeric data elements. Boolean values are stored as 1/0 in the value column
   * and must not be included in the statistics.
   */
  private String getNumericDataElementClause() {
    String valueTypes =
        sqlBuilder.singleQuotedCommaDelimited(ObjectUtils.asStringList(ValueType.NUMERIC_TYPES));

    return TextUtils.replace(
        """
        ax.dx in (select des.dataelementuid \
        from analytics_rs_dataelementstructure des \
        where des.valuetype in (${valueTypes}))""",
        Map.of("valueTypes", valueTypes));
  }

  private Table getTable(String name, List<Column> extraColumns) {
    List<Column> columns =
        new ArrayList<>(
            List.of(
                new Column("dx", CHARACTER_11, NOT_NULL),
                new Column("ou", CHARACTER_11, NOT_NULL),
                new Column("co", CHARACTER_11, NOT_NULL),
                new Column("ao", CHARACTER_11, NOT_NULL),
                new Column("n", INTEGER),
                new Column("mean", DOUBLE),
                new Column("std_dev", DOUBLE),
                new Column("median", DOUBLE)));
    columns.addAll(extraColumns);

    return new Table(name, columns, List.of(), Logged.UNLOGGED);
  }

  private void execute(String sql) {
    log.debug("Outlier statistics SQL: '{}'", sql);
    jdbcTemplate.execute(sql);
  }
}
