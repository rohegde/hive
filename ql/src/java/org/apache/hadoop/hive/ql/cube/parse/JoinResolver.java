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
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_FULLOUTERJOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_JOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_LEFTOUTERJOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_LEFTSEMIJOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_RIGHTOUTERJOIN;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_SUBQUERY;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_TABNAME;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_TABREF;
import static org.apache.hadoop.hive.ql.parse.HiveParser.TOK_UNIQUEJOIN;

import java.util.*;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.cube.metadata.AbstractCubeTable;
import org.apache.hadoop.hive.ql.cube.metadata.CubeInterface;
import org.apache.hadoop.hive.ql.cube.metadata.CubeMetastoreClient;
import org.apache.hadoop.hive.ql.cube.metadata.SchemaGraph;
import org.apache.hadoop.hive.ql.cube.metadata.SchemaGraph.TableRelationship;
import org.apache.hadoop.hive.ql.cube.metadata.Dimension;
import org.apache.hadoop.hive.ql.cube.parse.CubeQueryContext.CandidateDim;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.JoinCond;
import org.apache.hadoop.hive.ql.parse.JoinType;
import org.apache.hadoop.hive.ql.parse.QB;
import org.apache.hadoop.hive.ql.parse.QBJoinTree;
import org.apache.hadoop.hive.ql.parse.SemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.SemanticException;
/**
 *
 * JoinResolver.
 *
 */
public class JoinResolver implements ContextRewriter {

  private static final Log LOG = LogFactory.getLog(JoinResolver.class);

  public static class JoinClause implements Comparable<JoinClause> {
    private final String clause;
    private final int cost;

    public JoinClause(String clause, int cost) {
      this.clause = clause;
      this.cost = cost;
    }

    public String getClause() {
      return clause;
    }

    public int getCost() {
      return cost;
    }

    @Override
    public int compareTo(JoinClause joinClause) {
      return cost - joinClause.getCost();
    }
  }
  /**
   * Store join chain information resolved by join resolver
   */
  public static class AutoJoinContext {
    // Map of a joined table to list of all possible paths from that table to the target
    private final Map<Dimension, List<SchemaGraph.JoinPath>> allPaths;
    // User supplied partial join conditions
    private final Map<AbstractCubeTable, String> partialJoinConditions;
    // True if the query contains user supplied partial join conditions
    private final boolean partialJoinChains;
    // Map of joined table to the join type (if provided by user)
    private final Map<AbstractCubeTable, JoinType> tableJoinTypeMap;

    // True if joins were resolved automatically
    private boolean joinsResolved;
    // Target table for the auto join resolver
    private AbstractCubeTable autoJoinTarget;
    // Configuration string to control join type
    private String joinTypeCfg;

    // Map of a joined table to its columns which are part of any of the join paths. This is used
    // in candidate table resolver
    Map<AbstractCubeTable, List<String>> joinPathColumns = new HashMap<AbstractCubeTable, List<String>>();

    public AutoJoinContext(Map<Dimension, List<SchemaGraph.JoinPath>> allPaths,
        Map<AbstractCubeTable, String> partialJoinConditions,
        boolean partialJoinChains,
        Map<AbstractCubeTable, JoinType> tableJoinTypeMap,
        AbstractCubeTable autoJoinTarget, String joinTypeCfg,
        boolean joinsResolved) {
      this.allPaths = allPaths;
      initJoinPathColumns(allPaths);
      this.partialJoinConditions = partialJoinConditions;
      this.partialJoinChains = partialJoinChains;
      this.tableJoinTypeMap = tableJoinTypeMap;
      this.autoJoinTarget = autoJoinTarget;
      this.joinTypeCfg = joinTypeCfg;
      this.joinsResolved = joinsResolved;
    }

