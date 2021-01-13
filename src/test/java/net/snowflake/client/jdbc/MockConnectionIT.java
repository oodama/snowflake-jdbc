package net.snowflake.client.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.snowflake.client.category.TestCategoryConnection;
import net.snowflake.client.core.*;
import net.snowflake.client.jdbc.telemetry.Telemetry;
import net.snowflake.common.core.SnowflakeDateTimeFormat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * IT test for testing the "pluggable" implementation of SnowflakeConnection, SnowflakeStatement,
 * and ResultSet. These tests will query Snowflake normally, retrieve the JSON result, and replay it
 * back using a custom implementation of these objects that simply echoes a given JSON response.
 */
@Category(TestCategoryConnection.class)
public class MockConnectionIT extends BaseJDBCTest {

  private static final String testTableName = "test_custom_conn_table";

  private static SFResultSetMetaData getRSMDFromResponse(JsonNode rootNode, SFSession sfSession)
      throws SnowflakeSQLException {

    String queryId = rootNode.path("data").path("queryId").asText();

    Map<String, Object> parameters =
        SessionUtil.getCommonParams(rootNode.path("data").path("parameters"));

    String sqlTimestampFormat =
        (String) ResultUtil.effectiveParamValue(parameters, "TIMESTAMP_OUTPUT_FORMAT");

    // Special handling of specialized formatters, use a helper function
    SnowflakeDateTimeFormat ntzFormat =
        ResultUtil.specializedFormatter(
            parameters, "timestamp_ntz", "TIMESTAMP_NTZ_OUTPUT_FORMAT", sqlTimestampFormat);

    SnowflakeDateTimeFormat ltzFormat =
        ResultUtil.specializedFormatter(
            parameters, "timestamp_ltz", "TIMESTAMP_LTZ_OUTPUT_FORMAT", sqlTimestampFormat);

    SnowflakeDateTimeFormat tzFormat =
        ResultUtil.specializedFormatter(
            parameters, "timestamp_tz", "TIMESTAMP_TZ_OUTPUT_FORMAT", sqlTimestampFormat);

    String sqlDateFormat =
        (String) ResultUtil.effectiveParamValue(parameters, "DATE_OUTPUT_FORMAT");

    SnowflakeDateTimeFormat dateFormatter =
        SnowflakeDateTimeFormat.fromSqlFormat(Objects.requireNonNull(sqlDateFormat));

    String sqlTimeFormat =
        (String) ResultUtil.effectiveParamValue(parameters, "TIME_OUTPUT_FORMAT");

    SnowflakeDateTimeFormat timeFormatter =
        SnowflakeDateTimeFormat.fromSqlFormat(Objects.requireNonNull(sqlTimeFormat));

    List<SnowflakeColumnMetadata> resultColumnMetadata = new ArrayList<>();
    int columnCount = rootNode.path("data").path("rowtype").size();
    for (int i = 0; i < columnCount; i++) {
      JsonNode colNode = rootNode.path("data").path("rowtype").path(i);

      SnowflakeColumnMetadata columnMetadata =
          SnowflakeUtil.extractColumnMetadata(
              colNode, sfSession.isJdbcTreatDecimalAsInt(), sfSession);

      resultColumnMetadata.add(columnMetadata);
    }

    return new SFResultSetMetaData(
        resultColumnMetadata,
        queryId,
        sfSession,
        false,
        ntzFormat,
        ltzFormat,
        tzFormat,
        dateFormatter,
        timeFormatter);
  }

  private static ObjectNode getJsonFromDataType(DataType dataType) {

    ObjectMapper mapper = new ObjectMapper();
    ObjectNode type = mapper.createObjectNode();

    if (dataType == DataType.INT) {
      type.put("name", "someIntColumn");
      type.put("database", "");
      type.put("schema", "");
      type.put("table", "");
      type.put("scale", 0);
      type.put("precision", 18);
      type.put("type", "fixed");
      type.put("length", (Integer) null);
      type.put("byteLength", (Integer) null);
      type.put("nullable", true);
      type.put("collation", (String) null);
    } else if (dataType == DataType.STRING) {
      type.put("name", "someStringColumn");
      type.put("database", "");
      type.put("schema", "");
      type.put("table", "");
      type.put("scale", (Integer) null);
      type.put("precision", (Integer) null);
      type.put("length", 16777216);
      type.put("type", "text");
      type.put("byteLength", 16777216);
      type.put("nullable", true);
      type.put("collation", (String) null);
    }

    return type;
  }

