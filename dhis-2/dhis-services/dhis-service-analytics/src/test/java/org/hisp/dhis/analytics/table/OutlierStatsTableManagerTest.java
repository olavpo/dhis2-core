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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.hisp.dhis.db.sql.PostgreSqlBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class OutlierStatsTableManagerTest {
  @Mock private JdbcTemplate jdbcTemplate;

  private OutlierStatsTableManager subject;

  @BeforeEach
  void setUp() {
    subject = new OutlierStatsTableManager(jdbcTemplate, new PostgreSqlBuilder());
  }

  private List<String> generateAndCaptureSql() {
    subject.generateTable();
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate, atLeastOnce()).execute(sql.capture());
    return sql.getAllValues();
  }

  private String find(List<String> statements, String prefix) {
    List<String> matches = statements.stream().filter(s -> s.startsWith(prefix)).toList();
    assertEquals(1, matches.size(), "expected one statement starting with: " + prefix);
    return matches.get(0);
  }

  @Test
  void testMedianPassScopedToNumericDataElements() {
    String sql = find(generateAndCaptureSql(), "insert into analytics_outlier_stats_med ");

    assertTrue(sql.contains("from analytics ax"));
    assertTrue(sql.contains("percentile_cont(0.5) within group (order by ax.value) as median"));
    assertTrue(sql.contains("stddev_pop(ax.value) as std_dev"));
    assertTrue(sql.contains("from analytics_rs_dataelementstructure des"));
    assertTrue(sql.contains("'NUMBER'"), "numeric value types must be included");
    assertFalse(sql.contains("'BOOLEAN'"), "boolean values (stored as 1/0) must be excluded");
    assertFalse(sql.contains("'LONG_TEXT'"), "text value types must be excluded");
    assertTrue(sql.contains("group by ax.dx, ax.ou, ax.co, ax.ao"));
  }

  @Test
  void testMadPassJoinsGroupMedians() {
    String sql = find(generateAndCaptureSql(), "insert into analytics_outlier_stats_temp ");

    assertTrue(
        sql.contains(
            "percentile_cont(0.5) within group (order by abs(ax.value - m.median)) as mad"));
    assertTrue(sql.contains("on ax.dx = m.dx and ax.ou = m.ou and ax.co = m.co and ax.ao = m.ao"));
    assertTrue(sql.contains("group by ax.dx, ax.ou, ax.co, ax.ao) d"));
  }

  @Test
  void testTableIsIndexedAndSwappedAfterPopulate() {
    List<String> statements = generateAndCaptureSql();

    int madInsert = indexOfPrefix(statements, "insert into analytics_outlier_stats_temp ");
    int index = indexOfContains(statements, "create index ");
    int swap = indexOfContains(statements, "rename to \"analytics_outlier_stats\"");

    assertTrue(madInsert >= 0 && index > madInsert && swap > index, statements.toString());
    assertTrue(
        statements.get(index).contains("\"dx\",\"ou\",\"co\",\"ao\""), statements.get(index));
  }

  private int indexOfPrefix(List<String> statements, String prefix) {
    for (int i = 0; i < statements.size(); i++) {
      if (statements.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }

  private int indexOfContains(List<String> statements, String text) {
    for (int i = 0; i < statements.size(); i++) {
      if (statements.get(i).contains(text)) {
        return i;
      }
    }
    return -1;
  }
}
