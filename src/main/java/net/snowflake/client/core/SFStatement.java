/*
 * Copyright (c) 2012-2017 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.core;

import com.fasterxml.jackson.databind.JsonNode;
import net.snowflake.client.core.BasicEvent.QueryState;
import net.snowflake.client.jdbc.ErrorCode;
import net.snowflake.client.jdbc.SnowflakeFileTransferAgent;
import net.snowflake.client.jdbc.SnowflakeSQLException;
import net.snowflake.common.core.SqlState;
import java.util.List;

import org.apache.http.client.methods.HttpRequestBase;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.snowflake.client.log.SFLogger;
import net.snowflake.client.log.SFLoggerFactory;

/**
 * Snowflake statement
 *
 * @author jhuang
 */
public class SFStatement
{
  static final SFLogger logger = SFLoggerFactory.getLogger(SFStatement.class);

  private SFSession session;

  /**
   * map of bind values for batch query executions
   *
   * bind variable name ->
   *                      value -> list of bind variable values or value
   *                      type -> bind variable type
   */
  Map<String, Map<String, Object>> parameterBindings = new HashMap<String, Map<String, Object>>();

  private SFBaseResultSet resultSet = null;

  private SFBaseResultSet currentResultSet = null;

  private HttpRequestBase httpRequest;

  private int updateCount = -1;

  private Boolean isClosed = false;

  private Integer sequenceId = -1;

  private String requestId = null;

  private String sqlText = null;

  private AtomicBoolean canceling = new AtomicBoolean(false);

  // timeout in seconds
  private int queryTimeout = 0;

  private boolean isFileTransfer = false;

  private SnowflakeFileTransferAgent transferAgent = null;

  // statement level parameters
  Map<String, Object> statementParametersMap =  new HashMap<String, Object>();

  final private static int MAX_STATEMENT_PARAMETERS = 1000;

  private String sql = null;

  /**
   * Add a statement parameter
   *
   * Make sure a property is not added more than once and the number of
   * properties does not exceed limit.
   *
   * @param propertyName property name
   * @param propertyValue property value
   * @throws SFException if too many parameters for a statement
   */
  public void addProperty(String propertyName, Object propertyValue)
      throws SFException
  {
    statementParametersMap.put(propertyName, propertyValue);

    // for query timeout, we implement it on client side for now
    if ("query_timeout".equalsIgnoreCase(propertyName))
    {
      queryTimeout = (Integer) propertyValue;
    }

    // check if the number of session properties exceed limit
    if (statementParametersMap.size() > MAX_STATEMENT_PARAMETERS)
    {
      throw new SFException(
              ErrorCode.TOO_MANY_STATEMENT_PARAMETERS, MAX_STATEMENT_PARAMETERS);
    }
  }

  public SFStatement(SFSession session)
  {
    this(session, null);

    logger.debug(" public SFStatement(SFSession session)");
  }


  public SFStatement(SFSession session, String sql)
  {
    logger.debug(" public SFStatement(SFSession session, String sql)");

    this.session = session;
    this.sql = sql;
  }

  /**
   * Sanity check query text
   * @param sql
   * @throws java.sql.SQLException
   */
  void sanityCheckQuery(String sql) throws SQLException
  {
    if (sql == null || sql.isEmpty())
    {
      throw new SnowflakeSQLException(SqlState.SQL_STATEMENT_NOT_YET_COMPLETE,
          ErrorCode.INVALID_SQL.getMessageCode(), sql);

    }
  }

  /**
   * Execute SQL query
   *
   * @param sql sql statement
   * @return ResultSet
   * @throws java.sql.SQLException if failed to execute the query
   * @throws SFException if failed to execute the query
   */
  protected SFBaseResultSet executeQuery(String sql) throws SQLException,
      SFException
  {
    return executeQuery(sql, false);
  }

  /**
   * Execute SQL query with an option for describe only
   *
   * @param sql sql statement
   * @param describeOnly true if describe only
   * @return query result set
   * @throws SQLException if connection is already closed
   * @throws SFException if result set is null
   */
  protected SFBaseResultSet executeQuery(String sql, boolean describeOnly)
      throws SQLException, SFException
  {
    sanityCheckQuery(sql);

    String trimmedSql = sql.trim();

    // snowflake specific client side commands
    if (isFileTransfer(trimmedSql))
    {
      // PUT/GET command
      logger.debug( "Executing file transfer locally: {}", sql);

      return executeFileTransfer(sql);
    }

    return executeQueryInternal(sql, parameterBindings, describeOnly);
  }

