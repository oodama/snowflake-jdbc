/*
 * Copyright (c) 2012-2019 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.core;

import static org.hamcrest.MatcherAssert.assertThat;

import com.fasterxml.jackson.databind.node.BooleanNode;
import java.util.HashMap;
import java.util.Map;
import net.snowflake.client.jdbc.MockConnectionTest;
import org.junit.Test;

public class SessionUtilTest {

  /** Test isPrefixEqual */
  @Test
  public void testIsPrefixEqual() throws Exception {
    assertThat(
        "no port number",
        SessionUtil.isPrefixEqual(
            "https://***REMOVED***/blah",
            "https://***REMOVED***/"));
    assertThat(
        "no port number with a slash",
        SessionUtil.isPrefixEqual(
            "https://***REMOVED***/blah",
            "https://***REMOVED***"));
    assertThat(
        "including a port number on one of them",
        SessionUtil.isPrefixEqual(
            "https://***REMOVED***/blah",
            "https://***REMOVED***/"));

    // negative
    assertThat(
        "different hostnames",
        !SessionUtil.isPrefixEqual(
            "https://***REMOVED***/blah",
            "https://***REMOVED***/"));
    assertThat(
        "different port numbers",
        !SessionUtil.isPrefixEqual(
            "https://***REMOVED***/blah",
            "https://***REMOVED***/"));
    assertThat(
        "different protocols",
        !SessionUtil.isPrefixEqual(
            "http://***REMOVED***/blah",
            "https://***REMOVED***/"));
  }

  @Test
  public void testParameterParsing() {
    Map<String, Object> parameterMap = new HashMap<>();
    parameterMap.put("other_parameter", BooleanNode.getTrue());
    SFBaseSession session = new MockConnectionTest.MockSnowflakeSFSession();
    SessionUtil.updateSfDriverParamValues(parameterMap, session);
    assert (((BooleanNode) session.getOtherParameter("other_parameter")).asBoolean());
  }
}
