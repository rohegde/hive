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


import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.ql.cube.metadata.UpdatePeriod;

public class CubeQueryConfUtil {
  public static final String STORAGE_TABLES_SFX = ".storagetables";
  public static final String UPDATE_PERIODS_SFX = ".updateperiods";
  public static final String FACT_TABLES_SFX = ".facttables";
  public static final String STORAGE_KEY_PFX = ".storage.";
  public static final String VALID_PFX = "cube.query.valid.";
  public static final String VALID_FACT_PFX = "cube.query.valid." +
      "fact.";

  public static final String VALID_STORAGE_DIM_TABLES = "cube.query.valid." +
      "dim.storgaetables";
  public static final String DRIVER_SUPPORTED_STORAGES = "cube.query.driver." +
      "supported.storages";
  public static final String FAIL_QUERY_ON_PARTIAL_DATA =
      "cube.query.fail.if.data.partial";
  public static final String NON_EXISTING_PARTITIONS =
      "cube.query.nonexisting.partitions";
  public static final String ADD_NON_EXISTING_PARTITIONS =
      "cube.query.add.nonexisting.partitions";
  public static final String ENABLE_MULTI_TABLE_SELECT =
      "cube.query.enable.multi.table.select";
  public static final String QUERY_MAX_INTERVAL = "cube.query.max.interval";
  public static final String PROCESS_TIME_PART_COL = "cube.query.process.time" +
      ".partition.column";
  public static final String LOOK_AHEAD_PT_PARTS_PFX =
      "cube.query.lookahead.ptparts.forinterval.";
  public static final String ENABLE_GROUP_BY_TO_SELECT =
      "cube.query.promote.groupby.toselect";
  public static final String ENABLE_SELECT_TO_GROUPBY =
      "cube.query.promote.select.togroupby";

  public static final boolean DEFAULT_MULTI_TABLE_SELECT = true;
  public static final int DEFAULT_LOOK_AHEAD_PT_PARTS = 1;
  public static final boolean DEFAULT_ENABLE_GROUP_BY_TO_SELECT = false;
  public static final boolean DEFAULT_ENABLE_SELECT_TO_GROUPBY = false;
  public static final boolean DEFAULT_ADD_NON_EXISTING_PARTITIONS = false;

  public static String getLookAheadPTPartsKey(UpdatePeriod interval) {
    return LOOK_AHEAD_PT_PARTS_PFX + interval.name().toLowerCase();
  }
  private static String getValidKeyCubePFX(String cubeName) {
    return VALID_PFX + cubeName.toLowerCase();
  }

  private static String getValidKeyFactPFX(String factName) {
    return VALID_FACT_PFX + factName.toLowerCase();
  }

  private static String getValidKeyStoragePFX(String factName,
      String storage) {
    return getValidKeyFactPFX(factName) + STORAGE_KEY_PFX + storage.toLowerCase();
  }

  public static String getValidFactTablesKey(String cubeName) {
    return getValidKeyCubePFX(cubeName) + FACT_TABLES_SFX;
  }

  public static String getValidStorageTablesKey(String factName) {
    return getValidKeyFactPFX(factName) + STORAGE_TABLES_SFX;
  }

  public static String getValidUpdatePeriodsKey(String fact,
      String storage) {
    return getValidKeyStoragePFX(fact, storage) + UPDATE_PERIODS_SFX;
  }

  public static List<String> getStringList(Configuration conf, String keyName) {
    String str = conf.get(keyName);
    List<String> list = StringUtils.isBlank(str) ? null :
      Arrays.asList(StringUtils.split(str.toLowerCase(), ","));
    return list;
  }

  public static final String DISABLE_AUTO_JOINS = "hive.cube.disable.auto.join";
  public static final boolean DEFAULT_DISABLE_AUTO_JOINS = true;
  public static final String JOIN_TYPE_KEY = "hive.cube.join.type";
  public static final String DISABLE_AGGREGATE_RESOLVER = "hive.cube.disable.aggregate.resolver";
  public static final boolean DEFAULT_DISABLE_AGGREGATE_RESOLVER = true;
  public static final String LIGHTEST_FACT_FIRST = "cube.query.pick.lightest.fact.first";
  public static final boolean DEFAULT_LIGHTEST_FACT_FIRST = false;
  public static final String TIME_RANGE_WRITER_CLASS = "cube.query.time.range.writer.class";
  public static final Class<? extends TimeRangeWriter> DEFAULT_TIME_RANGE_WRITER =
      ORTimeRangeWriter.class.asSubclass(TimeRangeWriter.class);
  public static final String PART_WHERE_CLAUSE_DATE_FORMAT = "cube.query.partition.where.clause.format";
}