    // Populate map of tables to their columns which are present in any of the join paths
    private void initJoinPathColumns(Map<Dimension, List<SchemaGraph.JoinPath>> allPaths) {
      for (List<SchemaGraph.JoinPath> joinPaths : allPaths.values()) {
        for (SchemaGraph.JoinPath path : joinPaths) {
          for (TableRelationship edge : path.getEdges()) {
            // Populate columns of the 'from' table
            AbstractCubeTable fromTable = edge.getFromTable();
            String fromColumn = edge.getFromColumn();
            List<String> columnsOfFromTable = joinPathColumns.get(fromTable);
            if (columnsOfFromTable == null) {
              columnsOfFromTable = new ArrayList<String>();
              joinPathColumns.put(fromTable, columnsOfFromTable);
            }
            columnsOfFromTable.add(fromColumn);

            // Similarly populate for the 'to' table
            AbstractCubeTable toTable = edge.getToTable();
            String toColumn = edge.getToColumn();
            List<String> columnsOfToTable = joinPathColumns.get(toTable);
            if (columnsOfToTable == null) {
              columnsOfToTable = new ArrayList<String>();
              joinPathColumns.put(toTable, columnsOfToTable);
            }
            columnsOfToTable.add(toColumn);

          }
        }
      }
    }


    public Map<AbstractCubeTable, String> getPartialJoinConditions() {
      return partialJoinConditions;
    }

    public String getFromString(HQLContext context, CubeQueryContext cubeql) {
      String fromString = "";
      if (autoJoinTarget instanceof CubeInterface) {
        fromString = context.getFactToQuery().getStorageString(
            cubeql.getAliasForTabName(autoJoinTarget.getName()));
      } else {
        fromString = context.getDimsToQuery().get(autoJoinTarget).getStorageString(
            cubeql.getAliasForTabName(autoJoinTarget.getName()));
      }

      Iterator<JoinClause> itr = getJoinClausesForAllPaths(context.getDimsToQuery(), cubeql);
      JoinClause minCostClause = null;

      while (itr.hasNext()) {
        JoinClause clause = itr.next();
        if (minCostClause == null || minCostClause.getCost() > clause.getCost()) {
          minCostClause = clause;
        }
      }

      fromString += minCostClause.getClause();
      return fromString;
    }

    // Some refactoring needed to account for multiple join paths
    public String getMergedJoinClause(
        Map<Dimension, List<TableRelationship>> joinChain,
        Map<Dimension, CandidateDim> dimsToQuery,
        CubeQueryContext cubeql) {

      Set<String> clauses = new LinkedHashSet<String>();
      String joinTypeStr = "";
      JoinType joinType = JoinType.INNER;

      // this flag is set to true if user has specified a partial join chain
      if (!partialJoinChains) {
        // User has not specified any join conditions. In this case, we rely on configuration for the join type
        if (StringUtils.isNotBlank(joinTypeCfg)) {
          joinType = JoinType.valueOf(joinTypeCfg.toUpperCase());
          joinTypeStr = getJoinTypeStr(joinType);
        }
      }

      for (Map.Entry<Dimension, List<TableRelationship>> entry : joinChain.entrySet()) {
        List<TableRelationship> chain = entry.getValue();
        Dimension table = entry.getKey();

        if (partialJoinChains) {
          joinType = tableJoinTypeMap.get(table);
          joinTypeStr = getJoinTypeStr(joinType);
        }

        for (int i = chain.size() - 1; i >= 0; i--) {
          TableRelationship rel = chain.get(i);
          StringBuilder clause = new StringBuilder(joinTypeStr)
          .append(" join ");
          // Add storage table name followed by alias
          clause.append(dimsToQuery.get(rel.getToTable()).getStorageString(
              cubeql.getAliasForTabName(rel.getToTable().getName())));

          clause.append(" on ")
          .append(cubeql.getAliasForTabName(rel.getFromTable().getName())).append(".").append(rel.getFromColumn())
          .append(" = ")
          .append(cubeql.getAliasForTabName(rel.getToTable().getName())).append(".").append(rel.getToColumn());

          // We have to push user specified filters for the joined tables
          String userFilter = null;
          // Partition condition on the tables also needs to be pushed depending on the join
          String storageFilter = null;

          if (JoinType.INNER == joinType || JoinType.LEFTOUTER == joinType || JoinType.LEFTSEMI == joinType) {
            // For inner and left joins push filter of right table
            userFilter = partialJoinConditions.get(rel.getToTable());
            if (partialJoinConditions.containsKey(rel.getFromTable())) {
              if (StringUtils.isNotBlank(userFilter)) {
                userFilter += (" AND " + partialJoinConditions.get(rel.getFromTable()));
              } else {
                userFilter = partialJoinConditions.get(rel.getFromTable());
              }
            }
            storageFilter = getStorageFilter(dimsToQuery, rel.getToTable());
            dimsToQuery.get(rel.getToTable()).setWhereClauseAdded();
          } else if (JoinType.RIGHTOUTER == joinType) {
            // For right outer joins, push filters of left table
            userFilter = partialJoinConditions.get(rel.getFromTable());
            if (partialJoinConditions.containsKey(rel.getToTable())) {
              if (StringUtils.isNotBlank(userFilter)) {
                userFilter += (" AND " + partialJoinConditions.get(rel.getToTable()));
              } else {
                userFilter = partialJoinConditions.get(rel.getToTable());
              }
            }
            if (rel.getFromTable() instanceof Dimension) {
              storageFilter = getStorageFilter(dimsToQuery, rel.getFromTable());
              dimsToQuery.get(rel.getFromTable()).setWhereClauseAdded();
            }
          } else if (JoinType.FULLOUTER == joinType) {
            // For full outer we need to push filters of both left and right tables in the join clause
            String leftFilter = null, rightFilter = null;
            String leftStorageFilter = null, rightStorgeFilter = null;

            if (StringUtils.isNotBlank(partialJoinConditions.get(rel.getFromTable()))) {
              leftFilter = partialJoinConditions.get(rel.getFromTable()) + " and ";
            }

            if (rel.getFromTable() instanceof Dimension) {
              leftStorageFilter =
                  getStorageFilter(dimsToQuery, rel.getFromTable());
              if (StringUtils.isNotBlank((leftStorageFilter))) {
                dimsToQuery.get(rel.getFromTable()).setWhereClauseAdded();
              }
            }

            if (StringUtils.isNotBlank(partialJoinConditions.get(rel.getToTable()))) {
              rightFilter = partialJoinConditions.get(rel.getToTable());
            }

            rightStorgeFilter =
              getStorageFilter(dimsToQuery, rel.getToTable());
            if (StringUtils.isNotBlank(rightStorgeFilter)) {
              if (StringUtils.isNotBlank((leftStorageFilter))) {
                leftStorageFilter += " and ";
              }
              dimsToQuery.get(rel.getToTable()).setWhereClauseAdded();
            }

            userFilter = (leftFilter == null ? "" : leftFilter) + (rightFilter == null ? "" : rightFilter);
            storageFilter = (leftStorageFilter == null ? "" : leftStorageFilter) +
              (rightStorgeFilter == null ? "" : rightStorgeFilter);
          }

          if (StringUtils.isNotBlank(userFilter)) {
            clause.append(" and ").append(userFilter);
          }
          if (StringUtils.isNotBlank(storageFilter)) {
            clause.append (" and ").append(storageFilter);
          }
          clauses.add(clause.toString());
        }
      }
      return StringUtils.join(clauses, "");
    }