  /**
   * Describe a statement
   *
   * @param sql statement
   * @return metadata of statement including result set metadata and binding information
   * @throws SQLException if connection is already closed
   * @throws SFException if result set is null
   */
  public SFStatementMetaData describe(String sql) throws SFException, SQLException
  {
    SFBaseResultSet baseResultSet = executeQuery(sql, true);

    return new SFStatementMetaData(baseResultSet.getMetaData(),
        baseResultSet.getNumberOfBinds());
  }

  /**
   * Describe the statement
   *
   * @return metadata of statement including result set metadata and binding information
   * @throws SQLException if connection is already closed
   * @throws SFException if result set is null
   */
  public SFStatementMetaData describe() throws SFException, SQLException
  {
    return describe(sql);
  }

  /**
   * Internal method for executing a query with bindings accepted.
   * <p>
   * @param sql sql statement
   * @param parameterBindings binding information
   * @param describeOnly true if just showing result set metadata
   * @return snowflake query result set
   * @throws SQLException if connection is already closed
   * @throws SFException if result set is null
   */
  protected SFBaseResultSet executeQueryInternal(String sql,
                                           Map<String, Map<String, Object>>
                                               parameterBindings,
                                           boolean describeOnly)
      throws SQLException, SFException
  {
    resetState();

    if (logger.isDebugEnabled())
    {
      logger.debug( "Time: {} executeQuery: {}",
                 System.currentTimeMillis(), sql);
    }

    if (session.isClosed())
    {
      throw new SQLException("connection is closed");
    }

    Object result = executeHelper(sql, "application/snowflake",
                                  parameterBindings, describeOnly);

    if (result == null)
    {
      throw new SnowflakeSQLException(SqlState.INTERNAL_ERROR,
                                      ErrorCode.INTERNAL_ERROR.getMessageCode(),
                                      "got null result");
    }

    boolean sortResult = false;

    /*
     * we sort the result if the connection is in sorting mode
     */
    Object sortProperty = session.getSFSessionProperty("sort");

    sortResult = sortProperty != null && (Boolean) sortProperty;

    if (logger.isDebugEnabled())
    {
      logger.debug( "Time: {} Creating result set",
                 System.currentTimeMillis());
    }

    try
    {
      resultSet = new SFResultSet((JsonNode) result, this, sortResult);
    }
    catch (SnowflakeSQLException | OutOfMemoryError ex)
    {
      // snow-24428: no need to generate incident for exceptions we generate
      // snow-29403: or client OOM
      throw ex;
    }
    catch (Throwable ex)
    {
      // SNOW-22813 log exception
      logger.error("Exception creating result", ex);

      throw IncidentUtil.generateIncidentWithException(
          session,
          null,
          null, ex,
          ErrorCode.INTERNAL_ERROR,
          "exception creating result");
    }
    if (logger.isDebugEnabled())
    {
      logger.debug( "Time: {} Done creating result set",
                 System.currentTimeMillis());
    }

    return resultSet;
  }

  /**
   * Set a time bomb to cancel the outstanding query when timeout is reached.
   * @param executor object to execute statement cancel request
   */
  private void setTimeBomb(ScheduledExecutorService executor)
  {
    class TimeBombTask implements Callable<Void>
    {

      final SFStatement statement;

      TimeBombTask(SFStatement statement)
      {
        this.statement = statement;
      }

      @Override
      public Void call() throws SQLException
      {
        try
        {
          statement.cancel();
        }
        catch (SFException ex)
        {
          throw new SnowflakeSQLException(ex, ex.getSqlState(),
              ex.getVendorCode(), ex.getParams());
        }
        return null;
      }
    }

    executor.schedule(new TimeBombTask(this), this.queryTimeout,
        TimeUnit.SECONDS);
  }

