/*
 * Copyright (c) 2012-2019 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.core;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;

public class SessionUtilTest
{

  /**
   * Test isPrefixEqual
   */
  @Test
  public void testIsPrefixEqual() throws Exception
  {
    assertThat("no port number",
               SessionUtil.isPrefixEqual(
                   "https://***REMOVED***/blah",
                   "https://***REMOVED***/"));
    assertThat("no port number with a slash",
               SessionUtil.isPrefixEqual(
                   "https://***REMOVED***/blah",
                   "https://***REMOVED***"));
    assertThat("including a port number on one of them",
               SessionUtil.isPrefixEqual(
                   "https://***REMOVED***/blah",
                   "https://***REMOVED***/"));

    // negative
    assertThat("different hostnames",
               !SessionUtil.isPrefixEqual(
                   "https://***REMOVED***/blah",
                   "https://***REMOVED***/"));
    assertThat("different port numbers",
               !SessionUtil.isPrefixEqual(
                   "https://***REMOVED***/blah",
                   "https://***REMOVED***/"));
    assertThat("different protocols",
               !SessionUtil.isPrefixEqual(
                   "http://***REMOVED***/blah",
                   "https://***REMOVED***/"));
  }
}