    private String getStorageFilter(Map<Dimension, CandidateDim> dimsToQuery,
                                    AbstractCubeTable table) {
      String whereClause = "";
      if (dimsToQuery != null && dimsToQuery.get(table) != null) {
          if (StringUtils.isNotBlank(dimsToQuery.get(table).whereClause)) {
            whereClause = dimsToQuery.get(table).whereClause;
          }
      }
      return whereClause;
    }

    /**
     * @return the joinsResolved
     */
    public boolean isJoinsResolved() {
      return joinsResolved;
    }


    public List<String> getJoinPathColumnsOfTable(AbstractCubeTable table) {
      return joinPathColumns.get(table);
    }

    /** There can be multiple join paths between a dimension and the target. Set of all possible join clauses
     * is the cartesian product of join paths of all dimensions
     */
    public Iterator<JoinClause> getJoinClausesForAllPaths(final Map<Dimension, CandidateDim> dimsToQuery,
                                              final CubeQueryContext cubeql) {

      // Remove join paths which cannot be satisfied by the resolved candidate fact and dimension tables

      Set<CubeQueryContext.CandidateFact> candidateFacts = cubeql.getCandidateFactTables();
      if (!candidateFacts.isEmpty()) {
        // At this point it is assumed that least fact resolver has been run
        CubeQueryContext.CandidateFact candidateFact = candidateFacts.iterator().next();
        Set<String> factColumns = candidateFact.fact.getAllFieldNames();

        for (List<SchemaGraph.JoinPath> paths : allPaths.values()) {
          for (int i = 0; i < paths.size(); i++) {
            SchemaGraph.JoinPath jp = paths.get(i);
            List<String> cubeCols = jp.getColumnsForTable((AbstractCubeTable) cubeql.getCube());
            if (cubeCols != null && !factColumns.containsAll(cubeCols)) {
              // This path requires some columns from the cube which are not present in the candidate fact
              // Remove this path
              paths.remove(i);
              i--;
            }
          }
        }
      }

      Map<Dimension, Set<CubeQueryContext.CandidateDim>> candidateDims = cubeql.getCandidateDimTables();
      if (!candidateDims.isEmpty()) {
        // At this point it is assumed that least dimension resolver has been run, so we expect only one CandidateDim
        // for each Dimension
        for (Dimension dimension : candidateDims.keySet()) {
          CubeQueryContext.CandidateDim candidateDim = candidateDims.get(dimension).iterator().next();
          Set<String> dimCols = candidateDim.dimtable.getAllFieldNames();

          for (List<SchemaGraph.JoinPath> paths : allPaths.values()) {
            for (int i = 0; i < paths.size(); i++) {
              SchemaGraph.JoinPath jp = paths.get(i);
              List<String> candidateDimCols = jp.getColumnsForTable(dimension);
              if (candidateDimCols != null && !dimCols.containsAll(candidateDimCols)) {
                // This path requires some columns from the dimension which are not present in the candidate dim
                // Remove this path
                paths.remove(i);
                i--;
              }
            }
          }
        }
      }

      // Number of paths in each path set
      final int groupSizes[] = new int[allPaths.values().size()];
      // Total number of elements in the cartesian product
      int numSamples = 1;
      // All path sets
      final List<List<SchemaGraph.JoinPath>> pathSets = new ArrayList<List<SchemaGraph.JoinPath>>();
      // Dimension corresponding to the path sets
      final Dimension dimensions[] = new Dimension[groupSizes.length];

      int i = 0;
      for (Map.Entry<Dimension, List<SchemaGraph.JoinPath>> entry : allPaths.entrySet()) {
        dimensions[i] = entry.getKey();
        List<SchemaGraph.JoinPath> group = entry.getValue();
        pathSets.add(group);
        groupSizes[i] = group.size();
        numSamples *= groupSizes[i];
        i++;
      }

      final int selection[] = new int[groupSizes.length];
      final int MAX_SAMPLE_COUNT = numSamples;

      // Return a lazy iterator over all possible join chains
      return new Iterator<JoinClause>() {
        int sample = 0;
        Map<Dimension, List<TableRelationship>> chain = new LinkedHashMap<Dimension, List<TableRelationship>>();

        @Override
        public boolean hasNext() {
          return sample < MAX_SAMPLE_COUNT;
        }

        @Override
        public JoinClause next() {
          getNextSelection(selection, sample);

          for (int i = 0; i < selection.length; i++) {
            int selectedPath = selection[i];
            List<TableRelationship> path = pathSets.get(i).get(selectedPath).getEdges();
            chain.put(dimensions[i], path);
          }

          // Compute the merged join chain for this path
          String clause = getMergedJoinClause(chain, dimsToQuery, cubeql);

          sample++;
          // Cost of join = number of tables joined in the clause
          return new JoinClause(clause, StringUtils.countMatches(clause, "join"));
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException("Cannot remove elements!");
        }

        // Generate the next selection in the cartesian product of join paths
        public void getNextSelection(int[] selection, int sample) {
          // Populate next selection array
          boolean changed = false;

          for (int i = selection.length - 1; !changed && i >= 0; i--) {
            if (selection[i] < groupSizes[i] - 1) {
              selection[i]++;
              changed = true;
            } else {
              // Roll over
              selection[i] = 0;
            }
          }
        }
      };
    }

