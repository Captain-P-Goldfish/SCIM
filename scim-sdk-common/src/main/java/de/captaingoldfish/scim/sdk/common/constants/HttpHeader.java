package de.captaingoldfish.scim.sdk.common.constants;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;


/**
 * author Pascal Knueppel <br>
 * created at: 14.10.2019 - 14:13 <br>
 * <br>
 * contains the http header that are required for the SCIM protocol
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HttpHeader
{

  public static final String CONTENT_TYPE_HEADER = "Content-Type";

  public static final String SCIM_CONTENT_TYPE = "application/scim+json";

  public static final String LOCATION_HEADER = "Location";

  public static final String E_TAG_HEADER = "ETag";

  public static final String IF_MATCH_HEADER = "If-Match";

  public static final String IF_NONE_MATCH_HEADER = "If-None-Match";

  public static final String AUTHORIZATION = "Authorization";

  public static final String WWW_AUTHENTICATE = "WWW-Authenticate";
}
