package de.captaingoldfish.scim.sdk.common.resources;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import de.captaingoldfish.scim.sdk.common.constants.AttributeNames;
import de.captaingoldfish.scim.sdk.common.constants.ScimType;
import de.captaingoldfish.scim.sdk.common.constants.enums.Type;
import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import de.captaingoldfish.scim.sdk.common.exceptions.InternalServerException;
import de.captaingoldfish.scim.sdk.common.resources.complex.Meta;
import de.captaingoldfish.scim.sdk.common.schemas.SchemaAttribute;


/**
 * author Pascal Knueppel <br>
 * created at: 11.10.2019 - 11:23 <br>
 * <br>
 * Each SCIM resource (Users, Groups, etc.) includes the following common attributes. With the exception of
 * the "ServiceProviderConfig" and "ResourceType" server discovery endpoints and their associated resources,
 * these attributes MUST be defined for all resources, including any extended resource types. When accepted by
 * a service provider (e.g., after a SCIM create), the attributes "id" and "meta" (and its associated
 * sub-attributes) MUST be assigned values by the service provider. Common attributes are considered to be
 * part of every base resource schema and do not use their own "schemas" URI. For backward compatibility, some
 * existing schema definitions MAY list common attributes as part of the schema. The attribute characteristics
 * (see Section 2.2) listed here SHALL take precedence over older definitions that may be included in existing
 * schemas.
 */
public abstract class ResourceNode extends AbstractSchemasHolder
{

  /**
   * A unique identifier for a SCIM resource as defined by the service provider. Each representation of the
   * resource MUST include a non-empty "id" value. This identifier MUST be unique across the SCIM service
   * provider's entire set of resources. It MUST be a stable, non-reassignable identifier that does not change
   * when the same resource is returned in subsequent requests. The value of the "id" attribute is always issued
   * by the service provider and MUST NOT be specified by the client. The string "bulkId" is a reserved keyword
   * and MUST NOT be used within any unique identifier value. The attribute characteristics are "caseExact" as
   * "true", a mutability of "readOnly", and a "returned" characteristic of "always". See Section 9 for
   * additional considerations regarding privacy.
   */
  public Optional<String> getId()
  {
    return getStringAttribute(AttributeNames.RFC7643.ID);
  }

  /**
   * A unique identifier for a SCIM resource as defined by the service provider. Each representation of the
   * resource MUST include a non-empty "id" value. This identifier MUST be unique across the SCIM service
   * provider's entire set of resources. It MUST be a stable, non-reassignable identifier that does not change
   * when the same resource is returned in subsequent requests. The value of the "id" attribute is always issued
   * by the service provider and MUST NOT be specified by the client. The string "bulkId" is a reserved keyword
   * and MUST NOT be used within any unique identifier value. The attribute characteristics are "caseExact" as
   * "true", a mutability of "readOnly", and a "returned" characteristic of "always". See Section 9 for
   * additional considerations regarding privacy.
   */
  public void setId(String id)
  {
    setAttribute(AttributeNames.RFC7643.ID, id);
  }

  /**
   * A String that is an identifier for the resource as defined by the provisioning client. The "externalId" may
   * simplify identification of a resource between the provisioning client and the service provider by allowing
   * the client to use a filter to locate the resource with an identifier from the provisioning domain,
   * obviating the need to store a local mapping between the provisioning domain's identifier of the resource
   * and the identifier used by the service provider. Each resource MAY include a non-empty "externalId" value.
   * The value of the "externalId" attribute is always issued by the provisioning client and MUST NOT be
   * specified by the service provider. The service provider MUST always interpret the externalId as scoped to
   * the provisioning domain. While the server does not enforce uniqueness, it is assumed that the value's
   * uniqueness is controlled by the client setting the value. See Section 9 for additional considerations
   * regarding privacy. This attribute has "caseExact" as "true" and a mutability of "readWrite". This attribute
   * is OPTIONAL.
   */
  public Optional<String> getExternalId()
  {
    return getStringAttribute(AttributeNames.RFC7643.EXTERNAL_ID);
  }

  public Optional<String> getLdapId()
  {
    return getStringAttribute("LDAP_ID");
  }

  /**
   * A String that is an identifier for the resource as defined by the provisioning client. The "externalId" may
   * simplify identification of a resource between the provisioning client and the service provider by allowing
   * the client to use a filter to locate the resource with an identifier from the provisioning domain,
   * obviating the need to store a local mapping between the provisioning domain's identifier of the resource
   * and the identifier used by the service provider. Each resource MAY include a non-empty "externalId" value.
   * The value of the "externalId" attribute is always issued by the provisioning client and MUST NOT be
   * specified by the service provider. The service provider MUST always interpret the externalId as scoped to
   * the provisioning domain. While the server does not enforce uniqueness, it is assumed that the value's
   * uniqueness is controlled by the client setting the value. See Section 9 for additional considerations
   * regarding privacy. This attribute has "caseExact" as "true" and a mutability of "readWrite". This attribute
   * is OPTIONAL.
   */
  public void setExternalId(String externalId)
  {
    setAttribute(AttributeNames.RFC7643.EXTERNAL_ID, externalId);
  }