    public Map<Dimension, List<SchemaGraph.JoinPath>> getAllPaths() {
      return allPaths;
    }
  }

  static String getJoinTypeStr(JoinType joinType) {
    if (joinType == null) {
      return "";
    }
    switch (joinType) {
      case FULLOUTER: return " full outer";
      case INNER: return " inner";
      case LEFTOUTER: return " left outer";
      case LEFTSEMI: return " left semi";
      case UNIQUE: return " unique";
      case RIGHTOUTER: return " right outer";
      default: return "";
    }
  }

  private CubeMetastoreClient metastore;
  private Map<AbstractCubeTable, String> partialJoinConditions;
  private Map<AbstractCubeTable, JoinType> tableJoinTypeMap;
  private boolean partialJoinChain;
  private AbstractCubeTable target;
  private HiveConf conf;

  public JoinResolver(Configuration conf) {
  }

  private CubeMetastoreClient getMetastoreClient() throws HiveException {
    if (metastore == null) {
      metastore = CubeMetastoreClient.getInstance(new HiveConf(this.getClass()));
    }

    return metastore;
  }

  @Override
  public void rewriteContext(CubeQueryContext cubeql) throws SemanticException {
    partialJoinConditions = new HashMap<AbstractCubeTable, String>();
    tableJoinTypeMap = new HashMap<AbstractCubeTable, JoinType>();
    try {
      conf = cubeql.getHiveConf();
      resolveJoins(cubeql);
    } catch (HiveException e) {
      throw new SemanticException(e);
    }
  }

