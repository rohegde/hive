package org.apache.hadoop.hive.ql.cube.parse;
/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
*/


import static org.apache.hadoop.hive.ql.parse.HiveParser.Identifier;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_FUNCTION;

import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.cube.metadata.AbstractCubeTable;
import org.apache.hadoop.hive.ql.cube.metadata.CubeColumn;
import org.apache.hadoop.hive.ql.cube.metadata.Dimension;
import org.apache.hadoop.hive.ql.cube.metadata.SchemaGraph;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.PlanUtils;

public class TimerangeResolver implements ContextRewriter {

  public TimerangeResolver(Configuration conf) {
  }

  @Override
  public void rewriteContext(CubeQueryContext cubeql) throws SemanticException {
    if (cubeql.getCube() == null) {
      return;
    }
    extractTimeRange(cubeql);
    doColLifeValidation(cubeql);
  }

  private void extractTimeRange(CubeQueryContext cubeql) throws SemanticException {
    // get time range -
    // Time range should be direct child of where condition
    // TOK_WHERE.TOK_FUNCTION.Identifier Or, it should be right hand child of
    // AND condition TOK_WHERE.KW_AND.TOK_FUNCTION.Identifier
    if (cubeql.getWhereAST() == null || cubeql.getWhereAST().getChildCount() < 1) {
      throw new SemanticException(ErrorMsg.NO_TIMERANGE_FILTER);
    }
    searchTimeRanges(cubeql.getWhereAST(), cubeql);
  }

  private void searchTimeRanges(ASTNode root, CubeQueryContext cubeql) throws SemanticException {
    if (root == null) {
      return;
    } else if (root.getToken().getType() == TOK_FUNCTION) {
      ASTNode fname = HQLParser.findNodeByPath(root, Identifier);
      if (fname != null && CubeQueryContext.TIME_RANGE_FUNC.equalsIgnoreCase(fname.getText())) {
        processTimeRangeFunction(cubeql, root, null);
      }
    } else {
      for (int i = 0; i < root.getChildCount(); i++) {
        ASTNode child = (ASTNode) root.getChild(i);
        searchTimeRanges(child, cubeql);
      }
    }
  }

  private void processTimeRangeFunction(CubeQueryContext cubeql,
      ASTNode timenode, TimeRange parent) throws SemanticException {
    TimeRange.TimeRangeBuilder builder = TimeRange.getBuilder();
    builder.astNode(timenode);

    String timeDimName = PlanUtils.stripQuotes(timenode.getChild(1).getText());
    if (cubeql.getCube().getTimedDimensions().contains(timeDimName)) {
      builder.partitionColumn(timeDimName);
    } else {
      throw new SemanticException(ErrorMsg.NOT_A_TIMED_DIMENSION, timeDimName);
    }

    String fromDateRaw = PlanUtils.stripQuotes(timenode.getChild(2).getText());
    String toDateRaw = null;
    if (timenode.getChildCount() > 3) {
      ASTNode toDateNode = (ASTNode) timenode.getChild(3);
      if (toDateNode != null) {
        toDateRaw = PlanUtils.stripQuotes(timenode.getChild(3).getText());
      }
    }

    Date now = new Date();
    builder.fromDate(DateUtil.resolveDate(fromDateRaw, now));
    if (StringUtils.isNotBlank(toDateRaw)) {
      builder.toDate(DateUtil.resolveDate(toDateRaw, now));
    } else {
      builder.toDate(now);
    }

    TimeRange range = builder.build();
    range.validate();

    cubeql.getTimeRanges().add(range);
  }

  private void doColLifeValidation(CubeQueryContext cubeql) throws SemanticException {
    for (String col : cubeql.getColumnsQueried(cubeql.getCube().getName())) {
      CubeColumn column = cubeql.getCube().getColumnByName(col);
      for (TimeRange range : cubeql.getTimeRanges()) {
        if (column == null) {
          if (!cubeql.getCube().getTimedDimensions().contains(col)) {
            throw new SemanticException(ErrorMsg.NOT_A_CUBE_COLUMN);
          }
          continue;
        }
        if (isColumnLifeInvalid(column, range)) {
          throw new SemanticException(ErrorMsg.NOT_AVAILABLE_IN_RANGE, col, 
            range.toString(),  (column.getStartTime() == null ? "" :
              " from:" + column.getStartTime()),
             (column.getEndTime() == null ? "" :
              " upto:" + column.getEndTime()));
        }
      }
    }

    // Remove join paths that have columns with invalid life span
    JoinResolver.AutoJoinContext joinContext = cubeql.getAutoJoinCtx();
    if (joinContext == null) {
      return;
    }
    // Get cube columns which are part of join chain
    List<String> joinColumns = joinContext.getJoinPathColumnsOfTable((AbstractCubeTable) cubeql.getCube());
    if (joinColumns == null) {
      return;
    }

    // Loop over all cube columns part of join paths
    for (String col : joinColumns) {
      CubeColumn column = cubeql.getCube().getColumnByName(col);
      for (TimeRange range : cubeql.getTimeRanges()) {
        if (isColumnLifeInvalid(column, range)) {
          // Remove join paths containing this column
          Map<Dimension, List<SchemaGraph.JoinPath>> allPaths = joinContext.getAllPaths();

          for (Dimension dimension : allPaths.keySet()) {
            List<SchemaGraph.JoinPath> joinPaths = allPaths.get(dimension);
            Iterator<SchemaGraph.JoinPath> joinPathIterator = joinPaths.iterator();

            while (joinPathIterator.hasNext()) {
              SchemaGraph.JoinPath path = joinPathIterator.next();
              if (path.containsColumnOfTable(col, (AbstractCubeTable) cubeql.getCube())) {
                joinPathIterator.remove();
                if (joinPaths.isEmpty()) {
                  // This dimension doesn't have any paths left
                  throw new SemanticException(ErrorMsg.NO_JOIN_PATH,
                    "No valid join path available for dimension " + dimension
                      + " which would satisfy time range " + range.getFromDate() + "-" + range.getToDate());
                }
              }
            } // End loop to remove path

          } // End loop for all paths
        }
      } // End time range loop
    } // End column loop

  }

  private boolean isColumnLifeInvalid(CubeColumn column, TimeRange range) {
    return (column.getStartTime() != null &&
      column.getStartTime().after(range.getFromDate())) ||
      (column.getEndTime() != null &&
        column.getEndTime().before(range.getToDate()));
  }
}