  /**
   * A helper method to build URL and submit the SQL to snowflake for exec
   *
   * @param sql sql statement
   * @param mediaType media type
   * @param bindValues map of binding values
   * @param describeOnly whether only show the result set metadata
   * @return query result set
   * @throws SFException if query is canceled
   * @throws SnowflakeSQLException if query is already running
   */
  public
  Object executeHelper(String sql, String mediaType,
                                 Map<String, Map<String, Object>> bindValues,
                                 boolean describeOnly)
      throws SnowflakeSQLException, SFException
  {
    ScheduledExecutorService executor = null;

    try
    {
      synchronized (this)
      {
        if (isClosed)
        {
          throw new SFException(ErrorCode.STATEMENT_CLOSED);
        }

        // initialize a sequence id if not closed or not for aborting
        if (canceling.get())
        {
          // nothing to do if canceled
          throw new SFException(ErrorCode.QUERY_CANCELED);
        }

        if (this.requestId != null)
        {
          throw new SnowflakeSQLException(SqlState.FEATURE_NOT_SUPPORTED,
              ErrorCode.STATEMENT_ALREADY_RUNNING_QUERY.getMessageCode());
        }

        this.requestId = UUID.randomUUID().toString();
        this.sequenceId = session.getAndIncrementSequenceId();

        this.sqlText = sql;
      }

      EventUtil.triggerStateTransition(BasicEvent.QueryState.QUERY_STARTED,
          String.format(QueryState.QUERY_STARTED.getArgString(), requestId));

      StmtUtil.StmtInput stmtInput = new StmtUtil.StmtInput();
      stmtInput.setSql(sql)
          .setMediaType(mediaType)
          .setBindValues(bindValues)
          .setDescribeOnly(describeOnly)
          .setServerUrl(session.getServerUrl())
          .setRequestId(requestId)
          .setSequenceId(sequenceId)
          .setParametersMap(statementParametersMap)
          .setSessionToken(session.getSessionToken())
          .setHttpClient(session.getHttpClient())
          .setNetworkTimeoutInMillis(session.getNetworkTimeoutInMilli())
          .setInjectSocketTimeout(session.getInjectSocketTimeout())
          .setInjectClientPause(session.getInjectClientPause())
          .setCanceling(canceling)
          .setRetry(false);

      if (canceling.get())
      {
        logger.debug( "Query cancelled");

        throw new SFException(ErrorCode.QUERY_CANCELED);
      }

      // if timeout is set, start a thread to cancel the request after timeout
      // reached.
      if (this.queryTimeout > 0)
      {
        executor = Executors.newScheduledThreadPool(1);
        setTimeBomb(executor);
      }

      StmtUtil.StmtOutput stmtOutput = null;
      boolean sessionRenewed = false;

      do
      {
        sessionRenewed = false;
        try
        {
          stmtOutput = StmtUtil.execute(stmtInput);
          break;
        }
        catch (SnowflakeSQLException ex)
        {
          if (ex.getErrorCode() == Constants.SESSION_EXPIRED_GS_CODE)
          {
            // renew the session
            session.renewSession(stmtInput.sessionToken);
            // SNOW-18822: reset session token for the statement
            stmtInput.setSessionToken(session.getSessionToken());
            stmtInput.setRetry(true);

            sessionRenewed = true;

            logger.debug("Session got renewed, will retry");
          }
          else
            throw ex;
        }
      }
      while(sessionRenewed && !canceling.get());

      // Debugging/Testing for incidents
      if(System.getProperty("snowflake.enable_incident_test1") != null &&
         System.getProperty("snowflake.enable_incident_test1").equals("true"))
      {
        SFException sfe =
            IncidentUtil.generateIncidentWithException(session, this.requestId,
                null, ErrorCode.STATEMENT_CLOSED);

          throw sfe;
      }

      synchronized (this)
      {
        /*
         * done with the remote execution of the query. set sequenceId to -1
         * and request id to null so that we don't try to abort it upon canceling.
         */
        this.sequenceId = -1;
        this.requestId = null;
      }

      if (canceling.get())
      {
        // If we are here, this is the context for the initial query that
        // is being canceled. Raise an exception anyway here even if
        // the server fails to abort it.
        throw new SFException(ErrorCode.QUERY_CANCELED);
      }

      if (logger.isDebugEnabled())
      {
        logger.debug( "Time: {} Returning from executeHelper",
                   System.currentTimeMillis());
      }

      return stmtOutput.getResult();
    }
    catch (SFException | SnowflakeSQLException ex)
    {
      isClosed = true;
      throw ex;
    }
    finally
    {
      if (executor != null)
      {
        executor.shutdownNow();
      }
    }
  }

