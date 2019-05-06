package net.snowflake.client.core;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

public class SFTrustManagerTest
{
  /**
   * Test building OCSP retry URL
   */
  @Test
  public void testBuildRetryURL()
  {
    try
    {
      // private link
      System.clearProperty("net.snowflake.jdbc.ssd_support_enabled");
      SFTrustManager.ssdManager = new SSDManager();
      SFTrustManager.SF_OCSP_RESPONSE_CACHE_SERVER_RETRY_URL_PATTERN = null;
      SFTrustManager.resetOCSPResponseCacherServerURL(
          "http://ocsp.us-east-***REMOVED***/" +
          SFTrustManager.CACHE_FILE_NAME);
      assertThat(
          SFTrustManager.SF_OCSP_RESPONSE_CACHE_SERVER_RETRY_URL_PATTERN,
          equalTo("http://ocsp.us-east-***REMOVED***/retry/%s/%s"));

      // private link with port
      System.clearProperty("net.snowflake.jdbc.ssd_support_enabled");
      SFTrustManager.ssdManager = new SSDManager();
      SFTrustManager.SF_OCSP_RESPONSE_CACHE_SERVER_RETRY_URL_PATTERN = null;
      SFTrustManager.resetOCSPResponseCacherServerURL(
          "http://ocsp.us-east-***REMOVED***/" +
          SFTrustManager.CACHE_FILE_NAME);
      assertThat(
          SFTrustManager.SF_OCSP_RESPONSE_CACHE_SERVER_RETRY_URL_PATTERN,
          equalTo("http://ocsp.us-east-***REMOVED***/retry/%s/%s"));

      // non-privatelink
      System.clearProperty("net.snowflake.jdbc.ssd_support_enabled");
      SFTrustManager.ssdManager = new SSDManager();
      SFTrustManager.SF_OCSP_RESPONSE_CACHE_SERVER_RETRY_URL_PATTERN = null;
      SFTrustManager.resetOCSPResponseCacherServerURL(
          "http://***REMOVED***/" +
          SFTrustManager.CACHE_FILE_NAME);
      assertThat(
          SFTrustManager.SF_OCSP_RESPONSE_CACHE_SERVER_RETRY_URL_PATTERN, nullValue());

      // non-privatelink with port
      System.clearProperty("net.snowflake.jdbc.ssd_support_enabled");
      SFTrustManager.ssdManager = new SSDManager();
      SFTrustManager.SF_OCSP_RESPONSE_CACHE_SERVER_RETRY_URL_PATTERN = null;
      SFTrustManager.resetOCSPResponseCacherServerURL(
          "http://***REMOVED***/" +
          SFTrustManager.CACHE_FILE_NAME);
      assertThat(
          SFTrustManager.SF_OCSP_RESPONSE_CACHE_SERVER_RETRY_URL_PATTERN, nullValue());
    }
    finally
    {
      System.clearProperty("net.snowflake.jdbc.ssd_support_enabled");

    }
  }

  @Test
  public void testBuildNewRetryURL()
  {
    try
    {
      System.setProperty("net.snowflake.jdbc.ocsp_activate_new_endpoint", "true");
      SFTrustManager tManager = new SFTrustManager(null, // OCSP Cache file custom location
                                                   false, // OCSP SoftFail Mode
                                                   true); // Use OCSP Cache Server
      tManager.ocspCacheServer.resetOCSPResponseCacheServer("***REMOVED***");
      assertThat(
          tManager.ocspCacheServer.SF_OCSP_RESPONSE_CACHE_SERVER,
          equalTo("https://***REMOVED***/ocsp/fetch"));
      assertThat(
          tManager.ocspCacheServer.SF_OCSP_RESPONSE_RETRY_URL,
          equalTo("https://***REMOVED***/ocsp/retry"));

      tManager.ocspCacheServer.resetOCSPResponseCacheServer("a1-***REMOVED***");
      assertThat(
          tManager.ocspCacheServer.SF_OCSP_RESPONSE_CACHE_SERVER,
          equalTo("https://ocspssd-***REMOVED***/ocsp/fetch"));
      assertThat(
          tManager.ocspCacheServer.SF_OCSP_RESPONSE_RETRY_URL,
          equalTo("https://ocspssd-***REMOVED***/ocsp/retry"));

      tManager.ocspCacheServer.resetOCSPResponseCacheServer("okta.snowflake.com");
      assertThat(
          tManager.ocspCacheServer.SF_OCSP_RESPONSE_CACHE_SERVER,
          equalTo("https://***REMOVED***/ocsp/fetch"));
      assertThat(
          tManager.ocspCacheServer.SF_OCSP_RESPONSE_RETRY_URL,
          equalTo("https://***REMOVED***/ocsp/retry"));

      tManager.ocspCacheServer.resetOCSPResponseCacheServer("a1.us-east-***REMOVED***");
      assertThat(
          tManager.ocspCacheServer.SF_OCSP_RESPONSE_CACHE_SERVER,
          equalTo("https://ocspssd.us-east-***REMOVED***/ocsp/fetch"));
      assertThat(
          tManager.ocspCacheServer.SF_OCSP_RESPONSE_RETRY_URL,
          equalTo("https://ocspssd.us-east-***REMOVED***/ocsp/retry"));
    }
    finally
    {
      System.clearProperty("net.snowflake.jdbc.ocsp_activate_new_endpoint");
    }
  }
}