  public void setLdapId(String ldapId)
  {
    setAttribute("LDAP_ID", ldapId);
  }

  /**
   * A complex attribute containing resource metadata. All "meta" sub-attributes are assigned by the service
   * provider (have a "mutability" of "readOnly"), and all of these sub-attributes have a "returned"
   * characteristic of "default". This attribute SHALL be ignored when provided by clients. "meta" contains the
   * following sub-attributes:
   */
  public Optional<Meta> getMeta()
  {
    return getObjectAttribute(AttributeNames.RFC7643.META, Meta.class);
  }

  /**
   * A complex attribute containing resource metadata. All "meta" sub-attributes are assigned by the service
   * provider (have a "mutability" of "readOnly"), and all of these sub-attributes have a "returned"
   * characteristic of "default". This attribute SHALL be ignored when provided by clients. "meta" contains the
   * following sub-attributes:
   */
  public void setMeta(Meta meta)
  {
    if (getMeta().isPresent())
    {
      throw new InternalServerException("meta attribute is already present please do not override the whole meta "
                                        + "attribute but the single values", null, null);
    }
    setAttribute(AttributeNames.RFC7643.META, meta);
  }

  /**
   * this method is specifically for sorting and applies to the following rules for the "sortBy" attribute
   * defined by RFC7644<br>
   * <br>
   *
   * <pre>
   *   The "sortBy" parameter specifies the attribute whose value
   *   SHALL be used to order the returned responses.  If the "sortBy"
   *   attribute corresponds to a singular attribute, resources are
   *   sorted according to that attribute's value; if it's a multi-valued
   *   attribute, resources are sorted by the value of the primary
   *   attribute (see Section 2.4 of [RFC7643]), if any, or else the
   *   first value in the list, if any.  If the attribute is complex, the
   *   attribute name must be a path to a sub-attribute in standard
   *   attribute notation (Section 3.10), e.g., "sortBy=name.givenName".
   *   For all attribute types, if there is no data for the specified
   *   "sortBy" value, they are sorted via the "sortOrder" parameter,
   *   i.e., they are ordered last if ascending and first if descending.
   * </pre>
   *
   * @param sortBy the sortBy attribute definition
   * @return the json node that represents the specified attribute
   */
  public Optional<JsonNode> getSortingAttribute(SchemaAttribute sortBy)
  {
    if (sortBy == null)
    {
      return Optional.empty();
    }
    if (Type.COMPLEX.equals(sortBy.getType()))
    {
      throw new BadRequestException(" the attribute name must be a path to a sub-attribute in standard "
                                    + "attribute notation, e.g., \"sortBy=name.givenName\".", null,
                                    ScimType.RFC7644.INVALID_PATH);
    }
    Optional<JsonNode> parentNode = getSubNodeFromParent(this, false, sortBy.getParent());
    if (parentNode.isPresent())
    {
      return getSubNodeFromParent(parentNode.get(), Type.COMPLEX.equals(sortBy.getParent().getType()), sortBy);
    }
    else
    {
      return getSubNodeFromParent(this, false, sortBy);
    }
  }

  /**
   * gets the attribute of the {@code sortBy} attribute definition from the given parent node
   *
   * @param parent the parent node from which the attribute should be extracted
   * @param isComplex if the parent node is a complex type or a simple type
   * @param sortBy the attribute definition of the attribute that should be returned
   * @return the {@code sortBy} attribute or an empty if not present
   */
  private Optional<JsonNode> getSubNodeFromParent(JsonNode parent, boolean isComplex, SchemaAttribute sortBy)
  {
    if (sortBy == null)
    {
      return Optional.empty();
    }
    if (parent.size() == 0)
    {
      return Optional.empty();
    }

    if (parent.isArray() && isComplex)
    {
      JsonNode primaryNode = getPrimarySortingNodeFromMultiComplex((ArrayNode)parent);
      return Optional.ofNullable(primaryNode.get(sortBy.getName()));
    }
    else if (parent.isArray())
    {
      return Optional.ofNullable(parent.get(0));
    }
    else
    {
      return Optional.ofNullable(parent.get(sortBy.getName()));
    }
  }

  /**
   * retrieves either the first node of the array list or the primary node of the attribute
   *
   * @param arrayNode the multi valued complex attribute that might hold a primary attribute
   * @return the primary node if any or the first node of the array node
   */
  private JsonNode getPrimarySortingNodeFromMultiComplex(ArrayNode arrayNode)
  {
    JsonNode primaryNode = null;
    for ( int i = 0 ; i < arrayNode.size() ; i++ )
    {
      JsonNode multiComplexNode = arrayNode.get(i);
      JsonNode primary = multiComplexNode.get(AttributeNames.RFC7643.PRIMARY);
      if (primary != null && primary.isBoolean() && primary.booleanValue())
      {
        primaryNode = multiComplexNode;
        break;
      }
    }
    if (primaryNode == null)
    {
      primaryNode = arrayNode.get(0);
    }
    return primaryNode;
  }
}