  /**
   * A helper method to build URL and cancel the SQL for exec
   *
   * @param sql sql statement
   * @param mediaType media type
   * @throws SnowflakeSQLException if failed to cancel the statement
   * @throws SFException if statement is already closed
   */
  protected void cancelHelper(String sql, String mediaType)
      throws SnowflakeSQLException, SFException
  {
    synchronized (this)
    {
      if (isClosed)
      {
        throw new SFException(ErrorCode.INTERNAL_ERROR,
            "statement already closed");
      }
    }

    StmtUtil.StmtInput stmtInput = new StmtUtil.StmtInput();
    stmtInput.setServerUrl(session.getServerUrl())
        .setSql(sql)
        .setMediaType(mediaType)
        .setRequestId(requestId)
        .setHttpClient(session.getHttpClient())
        .setSessionToken(session.getSessionToken());

    StmtUtil.cancel(stmtInput);

    synchronized (this)
    {
      /*
       * done with the remote execution of the query. set sequenceId to -1
       * and request id to null so that we don't try to abort it again upon
       * canceling.
       */
      this.sequenceId = -1;
      this.requestId = null;
    }
  }

  protected void releaseConnection()
  {
    logger.debug("public void releaseConnection()");

    if (httpRequest != null)
    {
      logger.debug("Release connection");
      httpRequest.releaseConnection();

      httpRequest = null;
    }
  }

  /**
   * A method to check if a sql is file upload statement with consideration for
   * potential comments in front of put keyword.
   * <p>
   * @param sql sql statement
   * @return true if the command is upload statement
   */
  protected boolean isFileTransfer(String sql)
  {
    if (sql == null)
    {
      return false;
    }

    String trimmedSql = sql.trim();

    // skip commenting prefixed with //
    while (trimmedSql.startsWith("//"))
    {
      logger.debug("skipping // comments in: \n{}", trimmedSql);

      if (trimmedSql.indexOf('\n') > 0)
      {
        trimmedSql = trimmedSql.substring(trimmedSql.indexOf('\n'));
        trimmedSql = trimmedSql.trim();
      }
      else
      {
        break;
      }

      logger.debug( "New sql after skipping // comments: \n{}",
                                trimmedSql);

    }

    // skip commenting enclosed with /* */
    while (trimmedSql.startsWith("/*"))
    {
      logger.debug( "skipping /* */ comments in: \n{}", trimmedSql);

      if (trimmedSql.indexOf("*/") > 0)
      {
        trimmedSql = trimmedSql.substring(trimmedSql.indexOf("*/") + 2);
        trimmedSql = trimmedSql.trim();
      }
      else
      {
        break;

      }
      logger.debug( "New sql after skipping /* */ comments: \n{}", trimmedSql);

    }

    return (trimmedSql.length() >= 4
            && (trimmedSql.toLowerCase().startsWith("put ")
                || trimmedSql.toLowerCase().startsWith("get ")));
  }

  /**
   * Execute sql
   *
   * @param sql sql statement
   * @return whether there is result set or not
   * @throws java.sql.SQLException if failed to execute sql
   * @throws SFException exception raised from Snowflake components
   */
  public SFBaseResultSet execute(String sql) throws SQLException, SFException
  {
    sanityCheckQuery(sql);

    session.injectedDelay();

    logger.debug("execute: {}", sql);

    String trimmedSql = sql.trim();

    if (trimmedSql.length() >= 20
        && trimmedSql.toLowerCase().startsWith(
        "set-sf-property"))
    {
      executeSetProperty(sql);
      return null;
    }
    else
    {
      return executeQuery(sql);
    }
  }

  protected SFBaseResultSet executeFileTransfer(String sql) throws SQLException,
      SFException
  {
    session.injectedDelay();

    resetState();

    logger.debug("Entering executeFileTransfer");

    isFileTransfer = true;
    transferAgent = new SnowflakeFileTransferAgent(sql, session, this);

    try
    {
      transferAgent.execute();

      logger.debug("setting result set");

      resultSet = (SFFixedViewResultSet)transferAgent.getResultSet();

      logger.debug("Number of cols: {}",
                               resultSet.getMetaData().getColumnCount());

      return resultSet;
    }
    catch (SQLException ex)
    {
      logger.debug("Exception: {}", ex.getMessage());
      throw ex;
    }
  }