  public void resolveJoins(CubeQueryContext cubeql) throws HiveException {
    QB cubeQB = cubeql.getQB();
    boolean joinResolverDisabled = conf.getBoolean(
        CubeQueryConfUtil.DISABLE_AUTO_JOINS, CubeQueryConfUtil.DEFAULT_DISABLE_AUTO_JOINS);
    if (joinResolverDisabled) {
      if (cubeql.getJoinTree() != null) {
        cubeQB.setQbJoinTree(genJoinTree(cubeQB, cubeql.getJoinTree(), cubeql));
      }
    } else {
      autoResolveJoins(cubeql);
    }
  }

  protected SchemaGraph getSchemaGraph() throws HiveException {
    SchemaGraph graph = getMetastoreClient().getSchemaGraph();
    if (graph == null) {
      graph = new SchemaGraph(getMetastoreClient());
      graph.buildSchemaGraph();
      getMetastoreClient().setSchemaGraph(graph);
    }
    return graph;
  }
  /**
   * Resolve joins automatically for the given query.
   * @param cubeql
   * @throws SemanticException
   */
  public void autoResolveJoins(CubeQueryContext cubeql) throws HiveException {
    // Check if this query needs a join -
    // A join is needed if there is a cube and at least one dimension, or, 0 cubes and more than one
    // dimensions
    Set<Dimension> dimensions = cubeql.getDimensions();
    // Add dimensions specified in the partial join tree
    ASTNode joinClause = cubeql.getQB().getParseInfo().getJoinExpr();
    if (joinClause == null) {
      // Only cube in the query
      if (cubeql.hasCubeInQuery()) {
        target = (AbstractCubeTable)cubeql.getCube();
      } else {
        String targetDimAlias = cubeql.getQB().getTabAliases().iterator().next();
        String targetDimTable = cubeql.getQB().getTabNameForAlias(targetDimAlias);
        if (targetDimTable == null) {
          LOG.warn("Null table for alias " + targetDimAlias);
        }
        target = getMetastoreClient().getDimension(targetDimTable);
      }
    }
    searchDimensionTables(joinClause);
    if (target == null) {
      LOG.warn("Can't resolve joins for null target");
      return;
    }

    boolean hasDimensions = (dimensions != null && !dimensions.isEmpty()) || !partialJoinConditions.isEmpty();
    // Query has a cube and at least one dimension
    boolean cubeAndDimQuery = cubeql.hasCubeInQuery() && hasDimensions;
    // This query has only dimensions in it
    boolean dimOnlyQuery = !cubeql.hasCubeInQuery() && hasDimensions;

    if (!cubeAndDimQuery && !dimOnlyQuery) {
      LOG.info("Not a cube query or dim only quey");
      return;
    }

    Set<Dimension> dimTables =
        new HashSet<Dimension>(dimensions);
    for (AbstractCubeTable partiallyJoinedTable : partialJoinConditions.keySet()) {
      dimTables.add((Dimension) partiallyJoinedTable);
    }
    // Remove target
    dimTables.remove(target);
    if (dimTables.isEmpty()) {
      // Joins not required
      LOG.info("No dimension tables to resolve!");
      return;
    }

    SchemaGraph graph = getSchemaGraph();
    Map<Dimension, List<SchemaGraph.JoinPath>> multipleJoinPaths =
      new LinkedHashMap<Dimension, List<SchemaGraph.JoinPath>>();

    // Resolve join path for each dimension accessed in the query
    for (Dimension joinee : dimTables) {
      // Find all possible join paths
      SchemaGraph.GraphSearch search = new SchemaGraph.GraphSearch(joinee, target, graph);
      List<SchemaGraph.JoinPath> joinPaths = search.findAllPathsToTarget();
      if (joinPaths != null && !joinPaths.isEmpty()) {
        multipleJoinPaths.put(joinee, search.findAllPathsToTarget());
      } else {
        // No link to cube from this dim, can't proceed with query
        if (LOG.isDebugEnabled()) {
          graph.print();
        }
        LOG.warn("No join path between " + joinee.getName() + " and " + target.getName());
        throw new SemanticException(ErrorMsg.NO_JOIN_PATH, joinee.getName(), target.getName());
      }
    }

    for (List<SchemaGraph.JoinPath> joinPathList : multipleJoinPaths.values()) {
      for (SchemaGraph.JoinPath joinPath : joinPathList) {
        for (TableRelationship rel : joinPath.getEdges()) {
          // Add the joined tables to the queries table sets so that they are resolved in candidate resolver
          cubeql.addQueriedTable(rel.getToTable().getName());
          cubeql.addQueriedTable(rel.getFromTable().getName());
        }
      }
    }

    AutoJoinContext joinCtx = new AutoJoinContext(multipleJoinPaths, partialJoinConditions,
        partialJoinChain, tableJoinTypeMap, target,
        conf.get(CubeQueryConfUtil.JOIN_TYPE_KEY), true);
      cubeql.setAutoJoinCtx(joinCtx);
  }