  public Connection initStandardConnection() throws SQLException {
    Connection conn = BaseJDBCTest.getConnection(BaseJDBCTest.DONT_INJECT_SOCKET_TIMEOUT);
    conn.createStatement().execute("alter session set jdbc_query_result_format = json");
    return conn;
  }

  public Connection initMockConnection(ConnectionImplementationFactory implementation)
      throws SQLException {
    return new SnowflakeConnectionV1(implementation);
  }

  @Before
  public void setUp() throws SQLException {
    Connection con = initStandardConnection();

    con.createStatement().execute("alter session set jdbc_query_result_format = json");

    // Create a table of two rows containing a varchar column and an int column
    con.createStatement()
        .execute("create or replace table " + testTableName + " (colA string, colB int)");
    con.createStatement().execute("insert into " + testTableName + " values('rowOne', 1)");
    con.createStatement().execute("insert into " + testTableName + " values('rowTwo', 2)");

    con.close();
  }

  /**
   * Test running some queries, and plugging in the raw JSON response from those queries into a
   * MockConnection. The results retrieved from the MockConnection should be the same as those from
   * the original connection.
   */
  @Test
  public void testMockResponse() throws SQLException, JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode rawResponse =
        mapper.readTree(
            "{\n"
                + "   \"data\":{\n"
                + "      \"parameters\":[\n"
                + "         {\n"
                + "            \"name\":\"TIMESTAMP_OUTPUT_FORMAT\",\n"
                + "            \"value\":\"DY, DD MON YYYY HH24:MI:SS TZHTZM\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_PREFETCH_THREADS\",\n"
                + "            \"value\":4\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"TIME_OUTPUT_FORMAT\",\n"
                + "            \"value\":\"HH24:MI:SS\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_USE_SESSION_TIMEZONE\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_TREAT_TIMESTAMP_NTZ_AS_UTC\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_EXECUTE_RETURN_COUNT_FOR_DML\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"TIMESTAMP_TZ_OUTPUT_FORMAT\",\n"
                + "            \"value\":\"\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_RESULT_CHUNK_SIZE\",\n"
                + "            \"value\":48\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_SESSION_KEEP_ALIVE\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_RS_COLUMN_CASE_INSENSITIVE\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_CONSERVATIVE_MEMORY_ADJUST_STEP\",\n"
                + "            \"value\":64\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_OUT_OF_BAND_TELEMETRY_ENABLED\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_METADATA_USE_SESSION_DATABASE\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_ENABLE_COMBINED_DESCRIBE\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_RESULT_PREFETCH_THREADS\",\n"
                + "            \"value\":1\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"TIMESTAMP_NTZ_OUTPUT_FORMAT\",\n"
                + "            \"value\":\"\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_TREAT_DECIMAL_AS_INT\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_METADATA_REQUEST_USE_CONNECTION_CTX\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_HONOR_CLIENT_TZ_FOR_TIMESTAMP_NTZ\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_MEMORY_LIMIT\",\n"
                + "            \"value\":1536\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_TIMESTAMP_TYPE_MAPPING\",\n"
                + "            \"value\":\"TIMESTAMP_LTZ\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_EFFICIENT_CHUNK_STORAGE\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"TIMEZONE\",\n"
                + "            \"value\":\"America/Los_Angeles\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"SERVICE_NAME\",\n"
                + "            \"value\":\"\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_RESULT_PREFETCH_SLOTS\",\n"
                + "            \"value\":2\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_TELEMETRY_ENABLED\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_USE_V1_QUERY_API\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_DISABLE_INCIDENTS\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_RESULT_COLUMN_CASE_INSENSITIVE\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_ENABLE_CONSERVATIVE_MEMORY_USAGE\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"BINARY_OUTPUT_FORMAT\",\n"
                + "            \"value\":\"HEX\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_ENABLE_LOG_INFO_STATEMENT_PARAMETERS\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_CONSENT_CACHE_ID_TOKEN\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_FORMAT_DATE_WITH_TIMEZONE\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"DATE_OUTPUT_FORMAT\",\n"
                + "            \"value\":\"YYYY-MM-DD\"\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_FORCE_PROTECT_ID_TOKEN\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_STAGE_ARRAY_BINDING_THRESHOLD\",\n"
                + "            \"value\":65280\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"JDBC_USE_JSON_PARSER\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_SESSION_KEEP_ALIVE_HEARTBEAT_FREQUENCY\",\n"
                + "            \"value\":3600\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"CLIENT_SESSION_CLONE\",\n"
                + "            \"value\":false\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"AUTOCOMMIT\",\n"
                + "            \"value\":true\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"TIMESTAMP_LTZ_OUTPUT_FORMAT\",\n"
                + "            \"value\":\"\"\n"
                + "         }\n"
                + "      ],\n"
                + "      \"rowtype\":[\n"
                + "         {\n"
                + "            \"name\":\"COLA\",\n"
                + "            \"database\":\"TESTDB\",\n"
                + "            \"schema\":\"TESTSCHEMA\",\n"
                + "            \"table\":\"TEST_CUSTOM_CONN_TABLE\",\n"
                + "            \"scale\":null,\n"
                + "            \"precision\":null,\n"
                + "            \"length\":16777216,\n"
                + "            \"type\":\"text\",\n"
                + "            \"byteLength\":16777216,\n"
                + "            \"nullable\":true,\n"
                + "            \"collation\":null\n"
                + "         },\n"
                + "         {\n"
                + "            \"name\":\"COLB\",\n"
                + "            \"database\":\"TESTDB\",\n"
                + "            \"schema\":\"TESTSCHEMA\",\n"
                + "            \"table\":\"TEST_CUSTOM_CONN_TABLE\",\n"
                + "            \"scale\":0,\n"
                + "            \"precision\":38,\n"
                + "            \"length\":null,\n"
                + "            \"type\":\"fixed\",\n"
                + "            \"byteLength\":null,\n"
                + "            \"nullable\":true,\n"
                + "            \"collation\":null\n"
                + "         }\n"
                + "      ],\n"
                + "      \"rowset\":[\n"
                + "         [\n"
                + "            \"rowOne\",\n"
                + "            \"1\"\n"
                + "         ],\n"
                + "         [\n"
                + "            \"rowTwo\",\n"
                + "            \"2\"\n"
                + "         ]\n"
                + "      ],\n"
                + "      \"total\":2,\n"
                + "      \"returned\":2,\n"
                + "      \"queryId\":\"0199922f-015a-7715-0000-0014000123ca\",\n"
                + "      \"databaseProvider\":null,\n"
                + "      \"finalDatabaseName\":\"TESTDB\",\n"
                + "      \"finalSchemaName\":\"TESTSCHEMA\",\n"
                + "      \"finalWarehouseName\":\"DEV\",\n"
                + "      \"finalRoleName\":\"SYSADMIN\",\n"
                + "      \"numberOfBinds\":0,\n"
                + "      \"arrayBindSupported\":false,\n"
                + "      \"statementTypeId\":4096,\n"
                + "      \"version\":1,\n"
                + "      \"sendResultTime\":1610498856446,\n"
                + "      \"queryResultFormat\":\"json\"\n"
                + "   },\n"
                + "   \"code\":null,\n"
                + "   \"message\":null,\n"
                + "   \"success\":true\n"
                + "}");