  private int getQueryTimeout() throws SQLException
  {
    logger.debug("public int getQueryTimeout()");

    return this.queryTimeout;
  }

  private SFBaseResultSet getResultSet() throws SQLException
  {
    logger.debug("public ResultSet getResultSet()");

    if (currentResultSet == null)
    {
      currentResultSet = resultSet;
      resultSet = null;
    }

    return currentResultSet;
  }

  public void close() throws SQLException
  {
    logger.debug("public void close()");

    if (requestId != null)
    {
      EventUtil.triggerStateTransition(BasicEvent.QueryState.QUERY_ENDED,
          String.format(QueryState.QUERY_ENDED.getArgString(), requestId));
    }

    currentResultSet = null;
    resultSet = null;
    isClosed = true;

    if (httpRequest != null)
    {
      logger.debug("releasing connection for the http request");

      httpRequest.releaseConnection();
      httpRequest = null;
    }

    isFileTransfer = false;
    transferAgent = null;
  }

  public void cancel() throws SFException, SQLException
  {
    logger.debug("public void cancel()");

    if (canceling.get())
    {
      logger.debug("Query is already cancelled");
      return;
    }

    canceling.set(true);

    if (isFileTransfer)
    {
      if (transferAgent != null)
      {
        logger.debug("Cancel file transferring ... ");
        transferAgent.cancel();
      }
    }
    else
    {
      synchronized (this)
      {
        // the query hasn't been sent to GS yet, just mark the stmt closed
        if (requestId == null)
        {
          logger.debug("No remote query outstanding");

          return;
        }
      }

      // cancel the query on the server side if it has been issued
      cancelHelper(this.sqlText, "application/snowflake");
    }
  }

  private void resetState()
  {
    resultSet = null;
    currentResultSet = null;

    if (httpRequest != null)
    {
      httpRequest.releaseConnection();
      httpRequest = null;
    }

    isClosed = false;
    updateCount = -1;
    sequenceId = -1;
    requestId = null;
    sqlText = null;
    canceling.set(false);

    isFileTransfer = false;
    transferAgent = null;
  }

  public void executeSetProperty(final String sql)
  {
    logger.debug("setting property");

    // tokenize the sql
    String[] tokens = sql.split("\\s+");

    if (tokens.length < 2)
    {
      return;
    }

    if ("sort".equalsIgnoreCase(tokens[1]))
    {
      if (tokens.length >= 3 && "on".equalsIgnoreCase(tokens[2]))
      {
        logger.debug("setting sort on");

        this.session.setSFSessionProperty("sort", true);
      }
      else
      {
        logger.debug("setting sort off");
        this.session.setSFSessionProperty("sort", false);
      }
    }
  }

  /**
   * Bind the list of values with the given type for the parameter with the
   * given index.
   *
   * @param parameterName parameter name in the query string
   * @param values list of values bound to the parameter
   * @param sqlType Snowflake SQL type name as defined in SnowflakeType.java
   */
  public void setValues(String parameterName, List<String> values, String sqlType)
  {
    Map<String, Object> newBindingValueAndType = new HashMap<String, Object>();
    newBindingValueAndType.put("value", values);
    newBindingValueAndType.put("type", sqlType);

    parameterBindings.put(parameterName, newBindingValueAndType);
  }

  /**
   * Bind value with the given type for the parameter with the
   * given index.
   *
   * @param parameterName parameter name in the query string
   * @param value value bound to the parameter
   * @param sqlType: Snowflake SQL type name as defined in SnowflakeType.java
   */
  public void setValue(String parameterName, String value, String sqlType)
  {
    Map<String, Object> newBindingValueAndType = new HashMap<String, Object>();
    newBindingValueAndType.put("value", value);
    newBindingValueAndType.put("type", sqlType);

    parameterBindings.put(parameterName, newBindingValueAndType);
  }

  protected SFSession getSession()
  {
    return session;
  }
}
