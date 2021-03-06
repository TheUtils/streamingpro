/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package streaming.dsl

import org.antlr.v4.runtime.misc.Interval
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.catalyst.plans.logical.MLSQLDFParser
import streaming.dsl.auth.{MLSQLTable, OperateType, TableType}
import streaming.dsl.parser.DSLSQLLexer
import streaming.dsl.parser.DSLSQLParser.SqlContext
import streaming.dsl.template.TemplateMerge


/**
  * Created by allwefantasy on 27/8/2017.
  */
class SelectAdaptor(scriptSQLExecListener: ScriptSQLExecListener) extends DslAdaptor {

  val ENABLE_RUNTIME_SELECT_AUTH = scriptSQLExecListener.sparkSession
    .sparkContext
    .getConf
    .getBoolean("spark.mlsql.enable.runtime.select.auth", false)

  override def parse(ctx: SqlContext): Unit = {
    val input = ctx.start.getTokenSource().asInstanceOf[DSLSQLLexer]._input

    val start = ctx.start.getStartIndex()
    val stop = ctx.stop.getStopIndex()
    val interval = new Interval(start, stop)
    val originalText = input.getText(interval)

    val wowText = TemplateMerge.merge(originalText, scriptSQLExecListener.env().toMap)

    val chunks = wowText.split("\\s+")
    val tableName = chunks.last.replace(";", "")
    val sql = wowText.replaceAll(s"((?i)as)[\\s|\\n]+${tableName}", "")

    val df = scriptSQLExecListener.sparkSession.sql(sql)

    runtimeTableAuth(df)

    df.createOrReplaceTempView(tableName)
    scriptSQLExecListener.setLastSelectTable(tableName)
  }

  def runtimeTableAuth(df: DataFrame): Unit = {
    // enable runtime select auth
    if (ENABLE_RUNTIME_SELECT_AUTH) {
      scriptSQLExecListener.getTableAuth.foreach(tableAuth => {

        val tableAndCols = MLSQLDFParser.extractTableWithColumns(df)
        var mlsqlTables = List.empty[MLSQLTable]

        tableAndCols.foreach {
          case (table, cols) =>
            val stable = scriptSQLExecListener.sparkSession.catalog.getTable(table)
            val db = Option(stable.database)
            val tableStr = Option(stable.name)
            val ttpe = if (stable.isTemporary) {
              TableType.TEMP
            } else {
              TableType.HIVE
            }
            mlsqlTables ::= MLSQLTable(db, tableStr, Option(cols.toSet), OperateType.SELECT, None, ttpe)
        }

        tableAuth.auth(mlsqlTables)
      })
    }

  }
}
