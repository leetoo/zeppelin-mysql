/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.zeppelin.mysql;

import static org.apache.commons.lang.StringUtils.containsIgnoreCase;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterPropertyBuilder;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterResult.Code;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;

/**
 * MySQL interpreter for Zeppelin. This interpreter can also be used for accessing MariaDB and
 * Percona.
 * 
 * <ul>
 * <li>{@code mysql.url} - JDBC URL to connect to.</li>
 * <li>{@code mysql.user} - JDBC user name..</li>
 * <li>{@code mysql.password} - JDBC password..</li>
 * <li>{@code mysql.driver.name} - JDBC driver name.</li>
 * <li>{@code mysql.max.result} - Max number of SQL result to display.</li>
 * </ul>
 * 
 * <p>
 * How to use: <br/>
 * {@code %mysql.sql} <br/>
 * {@code 
 *  SELECT store_id, count(*) 
 *  FROM retail_demo.order_lineitems_pxf 
 *  GROUP BY store_id;
 * }
 * </p>
 * 
 * For SQL auto-completion use the (Ctrl+.) shortcut.
 */
public class MySqlInterpreter extends Interpreter {

  private final Logger logger = LoggerFactory.getLogger(MySqlInterpreter.class);

  private static final char WHITESPACE = ' ';
  private static final char NEWLINE = '\n';
  private static final char TAB = '\t';
  private static final String TABLE_MAGIC_TAG = "%table ";
  private static final String EXPLAIN_PREDICATE = "EXPLAIN ";
  private static final String UPDATE_COUNT_HEADER = "Update Count";

  static final String DEFAULT_JDBC_URL = "jdbc:mysql://localhost:3306/";
  static final String DEFAULT_JDBC_USER_PASSWORD = "";
  static final String DEFAULT_JDBC_USER_NAME = "root";
  static final String DEFAULT_JDBC_DRIVER_NAME = "com.mysql.jdbc.Driver";
  static final String DEFAULT_MAX_RESULT = "1000";

  static final String MYSQL_SERVER_URL = "mysql.url";
  static final String MYSQL_SERVER_USER = "mysql.user";
  static final String MYSQL_SERVER_PASSWORD = "mysql.password";
  static final String MYSQL_SERVER_DRIVER_NAME = "mysql.driver.name";
  static final String MYSQL_SERVER_MAX_RESULT = "mysql.max.result";
  static final String EMPTY_COLUMN_VALUE = "";

  static {
    Interpreter.register(
        "sql",
        "mysql",
        MySqlInterpreter.class.getName(),
        new InterpreterPropertyBuilder()
            .add(MYSQL_SERVER_URL, DEFAULT_JDBC_URL, "The URL for MySQL.")
            .add(MYSQL_SERVER_USER, DEFAULT_JDBC_USER_NAME, "The MySQL user name")
            .add(MYSQL_SERVER_PASSWORD, DEFAULT_JDBC_USER_PASSWORD,
                "The MySQL user password")
            .add(MYSQL_SERVER_DRIVER_NAME, DEFAULT_JDBC_DRIVER_NAME, "JDBC Driver Name")
            .add(MYSQL_SERVER_MAX_RESULT, DEFAULT_MAX_RESULT,
                "Max number of SQL result to display.").build());
  }

  private Connection jdbcConnection;
  private Statement currentStatement;
  private Exception exceptionOnConnect;
  private int maxResult;

  private SqlCompleter sqlCompleter;

  private static final Function<CharSequence, String> sequenceToStringTransformer =
      new Function<CharSequence, String>() {
        public String apply(CharSequence seq) {
          return seq.toString();
        }
      };

  private static final List<String> NO_COMPLETION = new ArrayList<>();

  public MySqlInterpreter(Properties property) {
    super(property);
  }

  @Override
  public void open() {

    logger.info("Open mysql connection!");

    // Ensure that no previous connections are left open.
    close();

    try {

      String driverName = getProperty(MYSQL_SERVER_DRIVER_NAME);
      String url = getProperty(MYSQL_SERVER_URL);
      String user = getProperty(MYSQL_SERVER_USER);
      String password = getProperty(MYSQL_SERVER_PASSWORD);
      maxResult = Integer.valueOf(getProperty(MYSQL_SERVER_MAX_RESULT));

      Class.forName(driverName);

      jdbcConnection = DriverManager.getConnection(url, user, password);

      sqlCompleter = createSqlCompleter(jdbcConnection);

      exceptionOnConnect = null;
      logger.info("Successfully created mysql connection");

    } catch (ClassNotFoundException | SQLException e) {
      logger.error("Cannot open connection", e);
      exceptionOnConnect = e;
      close();
    }
  }