    MockSnowflakeConnectionImpl mockImpl = new MockSnowflakeConnectionImpl(rawResponse);
    Connection mockConnection = initMockConnection(mockImpl);

    ResultSet fakeResultSet =
        mockConnection.prepareStatement("select count(*) from " + testTableName).executeQuery();
    fakeResultSet.next();
    String val = fakeResultSet.getString(1);
    assertEquals("colA value from the mock connection was not what was expected", "rowOne", val);

    mockConnection.close();
  }

  /**
   * Fabricates fake JSON responses with some int data, and asserts the correct results via
   * retrieval from MockJsonResultSet
   */
  @Test
  public void testMockedResponseWithRows() throws SQLException {
    // Test with some ints
    List<DataType> dataTypes = Arrays.asList(DataType.INT, DataType.INT, DataType.INT);
    List<Object> row1 = Arrays.asList(1, 2, null);
    List<Object> row2 = Arrays.asList(4, null, 6);
    List<List<Object>> rowsToTest = Arrays.asList(row1, row2);

    JsonNode responseWithRows = createDummyResponseWithRows(rowsToTest, dataTypes);

    MockSnowflakeConnectionImpl mockImpl = new MockSnowflakeConnectionImpl(responseWithRows);
    Connection mockConnection = initMockConnection(mockImpl);

    ResultSet fakeResultSet =
        mockConnection.prepareStatement("select * from fakeTable").executeQuery();
    compareResultSets(fakeResultSet, rowsToTest, dataTypes);

    mockConnection.close();

    // Now test with some strings
    dataTypes = Arrays.asList(DataType.STRING, DataType.STRING);
    row1 = Arrays.asList("hi", "bye");
    row2 = Arrays.asList(null, "snowflake");
    List<Object> row3 = Arrays.asList("is", "great");
    rowsToTest = Arrays.asList(row1, row2, row3);

    responseWithRows = createDummyResponseWithRows(rowsToTest, dataTypes);

    mockImpl = new MockSnowflakeConnectionImpl(responseWithRows);
    mockConnection = initMockConnection(mockImpl);

    fakeResultSet = mockConnection.prepareStatement("select * from fakeTable").executeQuery();
    compareResultSets(fakeResultSet, rowsToTest, dataTypes);

    mockConnection.close();

    // Mixed data
    dataTypes = Arrays.asList(DataType.STRING, DataType.INT);
    row1 = Arrays.asList("foo", 2);
    row2 = Arrays.asList("bar", 4);
    row3 = Arrays.asList("baz", null);
    rowsToTest = Arrays.asList(row1, row2, row3);

    responseWithRows = createDummyResponseWithRows(rowsToTest, dataTypes);

    mockImpl = new MockSnowflakeConnectionImpl(responseWithRows);
    mockConnection = initMockConnection(mockImpl);

    fakeResultSet = mockConnection.prepareStatement("select * from fakeTable").executeQuery();
    compareResultSets(fakeResultSet, rowsToTest, dataTypes);

    mockConnection.close();
  }

  @After
  public void tearDown() throws SQLException {
    Connection con = initStandardConnection();
    con.createStatement().execute("drop table if exists " + testTableName);
    con.close();
  }

  private JsonNode createDummyResponseWithRows(List<List<Object>> rows, List<DataType> dataTypes) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode rootNode = mapper.createObjectNode();
    ObjectNode dataNode = rootNode.putObject("data");

    createResultSetMetadataResponse(dataNode, dataTypes);
    createRowsetJson(dataNode, rows, dataTypes);

    return rootNode;
  }

  /**
   * Creates the metadata portion of the response, i.e.,
   *
   * <p>parameters: [time format, date format, timestamp format, timestamp_ltz format, timestamp_tz
   * format, timestamp_ntz format, ] queryId rowType
   *
   * @param dataNode ObjectNode representing the "data" portion of the JSON response
   * @param dataTypes datatypes of the rows used in the generated response
   */
  private void createResultSetMetadataResponse(ObjectNode dataNode, List<DataType> dataTypes) {
    ArrayNode parameters = dataNode.putArray("parameters");

    parameters.add(createParameterJson("TIME_OUTPUT_FORMAT", "HH24:MI:SS"));
    parameters.add(createParameterJson("DATE_OUTPUT_FORMAT", "YYYY-MM-DD"));
    parameters.add(
        createParameterJson("TIMESTAMP_OUTPUT_FORMAT", "DY, DD MON YYYY HH24:MI:SS TZHTZM"));
    parameters.add(createParameterJson("TIMESTAMP_LTZ_OUTPUT_FORMAT", ""));
    parameters.add(createParameterJson("TIMESTAMP_NTZ_OUTPUT_FORMAT", ""));
    parameters.add(createParameterJson("TIMESTAMP_TZ_OUTPUT_FORMAT", ""));

    dataNode.put("queryId", "81998ae8-01e5-e08d-0000-10140001201a");

    ArrayNode rowType = dataNode.putArray("rowtype");

    for (DataType type : dataTypes) {
      rowType.add(getJsonFromDataType(type));
    }
  }

  /**
   * Creates a parameter key-value pairing in JSON, with name and value
   *
   * @return an ObjectNode with the parameter name and value
   */
  private ObjectNode createParameterJson(String parameterName, String parameterValue) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode parameterObject = mapper.createObjectNode();
    parameterObject.put("name", parameterName);
    parameterObject.put("value", parameterValue);

    return parameterObject;
  }

  /**
   * Adds the data portion of the mocked response JSON
   *
   * @param dataNode The ObjectNode representing the "data" portion of the JSON response
   * @param rows The rows to add to the rowset.
   * @param dataTypes datatypes of the provided set of rows
   */
  private void createRowsetJson(
      ObjectNode dataNode, List<List<Object>> rows, List<DataType> dataTypes) {
    ArrayNode rowsetNode = dataNode.putArray("rowset");

    if (rows == null || rows.isEmpty()) {
      return;
    }

    for (List<Object> row : rows) {
      Iterator<Object> rowData = row.iterator();
      ArrayNode rowJson = rowsetNode.addArray();
      for (DataType type : dataTypes) {
        if (type == DataType.INT) {
          rowJson.add((Integer) rowData.next());
        } else if (type == DataType.STRING) {
          rowJson.add((String) rowData.next());
        }
      }
    }
  }

  /**
   * Utility method to check that the integer result set is equivalent to the given list of list of
   * ints
   */
  private void compareResultSets(
      ResultSet resultSet, List<List<Object>> expectedRows, List<DataType> dataTypes)
      throws SQLException {
    if (expectedRows == null || expectedRows.size() == 0) {
      assertFalse(resultSet.next());
      return;
    }

    int numRows = expectedRows.size();

    int resultSetRows = 0;

    Iterator<List<Object>> rowIterator = expectedRows.iterator();

    while (resultSet.next() && rowIterator.hasNext()) {
      List<Object> expectedRow = rowIterator.next();
      int columnIdx = 0;
      for (DataType type : dataTypes) {
        Object expected = expectedRow.get(columnIdx);
        columnIdx++;
        if (type == DataType.INT) {
          if (expected == null) {
            expected = 0;
          }
          int actual = resultSet.getInt(columnIdx);
          assertEquals(expected, actual);
        } else if (type == DataType.STRING) {
          String actual = resultSet.getString(columnIdx);
          assertEquals(expected, actual);
        }
      }

      resultSetRows++;
    }

    // If the result set has more rows than expected, finish the count
    while (resultSet.next()) {
      resultSetRows++;
    }

    assertEquals("row-count was not what was expected", numRows, resultSetRows);
  }

  // DataTypes supported with mock responses in test:
  // Currently only String and Integer are supported
  private enum DataType {
    INT,
    STRING
  }

  private static class MockedSFStatement implements SFStatement {
    JsonNode mockedResponse;
    MockSnowflakeSession sfSession;

    MockedSFStatement(JsonNode mockedResponse, MockSnowflakeSession session) {
      this.mockedResponse = mockedResponse;
      this.sfSession = session;
    }

    @Override
    public void addProperty(String propertyName, Object propertyValue) {}

    @Override
    public SFStatementMetaData describe(String sql) {
      return null;
    }

    @Override
    public Object executeHelper(
        String sql,
        String mediaType,
        Map<String, ParameterBindingDTO> bindValues,
        boolean describeOnly,
        boolean internal,
        boolean asyncExec) {
      return null;
    }

    @Override
    public int getConservativePrefetchThreads() {
      return 0;
    }

    @Override
    public long getConservativeMemoryLimit() {
      return 0;
    }

    @Override
    public SFBaseResultSet execute(
        String sql,
        boolean asyncExec,
        Map<String, ParameterBindingDTO> parametersBinding,
        CallingMethod caller)
        throws SQLException {
      return new MockJsonResultSet(mockedResponse, sfSession);
    }

    @Override
    public void close() {}

    @Override
    public void cancel() {}

    @Override
    public void executeSetProperty(String sql) {}

    @Override
    public SFSession getSession() {
      return sfSession;
    }

    @Override
    public boolean getMoreResults(int current) {
      return false;
    }

    @Override
    public SFBaseResultSet getResultSet() {
      return null;
    }

    @Override
    public boolean hasChildren() {
      return false;
    }
  }

  private static class MockJsonResultSet extends SFJsonResultSet {

    JsonNode resultJson;
    int currentRowIdx = -1;
    int rowCount;

    public MockJsonResultSet(JsonNode mockedJsonResponse, MockSnowflakeSession sfSession)
        throws SnowflakeSQLException {
      setSession(sfSession);
      this.resultJson = mockedJsonResponse.path("data").path("rowset");
      this.resultSetMetaData = MockConnectionIT.getRSMDFromResponse(mockedJsonResponse, session);
      this.rowCount = resultJson.size();
    }

    @Override
    public boolean next() {
      currentRowIdx++;
      return currentRowIdx < rowCount;
    }

    @Override
    protected Object getObjectInternal(int columnIndex) {
      return JsonResultChunk.extractCell(resultJson, currentRowIdx, columnIndex - 1);
    }

    @Override
    public boolean isLast() {
      return (currentRowIdx + 1) == rowCount;
    }

    @Override
    public boolean isAfterLast() {
      return (currentRowIdx >= rowCount);
    }

    @Override
    public SFStatementType getStatementType() {
      return null;
    }

    @Override
    public void setStatementType(SFStatementType statementType) {}

    @Override
    public String getQueryId() {
      return null;
    }
  }

  private static class MockSnowflakeSession implements SFSession {

    @Override
    public boolean isSafeToClose() {
      return false;
    }

    @Override
    public QueryStatus getQueryStatus(String queryID) {
      return null;
    }

    @Override
    public void addProperty(SFSessionProperty sfSessionProperty, Object propertyValue) {}

    @Override
    public void addProperty(String propertyName, Object propertyValue) {}

    @Override
    public boolean containProperty(String key) {
      return false;
    }

    @Override
    public boolean isStringQuoted() {
      return false;
    }

    @Override
    public boolean isJdbcTreatDecimalAsInt() {
      return false;
    }

    @Override
    public void open() {}

    @Override
    public List<DriverPropertyInfo> checkProperties() {
      return null;
    }

    @Override
    public String getDatabaseVersion() {
      return null;
    }

    @Override
    public int getDatabaseMajorVersion() {
      return 0;
    }

    @Override
    public int getDatabaseMinorVersion() {
      return 0;
    }

    @Override
    public String getSessionId() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public Properties getClientInfo() {
      return null;
    }

    @Override
    public String getClientInfo(String name) {
      return null;
    }

    @Override
    public void setSFSessionProperty(String propertyName, boolean propertyValue) {}

    @Override
    public Object getSFSessionProperty(String propertyName) {
      return null;
    }

    @Override
    public boolean isClosed() {
      return false;
    }

    @Override
    public boolean getAutoCommit() {
      return false;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {}

    @Override
    public String getDatabase() {
      return null;
    }

    @Override
    public void setDatabase(String database) {}

    @Override
    public String getSchema() {
      return null;
    }

    @Override
    public void setSchema(String schema) {}

    @Override
    public String getRole() {
      return null;
    }

    @Override
    public void setRole(String role) {}

    @Override
    public String getUser() {
      return null;
    }

    @Override
    public String getUrl() {
      return null;
    }

    @Override
    public String getWarehouse() {
      return null;
    }

    @Override
    public void setWarehouse(String warehouse) {}

    @Override
    public Telemetry getTelemetryClient() {
      return null;
    }

    @Override
    public boolean getMetadataRequestUseConnectionCtx() {
      return false;
    }

    @Override
    public boolean getMetadataRequestUseSessionDatabase() {
      return false;
    }

    @Override
    public boolean getPreparedStatementLogging() {
      return false;
    }

    @Override
    public List<SFException> getSqlWarnings() {
      return null;
    }

    @Override
    public void clearSqlWarnings() {}

    @Override
    public void setInjectFileUploadFailure(String fileToFail) {}

    @Override
    public void setInjectedDelay(int delay) {}

    @Override
    public boolean isSfSQLMode() {
      return false;
    }

    @Override
    public void setSfSQLMode(boolean booleanV) {}

    @Override
    public boolean isResultColumnCaseInsensitive() {
      return false;
    }

    @Override
    public String getServerUrl() {
      return null;
    }

    @Override
    public String getSessionToken() {
      return null;
    }

    @Override
    public String getServiceName() {
      return null;
    }

    @Override
    public String getIdToken() {
      return null;
    }

    @Override
    public SnowflakeType getTimestampMappedType() {
      return null;
    }
  }

  private static class MockSnowflakeConnectionImpl implements ConnectionImplementationFactory {

    JsonNode jsonResponse;
    MockSnowflakeSession session;

    public MockSnowflakeConnectionImpl(JsonNode jsonResponse) {
      this.jsonResponse = jsonResponse;
      this.session = new MockSnowflakeSession();
    }

    @Override
    public SFSession getSFSession() {
      return session;
    }

    @Override
    public SFStatement createSFStatement() {
      return new MockedSFStatement(jsonResponse, session);
    }

    @Override
    public SnowflakeFileTransferAgent getFileTransferAgent(String command, SFStatement statement) {
      return null;
    }
  }
}