  private void setTarget(ASTNode node) throws HiveException {
    String targetTableName =
        HQLParser.getString(HQLParser.findNodeByPath(node, TOK_TABNAME, Identifier));
    if (getMetastoreClient().isDimension(targetTableName)) {
      target = getMetastoreClient().getDimension(targetTableName);
    } else if (getMetastoreClient().isCube(targetTableName)) {
      target = (AbstractCubeTable)getMetastoreClient().getCube(targetTableName);
    } else {
      throw new SemanticException(ErrorMsg.JOIN_TARGET_NOT_CUBE_TABLE, targetTableName);
    }
  }

  private void searchDimensionTables(ASTNode node) throws HiveException {
    if (node == null) {
      return;
    }
    // User has specified join conditions partially. We need to store join conditions as well as join types
    partialJoinChain = true;
    if (isJoinToken(node)) {
      ASTNode left = (ASTNode) node.getChild(0);
      ASTNode right = (ASTNode) node.getChild(1);
      // Get table name and

      String tableName = HQLParser.getString(HQLParser.findNodeByPath(right,
          TOK_TABNAME, Identifier));

      Dimension dimension = getMetastoreClient().getDimension(tableName);
      String joinCond = "";
      if (node.getChildCount() > 2) {
        // User has specified a join condition for filter pushdown.
        joinCond = HQLParser.getString((ASTNode) node.getChild(2));
      }
      partialJoinConditions.put(dimension, joinCond);
      tableJoinTypeMap.put(dimension, getJoinType(node));
      if (isJoinToken(left)) {
        searchDimensionTables(left);
      } else {
        if (left.getToken().getType() == TOK_TABREF) {
          setTarget(left);
        }
      }
    } else if (node.getToken().getType() == TOK_TABREF) {
      setTarget(node);
    }

  }

  private JoinType getJoinType(ASTNode node) {
    switch(node.getToken().getType()) {
      case TOK_LEFTOUTERJOIN:
        return JoinType.LEFTOUTER;
      case TOK_LEFTSEMIJOIN:
        return JoinType.LEFTSEMI;
      case TOK_RIGHTOUTERJOIN:
        return JoinType.RIGHTOUTER;
      case TOK_FULLOUTERJOIN:
        return JoinType.FULLOUTER;
      case TOK_JOIN:
        return JoinType.INNER;
      case TOK_UNIQUEJOIN:
        return JoinType.UNIQUE;
      default:
        return JoinType.INNER;
    }
  }