  private SqlCompleter createSqlCompleter(Connection jdbcConnection) {

    SqlCompleter completer = null;
    try {
      Set<String> keywordsCompletions = SqlCompleter.getSqlKeywordsCompletions(jdbcConnection);
      Set<String> dataModelCompletions =
          SqlCompleter.getDataModelMetadataCompletions(jdbcConnection);
      SetView<String> allCompletions = Sets.union(keywordsCompletions, dataModelCompletions);
      completer = new SqlCompleter(allCompletions, dataModelCompletions);

    } catch (IOException | SQLException e) {
      logger.error("Cannot create SQL completer", e);
    }

    return completer;
  }

  @Override
  public void close() {

    logger.info("Close mysql connection!");

    try {
      if (getJdbcConnection() != null) {
        getJdbcConnection().close();
      }
    } catch (SQLException e) {
      logger.error("Cannot close connection", e);
    } finally {
      exceptionOnConnect = null;
    }
  }

  private InterpreterResult executeSql(String sql) {
    try {

      if (exceptionOnConnect != null) {
        return new InterpreterResult(Code.ERROR, exceptionOnConnect.getMessage());
      }

      currentStatement = getJdbcConnection().createStatement();

      currentStatement.setMaxRows(maxResult);

      StringBuilder msg;
      boolean isTableType = false;

      if (containsIgnoreCase(sql, EXPLAIN_PREDICATE)) {
        msg = new StringBuilder();
      } else {
        msg = new StringBuilder(TABLE_MAGIC_TAG);
        isTableType = true;
      }

      ResultSet resultSet = null;
      try {

        boolean isResultSetAvailable = currentStatement.execute(sql);

        if (isResultSetAvailable) {
          resultSet = currentStatement.getResultSet();

          ResultSetMetaData md = resultSet.getMetaData();

          for (int i = 1; i < md.getColumnCount() + 1; i++) {
            if (i > 1) {
              msg.append(TAB);
            }
            msg.append(replaceReservedChars(isTableType, md.getColumnLabel(i)));
          }
          msg.append(NEWLINE);

          int displayRowCount = 0;
          while (resultSet.next() && displayRowCount < getMaxResult()) {
            for (int i = 1; i < md.getColumnCount() + 1; i++) {
              msg.append(replaceReservedChars(isTableType, resultSet.getString(i)));
              if (i != md.getColumnCount()) {
                msg.append(TAB);
              }
            }
            msg.append(NEWLINE);
            displayRowCount++;
          }
        } else {
          // Response contains either an update count or there are no results.
          int updateCount = currentStatement.getUpdateCount();
          msg.append(UPDATE_COUNT_HEADER).append(NEWLINE);
          msg.append(updateCount).append(NEWLINE);

          // In case of update event (e.g. isResultSetAvailable = false) update the completion
          // meta-data.
          if (sqlCompleter != null) {
            sqlCompleter.updateDataModelMetaData(getJdbcConnection());
          }
        }
      } finally {
        try {
          if (resultSet != null) {
            resultSet.close();
          }
          currentStatement.close();
        } finally {
          currentStatement = null;
        }
      }

      return new InterpreterResult(Code.SUCCESS, msg.toString());

    } catch (SQLException ex) {
      logger.error("Cannot run " + sql, ex);
      return new InterpreterResult(Code.ERROR, ex.getMessage());
    }
  }

  /**
   * For %table response replace Tab and Newline characters from the content.
   */
  private String replaceReservedChars(boolean isTableResponseType, String str) {
    if (str == null) {
      return EMPTY_COLUMN_VALUE;
    }
    return (!isTableResponseType) ? str : str.replace(TAB, WHITESPACE).replace(NEWLINE, WHITESPACE);
  }

  @Override
  public InterpreterResult interpret(String cmd, InterpreterContext contextInterpreter) {
    logger.info("Run SQL command '{}'", cmd);
    return executeSql(cmd);
  }

  @Override
  public void cancel(InterpreterContext context) {

    logger.info("Cancel current query statement.");

    if (currentStatement != null) {
      try {
        currentStatement.cancel();
      } catch (SQLException ex) {
      } finally {
        currentStatement = null;
      }
    }
  }

  @Override
  public FormType getFormType() {
    return FormType.SIMPLE;
  }

  @Override
  public int getProgress(InterpreterContext context) {
    return 0;
  }

  @Override
  public Scheduler getScheduler() {
    return SchedulerFactory.singleton().createOrGetFIFOScheduler(
        MySqlInterpreter.class.getName() + this.hashCode());
  }

  @Override
  public List<String> completion(String buf, int cursor) {

    List<CharSequence> candidates = new ArrayList<>();
    if (sqlCompleter != null && sqlCompleter.complete(buf, cursor, candidates) >= 0) {
      return Lists.transform(candidates, sequenceToStringTransformer);
    } else {
      return NO_COMPLETION;
    }
  }

  public int getMaxResult() {
    return maxResult;
  }

  // Test only method
  protected Connection getJdbcConnection() {
    return jdbcConnection;
  }
}
