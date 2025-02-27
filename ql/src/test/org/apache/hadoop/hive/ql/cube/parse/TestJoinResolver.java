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

import static org.apache.hadoop.hive.ql.cube.parse.CubeTestSetup.getDbName;
import static org.apache.hadoop.hive.ql.cube.parse.CubeTestSetup.rewrite;
import static org.apache.hadoop.hive.ql.cube.parse.CubeTestSetup.twoDaysRange;
import static org.junit.Assert.*;

import java.util.*;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.cube.metadata.AbstractCubeTable;
import org.apache.hadoop.hive.ql.cube.metadata.CubeInterface;
import org.apache.hadoop.hive.ql.cube.metadata.CubeMetastoreClient;
import org.apache.hadoop.hive.ql.cube.metadata.SchemaGraph;
import org.apache.hadoop.hive.ql.cube.metadata.Dimension;
import org.apache.hadoop.hive.ql.cube.metadata.SchemaGraph.TableRelationship;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestJoinResolver {

  private  static CubeTestSetup setup;
  private  static HiveConf hconf = new HiveConf(TestJoinResolver.class);
  private CubeQueryRewriter driver;
  private CubeMetastoreClient metastore;


  @BeforeClass
  public static void setup() throws Exception {
    SessionState.start(hconf);
    setup = new CubeTestSetup();
    setup.createSources(hconf, TestJoinResolver.class.getSimpleName());
  }

  @AfterClass
  public static void tearDown() throws Exception {
    //setup.dropSources(hconf);
  }

  @Before
  public void setupInstance() throws Exception {
    hconf.set(CubeQueryConfUtil.DRIVER_SUPPORTED_STORAGES, "C1");
    hconf.setBoolean(CubeQueryConfUtil.DISABLE_AUTO_JOINS, false);
    driver = new CubeQueryRewriter(hconf);
    this.metastore = CubeMetastoreClient.getInstance(hconf);
  }

  @After
  public void closeInstance() throws  Exception {
  }

  // testBuildGraph - graph correctness
  @Test
  public void testBuildGraph() throws Exception {
    SchemaGraph schemaGraph = new SchemaGraph(metastore);
    schemaGraph.buildSchemaGraph();
    CubeInterface cube = metastore.getCube(CubeTestSetup.TEST_CUBE_NAME);
    Map<AbstractCubeTable, Set<TableRelationship>> graph = schemaGraph.getCubeGraph(cube);
    printGraph(graph);
    assertNotNull(graph);

    // Let's do some lookups
    Set<TableRelationship> dim4Edges = graph.get(metastore.getDimension("testdim4"));
    assertNotNull(dim4Edges);
    assertEquals(1, dim4Edges.size());

    List<TableRelationship> edges = new ArrayList<TableRelationship>(dim4Edges);
    TableRelationship dim4edge = edges.get(0);
    assertEquals("id", dim4edge.getToColumn());
    assertEquals(metastore.getDimension("testdim4"), dim4edge.getToTable());
    assertEquals("testdim4id", dim4edge.getFromColumn());
    assertEquals(metastore.getDimension("testdim3"), dim4edge.getFromTable());
  }

  private void searchPaths(AbstractCubeTable source,
                           AbstractCubeTable target,
                           SchemaGraph graph) {
    SchemaGraph.GraphSearch search = new SchemaGraph.GraphSearch(source, target, graph);
    search.setTrimLongerPaths(false);
    List<SchemaGraph.JoinPath> joinPaths = search.findAllPathsToTarget();

    System.out.println("@@ " + source + " ==> " + target + " paths =");
    int i = 0;
    for (SchemaGraph.JoinPath jp : joinPaths) {
      assertEquals(jp.getEdges().get(0).getToTable(), source);
      assertEquals(jp.getEdges().get(jp.getEdges().size() - 1).getFromTable(), target);
      Collections.reverse(jp.getEdges());
      System.out.println(++i + " " + jp.getEdges());
    }
  }

  @Test
  public void testFindChain() throws Exception {
    SchemaGraph schemaGraph = new SchemaGraph(metastore);
    schemaGraph.buildSchemaGraph();
    schemaGraph.print();

    // Search For all cubes and all dims to make sure that search terminates
    for (CubeInterface cube : metastore.getAllCubes()) {
      for (Dimension dim : metastore.getAllDimensions()) {
        searchPaths(dim, (AbstractCubeTable) cube, schemaGraph);
      }
    }

    for (Dimension dim : metastore.getAllDimensions()) {
      for (Dimension otherDim : metastore.getAllDimensions()) {
        if (otherDim != dim) {
          searchPaths(dim, otherDim, schemaGraph);
        }
      }
    }

    // Assert for testcube
    Dimension zipDim = metastore.getDimension("zipdim");
    Dimension cityDim = metastore.getDimension("citydim");
    Dimension stateDim = metastore.getDimension("statedim");
    Dimension countryDim = metastore.getDimension("countrydim");
    CubeInterface testCube = metastore.getCube("testcube");

    SchemaGraph.GraphSearch search = new SchemaGraph.GraphSearch(zipDim, (AbstractCubeTable) testCube, schemaGraph);
    search.setTrimLongerPaths(false);

    List<SchemaGraph.JoinPath> paths = search.findAllPathsToTarget();
    assertEquals(4, paths.size());
    validatePath(paths.get(0), zipDim, (AbstractCubeTable)testCube);
    validatePath(paths.get(1), zipDim, cityDim, (AbstractCubeTable)testCube);
    validatePath(paths.get(2), zipDim, cityDim, stateDim, (AbstractCubeTable)testCube);
    validatePath(paths.get(3), zipDim, cityDim, stateDim, countryDim, (AbstractCubeTable)testCube);
  }

  private void validatePath(SchemaGraph.JoinPath jp, AbstractCubeTable ... tables) {
    assertTrue(!jp.getEdges().isEmpty());
    Set<AbstractCubeTable> expected = new HashSet<AbstractCubeTable>(Arrays.asList(tables));
    Set<AbstractCubeTable> actual = new HashSet<AbstractCubeTable>();
    for (TableRelationship edge : jp.getEdges()) {
      if (edge.getFromTable() != null) {
        actual.add(edge.getFromTable());
      }
      if (edge.getToTable() != null) {
        actual.add(edge.getToTable());
      }
    }

    assertEquals("Edges: " + jp.getEdges().toString()
      + " Expected Tables: " + Arrays.toString(tables)
      + " Actual Tables: " + actual.toString(),
      expected,
      actual
    );
  }
  private void printGraph(Map<AbstractCubeTable, Set<TableRelationship>> graph) {
    System.out.println("--Graph-Nodes=" + graph.size());
    for (AbstractCubeTable tab : graph.keySet()) {
      System.out.println(tab.getName() + "::" + graph.get(tab));
    }
  }

  private String getAutoResolvedFromString(CubeQueryContext query) {
    if (query.getAutoJoinCtx() != null) {
      return query.getAutoJoinCtx().getFromString(query.getHqlContext(), query);
    }
    return null;
  }

  @Test
  public void testAutoJoinResolver() throws Exception {
    //Test 1 Cube + dim
    String query = "select citydim.name, testDim2.name, testDim4.name, msr2 from testCube where "
      + twoDaysRange;
    CubeQueryContext rewrittenQuery = driver.rewrite(query);
    String hql = rewrittenQuery.toHQL();
    System.out.println("testAutoJoinResolverauto join HQL:" + hql);
    System.out.println("testAutoJoinResolver@@Resolved join chain:[" + getAutoResolvedFromString(rewrittenQuery)+ "]");
    List<String> expectedClauses = new ArrayList<String>();
    expectedClauses.add(getDbName() + "c1_testfact2_raw testcube");
    expectedClauses.add(getDbName() + "c1_citytable citydim on testcube.cityid = citydim.id and (citydim.dt = 'latest')");
    expectedClauses.add(getDbName() + "c1_testdim2tbl testdim2 on testcube.dim2 = testdim2.id and (testdim2.dt = 'latest')");
    expectedClauses.add(getDbName() + "c1_testdim3tbl testdim3 on testdim2.testdim3id = testdim3.id and (testdim3.dt = 'latest')");
    expectedClauses.add(getDbName() + "c1_testdim4tbl testdim4 on testdim3.testdim4id = testdim4.id and (testdim4.dt = 'latest')");

    List<String> actualClauses = new ArrayList<String>();
    for (String clause : StringUtils.splitByWholeSeparator(getAutoResolvedFromString(rewrittenQuery), "join")) {
      if (StringUtils.isNotBlank(clause))  {
        actualClauses.add(clause.trim());
      }
    }
    System.out.println("testAutoJoinResolverExpected1" + expectedClauses);
    System.out.println("testAutoJoinResolverActual1" + actualClauses);
    assertEquals(expectedClauses, actualClauses);

    //Test 2  Dim only query
    expectedClauses.clear();
    actualClauses.clear();
    String dimOnlyQuery = "select testDim2.name, testDim4.name FROM testDim2 where "
      + twoDaysRange;
    rewrittenQuery = driver.rewrite(dimOnlyQuery);
    hql = rewrittenQuery.toHQL();
    System.out.println("testAutoJoinResolverauto join HQL:" + hql);
    System.out.println("testAutoJoinResolver@@Resolved join chain:[" + getAutoResolvedFromString(rewrittenQuery)+ "]");
    expectedClauses.add(getDbName() + "c1_testdim2tbl testdim2");
    expectedClauses.add(getDbName() + "c1_testdim3tbl testdim3 on testdim2.testdim3id = testdim3.id and (testdim3.dt = 'latest')");
    expectedClauses.add(getDbName() + "c1_testdim4tbl testdim4 on testdim3.testdim4id = testdim4.id and (testdim4.dt = 'latest')");
    for (String clause : StringUtils.splitByWholeSeparator(getAutoResolvedFromString(rewrittenQuery), "join")) {
      if (StringUtils.isNotBlank(clause))  {
        actualClauses.add(clause.trim());
      }
    }
    System.out.println("testAutoJoinResolverExpected2" + expectedClauses);
    System.out.println("testAutoJoinResolverActual2" + actualClauses);
    assertEquals(expectedClauses, actualClauses);

    //Test 3 Dim only query should throw error
    String errDimOnlyQuery = "select citydim.id, testDim4.name FROM citydim where "
      + twoDaysRange;
    try {
      hql = rewrite(driver, errDimOnlyQuery);
      fail("dim only query should throw error");
    } catch (SemanticException exc) {
    }
  }

  @Test
  public void testPartialJoinResolver() throws Exception {
    String query = "SELECT citydim.name, testDim4.name, msr2 " +
      "FROM testCube left outer join citydim ON citydim.name = 'FOOBAR'" +
      " right outer join testDim4 on testDim4.name='TESTDIM4NAME'" +
      " WHERE " + twoDaysRange;
    CubeQueryContext rewrittenQuery = driver.rewrite(query);
    String hql = rewrittenQuery.toHQL();
    System.out.println("testPartialJoinResolver Partial join hql: " + hql);
    String partSQL = " left outer join " + getDbName() 
        + "c1_citytable citydim on testcube.cityid " +
        "= citydim.id and (( citydim . name ) =  'FOOBAR' ) " +
        "and (citydim.dt = 'latest')";
    assertTrue(hql.contains(partSQL));
    partSQL = " right outer join "+ getDbName() +"c1_testdim2tbl testdim2 on " +
        "testcube.dim2 = testdim2.id right outer join "+ getDbName()+
        "c1_testdim3tbl testdim3 on testdim2.testdim3id = testdim3.id and " +
        "(testdim2.dt = 'latest') right outer join "+ getDbName() +
        "c1_testdim4tbl testdim4 on testdim3.testdim4id = testdim4.id and " +
        "(( testdim4 . name ) =  'TESTDIM4NAME' ) and (testdim3.dt = 'latest')";

    assertTrue(hql.contains(partSQL));
  }

  @Test
  public void testJoinNotRequired() throws Exception {
    String query = "SELECT msr2 FROM testCube WHERE " + twoDaysRange;
    CubeQueryContext ctx = driver.rewrite(query);
    String hql = ctx.toHQL();
    String joinClause = getAutoResolvedFromString(ctx);
    System.out.println("@Resolved join clause " + joinClause);
    assertTrue(joinClause == null || joinClause.isEmpty());
    assertTrue(ctx.getAutoJoinCtx() == null);
  }

  @Test
  public void testJoinWithoutCondition() throws Exception {
    String query = "SELECT citydim.name, msr2 FROM testCube WHERE " + twoDaysRange;
    CubeQueryContext ctx = driver.rewrite(query);
    String hql = ctx.toHQL();
    String joinClause = getAutoResolvedFromString(ctx);
    System.out.println("@Resolved join clause " + joinClause);
    assertEquals(getDbName() + "c1_testfact2_raw testcube join " + getDbName() + "c1_citytable citydim on testcube.cityid = citydim.id and (citydim.dt = 'latest')", joinClause.trim());
  }

  @Test
  public void testJoinTypeConf() throws Exception {
    hconf.set(CubeQueryConfUtil.JOIN_TYPE_KEY, "LEFTOUTER");
    System.out.println("@@Set join type to " + hconf.get(CubeQueryConfUtil.JOIN_TYPE_KEY));
    driver = new CubeQueryRewriter(hconf);
    String query = "select citydim.name, msr2 FROM testCube WHERE " + twoDaysRange;
    CubeQueryContext ctx = driver.rewrite(query);
    String hql = ctx.toHQL();
    System.out.println("testJoinTypeConf@@Resolved join clause1 - " + getAutoResolvedFromString(ctx));
    assertEquals(getDbName() + "c1_testfact2_raw testcube left outer join " + getDbName() + "c1_citytable citydim on testcube.cityid = citydim.id and (citydim.dt = 'latest')",
        getAutoResolvedFromString(ctx).trim());

    hconf.set(CubeQueryConfUtil.JOIN_TYPE_KEY, "FULLOUTER");
    System.out.println("@@Set join type to " + hconf.get(CubeQueryConfUtil.JOIN_TYPE_KEY));
    driver = new CubeQueryRewriter(hconf);
    ctx = driver.rewrite(query);
    hql = ctx.toHQL();
    System.out.println("testJoinTypeConf@@Resolved join clause2 - "+ getAutoResolvedFromString(ctx));
    assertEquals(getDbName() + "c1_testfact2_raw testcube full outer join " + getDbName() + "c1_citytable citydim on testcube.cityid = citydim.id and (citydim.dt = 'latest')",
        getAutoResolvedFromString(ctx).trim());
  }

  @Test
  public void testPreserveTableAlias() throws Exception {
    hconf.set(CubeQueryConfUtil.JOIN_TYPE_KEY, "LEFTOUTER");
    String query = "select c.name, t.msr2 FROM testCube t join citydim c WHERE " + twoDaysRange;
    CubeQueryRewriter driver = new CubeQueryRewriter(hconf);
    CubeQueryContext ctx = driver.rewrite(query);
    String hql = ctx.toHQL();
    System.out.println("testPreserveTableAlias@@HQL:" + hql);
    System.out.println("testPreserveTableAlias@@Resolved join clause - " + getAutoResolvedFromString(ctx));
    // Check that aliases are preserved in the join clause
    // Conf will be ignored in this case since user has specified partial join
    assertEquals(getDbName() + "c1_testfact2_raw t inner join "+ getDbName() + "c1_citytable c on t.cityid = c.id and (c.dt = 'latest')",
        getAutoResolvedFromString(ctx).trim());
    String whereClause = hql.substring(hql.indexOf("WHERE"));
    // Check that the partition condition is not added again in where clause
    assertFalse(whereClause.contains("c.dt = 'latest'"));
  }

  @Test
  public void testDimOnlyQuery() throws Exception {
    hconf.set(CubeQueryConfUtil.JOIN_TYPE_KEY, "INNER");
    String query = "select citydim.name, statedim.name from citydim limit 10";
    HiveConf dimOnlyConf = new HiveConf(hconf);
    CubeQueryRewriter rewriter = new CubeQueryRewriter(dimOnlyConf);
    CubeQueryContext ctx = rewriter.rewrite(query);
    String hql = ctx.toHQL();
    System.out.println("testDimOnlyQuery@@@HQL:" + hql);
    System.out.println("testDimOnlyQuery@@@Resolved join clause: " + getAutoResolvedFromString(ctx));
    assertTrue(hql.contains("WHERE ((citydim.dt = 'latest')) LIMIT 10"));
    assertEquals(getDbName() +  "c1_citytable citydim inner join "+ getDbName() + "c1_statetable statedim on citydim.stateid = statedim.id and (statedim.dt = 'latest')",
        getAutoResolvedFromString(ctx).trim());

    String queryWithJoin = "select citydim.name, statedim.name from citydim join statedim";
    ctx = rewriter.rewrite(queryWithJoin);
    hql = ctx.toHQL();
    System.out.println("testDimOnlyQuery@@@HQL2:" + hql);
    HQLParser.parseHQL(hql);
    assertEquals(getDbName() +  "c1_citytable citydim inner join "+ getDbName() + "c1_statetable statedim on citydim.stateid = statedim.id and (statedim.dt = 'latest')",
        getAutoResolvedFromString(ctx).trim());
  }

  @Test
  public void testStorageFilterPushdown() throws Exception {
    String q = "SELECT citydim.name, statedim.name FROM citydim";
    HiveConf conf = new HiveConf(hconf);
    conf.set(CubeQueryConfUtil.JOIN_TYPE_KEY, "LEFTOUTER");
    CubeQueryRewriter rewriter = new CubeQueryRewriter(conf);
    CubeQueryContext context = rewriter.rewrite(q);
    String hql = context.toHQL();
    System.out.println("##1 hql " + hql);
    System.out.println("##1 " + getAutoResolvedFromString(context));
    assertEquals(getDbName() +  "c1_citytable citydim left outer join " + getDbName() + "c1_statetable statedim on citydim.stateid = statedim.id" +
      " and (statedim.dt = 'latest')", getAutoResolvedFromString(context).trim());
    assertTrue(hql.contains("WHERE ((citydim.dt = 'latest'))"));

    conf.set(CubeQueryConfUtil.JOIN_TYPE_KEY, "RIGHTOUTER");
    rewriter = new CubeQueryRewriter(conf);
    context = rewriter.rewrite(q);
    hql = context.toHQL();
    System.out.println("##2 hql " + hql);
    System.out.println("##2 " + getAutoResolvedFromString(context));
    assertEquals(getDbName() +  "c1_citytable citydim right outer join " + getDbName() + "c1_statetable statedim on citydim.stateid = statedim.id " +
      "and (citydim.dt = 'latest')", getAutoResolvedFromString(context).trim());
    assertTrue(hql.contains("WHERE ((statedim.dt = 'latest'))"));

    conf.set(CubeQueryConfUtil.JOIN_TYPE_KEY, "FULLOUTER");
    rewriter = new CubeQueryRewriter(conf);
    context = rewriter.rewrite(q);
    hql = context.toHQL();
    System.out.println("##3 hql " + hql);
    System.out.println("##3 " + getAutoResolvedFromString(context));
    assertEquals(getDbName() +  "c1_citytable citydim full outer join " + getDbName() + "c1_statetable statedim on citydim.stateid = statedim.id " +
      "and (citydim.dt = 'latest') and (statedim.dt = 'latest')", getAutoResolvedFromString(context).trim());
    assertTrue(!hql.contains("WHERE"));
  }
}