  // Recursively find out join conditions
  private QBJoinTree genJoinTree(QB qb, ASTNode joinParseTree,
      CubeQueryContext cubeql)
          throws SemanticException {
    QBJoinTree joinTree = new QBJoinTree();
    JoinCond[] condn = new JoinCond[1];

    // Figure out join condition descriptor
    switch (joinParseTree.getToken().getType()) {
    case TOK_LEFTOUTERJOIN:
      joinTree.setNoOuterJoin(false);
      condn[0] = new JoinCond(0, 1, JoinType.LEFTOUTER);
      break;
    case TOK_RIGHTOUTERJOIN:
      joinTree.setNoOuterJoin(false);
      condn[0] = new JoinCond(0, 1, JoinType.RIGHTOUTER);
      break;
    case TOK_FULLOUTERJOIN:
      joinTree.setNoOuterJoin(false);
      condn[0] = new JoinCond(0, 1, JoinType.FULLOUTER);
      break;
    case TOK_LEFTSEMIJOIN:
      joinTree.setNoSemiJoin(false);
      condn[0] = new JoinCond(0, 1, JoinType.LEFTSEMI);
      break;
    default:
      condn[0] = new JoinCond(0, 1, JoinType.INNER);
      joinTree.setNoOuterJoin(true);
      break;
    }

    joinTree.setJoinCond(condn);

    ASTNode left = (ASTNode) joinParseTree.getChild(0);
    ASTNode right = (ASTNode) joinParseTree.getChild(1);

    // Left subtree is table or a subquery
    if ((left.getToken().getType() == TOK_TABREF)
        || (left.getToken().getType() == TOK_SUBQUERY)) {
      String tableName = SemanticAnalyzer.getUnescapedUnqualifiedTableName(
          (ASTNode) left.getChild(0)).toLowerCase();
      String alias = left.getChildCount() == 1 ? tableName
          : SemanticAnalyzer.unescapeIdentifier(left.getChild(left.getChildCount() - 1)
              .getText().toLowerCase());

      joinTree.setLeftAlias(alias);

      String[] leftAliases = new String[1];
      leftAliases[0] = alias;
      joinTree.setLeftAliases(leftAliases);

      String[] children = new String[2];
      children[0] = alias;
      joinTree.setBaseSrc(children);

    } else if (isJoinToken(left)) {
      // Left subtree is join token itself, so recurse down
      QBJoinTree leftTree = genJoinTree(qb, left, cubeql);

      joinTree.setJoinSrc(leftTree);

      String[] leftChildAliases = leftTree.getLeftAliases();
      String leftAliases[] = new String[leftChildAliases.length + 1];
      for (int i = 0; i < leftChildAliases.length; i++) {
        leftAliases[i] = leftChildAliases[i];
      }
      leftAliases[leftChildAliases.length] = leftTree.getRightAliases()[0];
      joinTree.setLeftAliases(leftAliases);

    } else {
      assert (false);
    }

    if ((right.getToken().getType() == TOK_TABREF)
        || (right.getToken().getType() == TOK_SUBQUERY)) {
      String tableName = SemanticAnalyzer.getUnescapedUnqualifiedTableName(
          (ASTNode) right.getChild(0)).toLowerCase();
      String alias = right.getChildCount() == 1 ? tableName
          : SemanticAnalyzer.unescapeIdentifier(right.getChild(
              right.getChildCount() - 1).getText().toLowerCase());
      String[] rightAliases = new String[1];
      rightAliases[0] = alias;
      joinTree.setRightAliases(rightAliases);
      String[] children = joinTree.getBaseSrc();
      if (children == null) {
        children = new String[2];
      }
      children[1] = alias;
      joinTree.setBaseSrc(children);
      // remember rhs table for semijoin
      if (joinTree.getNoSemiJoin() == false) {
        joinTree.addRHSSemijoin(alias);
      }
    } else {
      assert false;
    }

    ASTNode joinCond = (ASTNode) joinParseTree.getChild(2);
    if (joinCond != null) {
      cubeql.setJoinCond(joinTree, HQLParser.getString(joinCond));
    } else {
      // No join condition specified. this should be an error
      new SemanticException(ErrorMsg.NO_JOIN_CONDITION_AVAIABLE);
    }
    return joinTree;
  }

  private boolean isJoinToken(ASTNode node) {
    if ((node.getToken().getType() == TOK_JOIN)
        || (node.getToken().getType() == TOK_LEFTOUTERJOIN)
        || (node.getToken().getType() == TOK_RIGHTOUTERJOIN)
        || (node.getToken().getType() == TOK_FULLOUTERJOIN)
        || (node.getToken().getType() == TOK_LEFTSEMIJOIN)
        || (node.getToken().getType() == TOK_UNIQUEJOIN)) {
      return true;
    }
    return false;
  }
}
