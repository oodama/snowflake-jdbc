package net.snowflake.client.jdbc;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Properties;
import net.snowflake.client.core.SFSessionProperty;
import org.junit.Test;

public class ConnectStringParseTest {
  @Test
  public void testParseAccountName() throws SnowflakeSQLException {
    Properties info = new Properties();
    info.setProperty("username", "test");
    String jdbcConnectString = "jdbc:snowflake://abc.us-east-***REMOVED***";
    SnowflakeConnectString cstring = SnowflakeConnectString.parse(jdbcConnectString, info);
    assertThat(
        cstring.getParameters().get(SFSessionProperty.ACCOUNT.getPropertyKey().toUpperCase()),
        is("abc"));

    // Hostname should remain unchanged by default.
    jdbcConnectString = "jdbc:snowflake://abc_test.us-east-***REMOVED***";
    cstring = SnowflakeConnectString.parse(jdbcConnectString, info);
    assertThat(
        cstring.getParameters().get(SFSessionProperty.ACCOUNT.getPropertyKey().toUpperCase()),
        is("abc_test"));
    assertThat(cstring.getHost(), is("abc_test.us-east-***REMOVED***"));

    jdbcConnectString = "jdbc:snowflake://abc-test.us-east-***REMOVED***";
    cstring = SnowflakeConnectString.parse(jdbcConnectString, info);
    assertThat(
        cstring.getParameters().get(SFSessionProperty.ACCOUNT.getPropertyKey().toUpperCase()),
        is("abc-test"));
    assertThat(cstring.getHost(), is("abc-test.us-east-***REMOVED***"));

    //  Host name should be updated if the parameter is set and it has underscores in it.
    jdbcConnectString = "jdbc:snowflake://abc_test.us-east-***REMOVED***";
    info.setProperty(SFSessionProperty.ALLOW_UNDERSCORES_IN_HOST.getPropertyKey(), "false");
    cstring = SnowflakeConnectString.parse(jdbcConnectString, info);
    assertThat(
        cstring.getParameters().get(SFSessionProperty.ACCOUNT.getPropertyKey().toUpperCase()),
        is("abc_test"));
    assertThat(cstring.getHost(), is("abc-test.us-east-***REMOVED***"));

    // No change if hostname does not have underscores in it.
    jdbcConnectString = "jdbc:snowflake://abc-test.us-east-***REMOVED***";
    cstring = SnowflakeConnectString.parse(jdbcConnectString, info);
    assertThat(
        cstring.getParameters().get(SFSessionProperty.ACCOUNT.getPropertyKey().toUpperCase()),
        is("abc-test"));
    assertThat(cstring.getHost(), is("abc-test.us-east-***REMOVED***"));

    // The host URL should be updated whether the ACCOUNT property is set or not
    info.setProperty("ACCOUNT", "abc_test");
    cstring = SnowflakeConnectString.parse(jdbcConnectString, info);
    assertThat(
        cstring.getParameters().get(SFSessionProperty.ACCOUNT.getPropertyKey().toUpperCase()),
        is("abc_test"));
    assertThat(cstring.getHost(), is("abc-test.us-east-***REMOVED***"));
  }
}