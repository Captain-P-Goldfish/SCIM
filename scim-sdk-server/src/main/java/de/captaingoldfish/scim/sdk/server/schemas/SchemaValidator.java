package de.captaingoldfish.scim.sdk.server.schemas;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.captaingoldfish.scim.sdk.common.constants.AttributeNames;
import de.captaingoldfish.scim.sdk.common.constants.HttpStatus;
import de.captaingoldfish.scim.sdk.common.constants.SchemaUris;
import de.captaingoldfish.scim.sdk.common.constants.ScimType;
import de.captaingoldfish.scim.sdk.common.constants.enums.HttpMethod;
import de.captaingoldfish.scim.sdk.common.constants.enums.Mutability;
import de.captaingoldfish.scim.sdk.common.constants.enums.ReferenceTypes;
import de.captaingoldfish.scim.sdk.common.constants.enums.Returned;
import de.captaingoldfish.scim.sdk.common.constants.enums.Type;
import de.captaingoldfish.scim.sdk.common.constants.enums.Uniqueness;
import de.captaingoldfish.scim.sdk.common.exceptions.BadRequestException;
import de.captaingoldfish.scim.sdk.common.exceptions.DocumentValidationException;
import de.captaingoldfish.scim.sdk.common.exceptions.InternalServerException;
import de.captaingoldfish.scim.sdk.common.exceptions.InvalidDateTimeRepresentationException;
import de.captaingoldfish.scim.sdk.common.exceptions.ScimException;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimArrayNode;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimBooleanNode;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimDoubleNode;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimIntNode;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimLongNode;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimNode;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimObjectNode;
import de.captaingoldfish.scim.sdk.common.resources.base.ScimTextNode;
import de.captaingoldfish.scim.sdk.common.schemas.Schema;
import de.captaingoldfish.scim.sdk.common.schemas.SchemaAttribute;
import de.captaingoldfish.scim.sdk.common.utils.AttributeValidator;
import de.captaingoldfish.scim.sdk.common.utils.JsonHelper;
import de.captaingoldfish.scim.sdk.common.utils.TimeUtils;
import de.captaingoldfish.scim.sdk.server.utils.RequestUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


/**
 * author Pascal Knueppel <br>
 * created at: 06.10.2019 - 00:18 <br>
 * <br>
 * This class will validate documents against their meta-schemata. Normally the validation should be done with
 * the help of {@link ResourceType}-definitions that define a resource endpoint with the setup of their
 * resources. Meaning that a {@link ResourceType} knows the meta-schemata of a resource and its extensions
 * which is why the {@link ResourceType} is the base of validating a resource-document
 */
@Slf4j
public class SchemaValidator
{

  /**
   * tells us if the current validation is about an extension or the main document. In case of an extension we
   * will not validate the "schemas"-attribute because it is not expected within an extension
   */
  private final boolean extensionSchema;

  /**
   * tells us if the schema is validated as request or as response
   *
   * @see DirectionType
   */
  private final DirectionType directionType;

  /**
   * tells us which request type the user has used. This is e.g. necessary for immutable types that are valid on
   * POST requests but invalid on PUT requests
   */
  private final HttpMethod httpMethod;

  /**
   * this member is used for attributes that have a returned value of {@link Returned#REQUEST}. Those attributes
   * should only be returned if the attribute was modified on a POST, PUT or PATCH request or in a query request
   * only if the attribute is present within the {@link #attributes} parameter. So the validated request tells
   * us if the client tried to write to the attribute and if this is the case the attribute should be returned
   * <br>
   * <br>
   * from RFC7643 chapter 7
   *
   * <pre>
   *   request  The attribute is returned in response to any PUT,
   *             POST, or PATCH operations if the attribute was specified by
   *             the client (for example, the attribute was modified).  The
   *             attribute is returned in a SCIM query operation only if
   *             specified in the "attributes" parameter.
   * </pre>
   */
  private final JsonNode validatedRequest;

  /**
   * When specified, the default list of attributes SHALL be overridden, and each resource returned MUST contain
   * the minimum set of resource attributes and any attributes or sub-attributes explicitly requested by the
   * "attributes" parameter. The query parameter attributes value is a comma-separated list of resource
   * attribute names in standard attribute notation (Section 3.10) form (e.g., userName, name, emails).
   */
  private final List<String> attributes;

  /**
   * When specified, each resource returned MUST contain the minimum set of resource attributes. Additionally,
   * the default set of attributes minus those attributes listed in "excludedAttributes" is returned. The query
   * parameter attributes value is a comma-separated list of resource attribute names in standard attribute
   * notation (Section 3.10) form (e.g., userName, name, emails).
   */
  private final List<String> excludedAttributes;

  /**
   * used to automatically set $ref values on reference types during schema-validation if the attribute is
   * missing
   */
  private final Supplier<String> baseUrlSupplier;

  /**
   * used to automatically set $ref values on reference types during schema-validation if the attribute is
   * missing. We need the factory to get the endpoint-path of the referenced resource
   */
  private final ResourceTypeFactory resourceTypeFactory;

  private SchemaValidator(DirectionType directionType, String attributes, String excludedAttributes)
  {
    this.extensionSchema = false;
    this.directionType = directionType;
    this.httpMethod = null;
    this.validatedRequest = null;
    this.attributes = RequestUtils.getAttributes(attributes);
    this.excludedAttributes = RequestUtils.getAttributes(excludedAttributes);
    this.baseUrlSupplier = null;
    this.resourceTypeFactory = null;
  }

  private SchemaValidator(DirectionType directionType,
                          HttpMethod httpMethod,
                          JsonNode validatedRequest,
                          String attributes,
                          String excludedAttributes,
                          Supplier<String> baseUrlSupplier,
                          ResourceTypeFactory resourceTypeFactory)
  {
    this.directionType = directionType;
    this.httpMethod = httpMethod;
    this.extensionSchema = false;
    this.validatedRequest = validatedRequest;
    this.attributes = RequestUtils.getAttributes(attributes);
    this.excludedAttributes = RequestUtils.getAttributes(excludedAttributes);
    this.baseUrlSupplier = baseUrlSupplier;
    this.resourceTypeFactory = resourceTypeFactory;
  }

  private SchemaValidator(DirectionType directionType,
                          HttpMethod httpMethod,
                          boolean extensionSchema,
                          JsonNode validatedRequest,
                          String attributes,
                          String excludedAttributes,
                          Supplier<String> baseUrlSupplier,
                          ResourceTypeFactory resourceTypeFactory)
  {
    this.directionType = directionType;
    this.httpMethod = httpMethod;
    this.extensionSchema = extensionSchema;
    this.validatedRequest = validatedRequest;
    this.attributes = RequestUtils.getAttributes(attributes);
    this.excludedAttributes = RequestUtils.getAttributes(excludedAttributes);
    this.baseUrlSupplier = baseUrlSupplier;
    this.resourceTypeFactory = resourceTypeFactory;
  }

  /**
   * this method will validate a new schema declaration against a meta schema. In other validations it might
   * happen that specific attributes will be removed from the document because they do not belong into a request
   * or a response. This method will ignore the direction-validation and keeps these attributes
   *
   * @param metaSchema the meta schema that is used to validate the new schema
   * @param schemaDocument the new schema document that should be validated
   * @return the validated schema definition
   */
  public static JsonNode validateSchemaDocument(Schema metaSchema, JsonNode schemaDocument)
  {
    SchemaValidator schemaValidator = new SchemaValidator(null, null, null);
    return schemaValidator.validateDocument(metaSchema, schemaDocument);
  }

  /**
   * this method will validate a new schema declaration against a meta schema. In other validations it might
   * happen that specific attributes will be removed from the document because they do not belong into a request
   * or a response. This method will ignore the direction-validation and keeps these attributes
   *
   * @param metaSchema the meta schema that is used to validate the new schema
   * @param schemaDocument the new schema document that should be validated
   * @return the validated schema definition
   */
  public static JsonNode validateSchemaDocumentForRequest(Schema metaSchema, JsonNode schemaDocument)
  {
    SchemaValidator schemaValidator = new SchemaValidator(DirectionType.REQUEST, null, null);
    return schemaValidator.validateDocument(metaSchema, schemaDocument);
  }

  /**
   * will validate an outgoing document against its main schema and all its extensions. Attributes that are
   * unknown to the given schema or are meaningless or forbidden in responses due to their mutability or
   * returned value will be removed from the validated document. <br>
   * attributes that will be removed in the response validation are thos that are having a mutability of
   * {@link Mutability#WRITE_ONLY} or a returned value of {@link Returned#NEVER}. This will prevent the server
   * from accidentally returning passwords or equally sensitive information
   *
   * @param resourceType the resource type definition of the incoming document
   * @param document the document that should be validated
   * @param validatedRequest this parameter is used for attributes that have a returned value of
   *          {@link Returned#REQUEST}. Those attributes should only be returned if the attribute was modified
   *          on a POST, PUT or PATCH request or in a query request only if the attribute is present within the
   *          {@link #attributes} parameter. So the validated request tells us if the client tried to write to
   *          the attribute and if this is the case the attribute should be returned
   * @param attributes When specified, the default list of attributes SHALL be overridden, and each resource
   *          returned MUST contain the minimum set of resource attributes and any attributes or sub-attributes
   *          explicitly requested by the "attributes" parameter. The query parameter attributes value is a
   *          comma-separated list of resource attribute names in standard attribute notation (Section 3.10)
   *          form (e.g., userName, name, emails).
   * @param excludedAttributes When specified, each resource returned MUST contain the minimum set of resource
   *          attributes. Additionally, the default set of attributes minus those attributes listed in
   *          "excludedAttributes" is returned. The query parameter attributes value is a comma-separated list
   *          of resource attribute names in standard attribute notation (Section 3.10) form (e.g., userName,
   *          name, emails).
   * @return the validated document that consists of {@link ScimNode}s
   * @throws DocumentValidationException if the schema validation failed
   */
  public static JsonNode validateDocumentForResponse(ResourceTypeFactory resourceTypeFactory,
                                                     ResourceType resourceType,
                                                     JsonNode document,
                                                     JsonNode validatedRequest,
                                                     String attributes,
                                                     String excludedAttributes,
                                                     Supplier<String> baseUrlSupplier)
    throws DocumentValidationException
  {
    ResourceType.ResourceSchema resourceSchema = resourceType.getResourceSchema(document);
    JsonNode validatedMainDocument = validateDocumentForResponse(resourceSchema.getMetaSchema(),
                                                                 document,
                                                                 validatedRequest,
                                                                 attributes,
                                                                 excludedAttributes,
                                                                 baseUrlSupplier,
                                                                 resourceTypeFactory);
    validatedForMissingRequiredExtension(resourceType, document, DirectionType.RESPONSE);
    for ( Schema schemaExtension : resourceSchema.getExtensions() )
    {
      Supplier<String> message = () -> "the extension '" + schemaExtension.getId() + "' is referenced in the '"
                                       + AttributeNames.RFC7643.SCHEMAS + "' attribute but is "
                                       + "not present within the document";
      JsonNode extension = Optional.ofNullable(document.get(schemaExtension.getId().orElse(null)))
                                   .orElseThrow(() -> new InternalServerException(message.get(), null,
                                                                                  ScimType.Custom.MISSING_EXTENSION));
      JsonNode extensionNode = validateExtensionForResponse(schemaExtension,
                                                            extension,
                                                            validatedRequest == null ? null
                                                              : validatedRequest.get(schemaExtension.getNonNullId()),
                                                            attributes,
                                                            excludedAttributes,
                                                            baseUrlSupplier,
                                                            resourceTypeFactory);
      if (extensionNode == null)
      {
        JsonHelper.getArrayAttribute(validatedMainDocument, AttributeNames.RFC7643.SCHEMAS).ifPresent(arrayNode -> {
          JsonHelper.removeSimpleAttributeFromArray(arrayNode, schemaExtension.getNonNullId());
        });
      }
      else
      {
        JsonHelper.addAttribute(validatedMainDocument, schemaExtension.getNonNullId(), extensionNode);
      }
    }
    Schema metaSchema = resourceTypeFactory.getSchemaFactory().getMetaSchema(SchemaUris.META);
    JsonNode validatedMeta;
    try
    {
      validatedMeta = validateExtensionForResponse(metaSchema,
                                                   document,
                                                   validatedRequest,
                                                   attributes,
                                                   excludedAttributes,
                                                   baseUrlSupplier,
                                                   resourceTypeFactory);
    }
    catch (ScimException ex)
    {
      log.error("meta attribute validation failed for resource type: " + resourceType.getName() + " ["
                + resourceType.getSchema() + "]");
      throw ex;
    }
    if (validatedMeta != null && validatedMeta.size() != 0 && validatedMainDocument != null)
    {
      JsonHelper.addAttribute(validatedMainDocument,
                              AttributeNames.RFC7643.META,
                              validatedMeta.get(AttributeNames.RFC7643.META));
    }
    return validatedMainDocument;
  }

  /**
   * will validate an outgoing document against its main schema and all its extensions. Attributes that are
   * unknown to the given schema or are meaningless or forbidden in responses due to their mutability or
   * returned value will be removed from the validated document. <br>
   * attributes that will be removed in the response validation are thos that are having a mutability of
   * {@link Mutability#WRITE_ONLY} or a returned value of {@link Returned#NEVER}. This will prevent the server
   * from accidentally returning passwords or equally sensitive information
   *
   * @param metaSchema the json meta schema definition of the document
   * @param document the document to validate
   * @return the validated document that consists of {@link ScimNode}s
   */
  protected static JsonNode validateDocumentForResponse(Schema metaSchema,
                                                        JsonNode document,
                                                        Supplier<String> baseUrlSupplier,
                                                        ResourceTypeFactory resourceTypeFactory)
  {
    return validateDocumentForResponse(metaSchema, document, null, null, null, baseUrlSupplier, resourceTypeFactory);
  }

  /**
   * will validate an outgoing document against its main schema and all its extensions. Attributes that are
   * unknown to the given schema or are meaningless or forbidden in responses due to their mutability or
   * returned value will be removed from the validated document. <br>
   * attributes that will be removed in the response validation are thos that are having a mutability of
   * {@link Mutability#WRITE_ONLY} or a returned value of {@link Returned#NEVER}. This will prevent the server
   * from accidentally returning passwords or equally sensitive information
   *
   * @param metaSchema the json meta schema definition of the document
   * @param document the document to validate
   * @param validatedRequest this parameter is used for attributes that have a returned value of
   *          {@link Returned#REQUEST}. Those attributes should only be returned if the attribute was modified
   *          on a POST, PUT or PATCH request or in a query request only if the attribute is present within the
   *          {@link #attributes} parameter. So the validated request tells us if the client tried to write to
   *          the attribute and if this is the case the attribute should be returned write to the attribute and
   *          if this is the case the attribute should be returned
   * @param attributes When specified, the default list of attributes SHALL be overridden, and each resource
   *          returned MUST contain the minimum set of resource attributes and any attributes or sub-attributes
   *          explicitly requested by the "attributes" parameter. The query parameter attributes value is a
   *          comma-separated list of resource attribute names in standard attribute notation (Section 3.10)
   *          form (e.g., userName, name, emails).
   * @param excludedAttributes When specified, each resource returned MUST contain the minimum set of resource
   *          attributes. Additionally, the default set of attributes minus those attributes listed in
   *          "excludedAttributes" is returned. The query parameter attributes value is a comma-separated list
   *          of resource attribute names in standard attribute notation (Section 3.10) form (e.g., userName,
   *          name, emails).
   * @return the validated document that consists of {@link ScimNode}s
   */
  protected static JsonNode validateDocumentForResponse(Schema metaSchema,
                                                        JsonNode document,
                                                        JsonNode validatedRequest,
                                                        String attributes,
                                                        String excludedAttributes,
                                                        Supplier<String> baseUrlSupplier,
                                                        ResourceTypeFactory resourceTypeFactory)
  {
    SchemaValidator schemaValidator = new SchemaValidator(DirectionType.RESPONSE, null, validatedRequest, attributes,
                                                          excludedAttributes, baseUrlSupplier, resourceTypeFactory);
    try
    {
      return schemaValidator.validateDocument(metaSchema, document);
    }
    catch (ScimException ex)
    {
      ex.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
      throw ex;
    }
  }

  /**
   * This method is explicitly for extension validation. Extensions within its main documents should not have
   * "schemas"-attribute itself since the relation of the schema-uri can be found in the "schemas"-attribute of
   * the main-document. So this method will trigger the schema validation but will ignore the validation of the
   * "schemas"-attrbiute
   *
   * @param metaSchema the json meta schema definition of the extension
   * @param document the extension to validate
   * @param validatedRequest this parameter is used for attributes that have a returned value of
   *          {@link Returned#REQUEST}. Those attributes should only be returned if the attribute was modified
   *          on a POST, PUT or PATCH request or in a query request only if the attribute is present within the
   *          {@link #attributes} parameter. So the validated request tells us if the client tried to write to
   *          the attribute and if this is the case the attribute should be returned
   * @return the validated document that consists of {@link ScimNode}s
   */
  private static JsonNode validateExtensionForResponse(Schema metaSchema,
                                                       JsonNode document,
                                                       JsonNode validatedRequest,
                                                       String attributes,
                                                       String excludedAttributes,
                                                       Supplier<String> baseUrlSupplier,
                                                       ResourceTypeFactory resourceTypeFactory)
  {
    SchemaValidator schemaValidator = new SchemaValidator(DirectionType.RESPONSE, null, true, validatedRequest,
                                                          attributes, excludedAttributes, baseUrlSupplier,
                                                          resourceTypeFactory);
    try
    {
      return schemaValidator.validateDocument(metaSchema, document);
    }
    catch (ScimException ex)
    {
      ex.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
      throw ex;
    }
  }

  /**
   * will validate an incoming document against its main schema and all its extensions. Attributes that are
   * unknown to the given schema or are meaningless in requests due to their mutability value will be removed
   * from the validated document. <br>
   * attributes that will be removed in the request validation are those that are have a mutability of
   * {@link Mutability#READ_ONLY}. The client is not able to write these attributes and therefore the server
   * does not need to process them.
   *
   * @param resourceType the resource type definition of the incoming document
   * @param document the document that should be validated
   * @param httpMethod the request http method that is used to validate the request-document
   * @return the validated document that consists of {@link ScimNode}s
   * @throws DocumentValidationException if the schema validation failed
   */
  public static JsonNode validateDocumentForRequest(ResourceType resourceType, JsonNode document, HttpMethod httpMethod)
    throws DocumentValidationException
  {
    ResourceType.ResourceSchema resourceSchema = resourceType.getResourceSchema(document);
    JsonNode validatedMainDocument = validateDocumentForRequest(resourceSchema.getMetaSchema(), document, httpMethod);
    if (validatedMainDocument == null)
    {
      throw new DocumentValidationException("the received document is invalid and does not contain any data. The "
                                            + "illegal document is: " + document.toString(), null,
                                            HttpStatus.BAD_REQUEST, null);
    }
    validatedForMissingRequiredExtension(resourceType, document, DirectionType.REQUEST);
    for ( Schema schemaExtension : resourceSchema.getExtensions() )
    {
      Supplier<String> message = () -> "the extension '" + schemaExtension.getId().orElse("null") + "' is referenced "
                                       + "in the '" + AttributeNames.RFC7643.SCHEMAS + "' attribute but is "
                                       + "not present within the document";
      JsonNode extension = document.get(schemaExtension.getNonNullId());
      JsonNode extensionNode = null;
      log.info("validateDocumentForRequest extension: ", (extension != null ? extension.toPrettyString() : null));
      if (extension != null)
      {
        extensionNode = validateExtensionForRequest(schemaExtension, extension, httpMethod);
      }
      if (extensionNode == null)
      {
        JsonHelper.getArrayAttribute(validatedMainDocument, AttributeNames.RFC7643.SCHEMAS).ifPresent(arrayNode -> {
          JsonHelper.removeSimpleAttributeFromArray(arrayNode, schemaExtension.getNonNullId());
        });
      }
      else
      {
        JsonHelper.addAttribute(validatedMainDocument, schemaExtension.getNonNullId(), extensionNode);
      }
    }
    if (document.has(AttributeNames.RFC7643.META))
    {
      ((ObjectNode)validatedMainDocument).set(AttributeNames.RFC7643.META, document.get(AttributeNames.RFC7643.META));
    }
    return validatedMainDocument;
  }

  /**
   * will validate an incoming document against its main schema and all its extensions. Attributes that are
   * unknown to the given schema or are meaningless in requests due to their mutability value will be removed
   * from the validated document. <br>
   * attributes that will be removed in the request validation are those that are have a mutability of
   * {@link Mutability#READ_ONLY}. The client is not able to write these attributes and therefore the server
   * does not need to process them.
   *
   * @param metaSchema the json meta schema definition of the document
   * @param document the document to validate
   * @param httpMethod tells us which request type the client has used. This is e.g. necessary for immutable
   *          types that are valid on POST requests but invalid on PUT requests
   * @return the validated document that consists of {@link ScimNode}s
   */
  protected static JsonNode validateDocumentForRequest(Schema metaSchema, JsonNode document, HttpMethod httpMethod)
  {
    SchemaValidator schemaValidator = new SchemaValidator(DirectionType.REQUEST, httpMethod, null, null, null, null,
                                                          null);
    try
    {
      return schemaValidator.validateDocument(metaSchema, document);
    }
    catch (ScimException ex)
    {
      ex.setStatus(HttpStatus.BAD_REQUEST);
      throw ex;
    }
  }

  /**
   * This method is explicitly for extension validation. Extensions within its main documents should not have
   * "schemas"-attribute itself since the relation of the schema-uri can be found in the "schemas"-attribute of
   * the main-document. So this method will trigger the schema validation but will ignore the validation of the
   * "schemas"-attrbiute
   *
   * @param metaSchema the json meta schema definition of the document
   * @param document the extension to validate
   * @param httpMethod tells us which request type the client has used. This is e.g. necessary for immutable
   *          types that are valid on POST requests but invalid on PUT requests
   * @return the validated document that consists of {@link ScimNode}s
   */
  protected static JsonNode validateExtensionForRequest(Schema metaSchema, JsonNode document, HttpMethod httpMethod)
  {
    SchemaValidator schemaValidator = new SchemaValidator(DirectionType.REQUEST, httpMethod, true, null, null, null,
                                                          null, null);
    try
    {
      return schemaValidator.validateDocument(metaSchema, document);
    }
    catch (ScimException ex)
    {
      ex.setStatus(HttpStatus.BAD_REQUEST);
      throw ex;
    }
  }

  /**
   * will check that extensions that are required are present within the document and will throw an exception if
   * a required extension is missing
   *
   * @param resourceType the resource type definition that knows all required extensions
   * @param document the document that must be validated
   * @param directionType the direction of the request that will help us with the http status code if the
   *          exception is thrown
   */
  private static void validatedForMissingRequiredExtension(ResourceType resourceType,
                                                           JsonNode document,
                                                           DirectionType directionType)
  {
    for ( Schema requiredExtension : resourceType.getRequiredResourceSchemaExtensions() )
    {
      if (!JsonHelper.getObjectAttribute(document, requiredExtension.getNonNullId()).isPresent())
      {
        String errorMessage = "required extension '" + requiredExtension.getId() + "' is missing in the document";
        throw new DocumentValidationException(errorMessage, null, directionType == null
          ? HttpStatus.INTERNAL_SERVER_ERROR : directionType.getHttpStatus(), null);
      }
    }
  }

  /**
   * this method will validate the given document against the given meta schema and check if the document is
   * valid. Attributes that are unknown in the metaSchema but do exist in the document will be removed from the
   * document
   *
   * @param metaSchema the document description
   * @param document the document that should be built after the rules of the metaSchema
   * @return the validated document that consists of {@link ScimNode}s
   */
  private JsonNode validateDocument(Schema metaSchema, JsonNode document)
  {
    log.trace("validating metaSchema vs document");
    JsonNode schemasNode = null;
    if (!extensionSchema)
    {
      schemasNode = checkDocumentAndMetaSchemaRelationship(metaSchema, document);
    }
    JsonNode validatedDocument = validateAttributes(metaSchema.getAttributes(), document, null);
    if (validatedDocument != null && schemasNode != null)
    {
      JsonHelper.addAttribute(validatedDocument, AttributeNames.RFC7643.SCHEMAS, schemasNode);
    }
    return validatedDocument;
  }

  /**
   * will use the given meta attributes to validate each attribute in the document
   *
   * @param metaAttributes list of meta attributes that may or may not be present within the document
   * @param document the document to validate
   * @param parentAttribute this method is getting called recursively and this is the parent document that is
   *          given to the new {@link SchemaAttribute} object
   * @return the validated document that consists of {@link ScimNode}s
   */
  private JsonNode validateAttributes(List<SchemaAttribute> metaAttributes,
                                      JsonNode document,
                                      SchemaAttribute parentAttribute)
  {
    JsonNode scimNode = new ScimObjectNode(parentAttribute);
    for ( SchemaAttribute metaAttribute : metaAttributes )
    {
      if (document == null)
      {
        validateIsRequired(null, metaAttribute);
        continue;
      }
      checkMetaAttributeOnDocument(document, metaAttribute).ifPresent(childNode -> {
        if (!(childNode.isArray() && childNode.size() == 0))
        {
          JsonHelper.addAttribute(scimNode, metaAttribute.getName(), childNode);
        }
      });
    }
    if (scimNode.size() == 0)
    {
      return null;
    }
    return scimNode;
  }

  /**
   * will check a single meta-attribute on the given document
   *
   * @param document the document to validate
   * @param schemaAttribute the single meta-attribute that will be validated against the given document
   * @return the attribute if present in the document an empty else
   */
  private Optional<JsonNode> checkMetaAttributeOnDocument(JsonNode document, SchemaAttribute schemaAttribute)
  {
    JsonNode documentNode = document.get(schemaAttribute.getName());
    if (log.isTraceEnabled())
    {
      log.trace("validating attribute '{}' with value '{}'",
                schemaAttribute.getName(),
                Optional.ofNullable(documentNode)
                        .map(JsonNode::textValue)
                        .orElse(Optional.ofNullable(documentNode).map(JsonNode::toString).orElse(null)));
    }
    validateIsRequired(documentNode, schemaAttribute);
    if (directionType != null && directionType.equals(DirectionType.RESPONSE) && documentNode == null
        && schemaAttribute.getReferenceTypes().contains(ReferenceTypes.RESOURCE))
    {
      // this block is used for automatically setting $ref values if not already present on complex
      // resource-references
      Optional<JsonNode> overriddenReferenceNode = overrideEmptyReferenceNode(document, schemaAttribute);
      if (overriddenReferenceNode.isPresent())
      {
        documentNode = overriddenReferenceNode.get();
      }
      else
      {
        validateNonPresentAttributes(schemaAttribute);
        return Optional.empty();
      }
    }
    else if (documentNode == null)
    {
      validateNonPresentAttributes(schemaAttribute);
      return Optional.empty();
    }
    else if (!validatePresentAttributes(schemaAttribute))
    {
      return Optional.empty();
    }
    documentNode = validateComplexAndArrayTypeAttribute(documentNode, schemaAttribute);

    if (schemaAttribute.isMultiValued())
    {
      return handleMultivaluedNodes(documentNode, schemaAttribute);
    }
    else
    {
      return handleNode(documentNode, schemaAttribute);
    }
  }

  /**
   * will use the given complex-node json-document to override the $ref attribute if it is not set. If the
   * refernce type was set the URL to the specific resource will be added automatically into the json document
   *
   * @param document the complex object node that represents a resource reference e.g. a member-attribute of the
   *          group resource
   * @param schemaAttribute the attribute definition of the current node
   * @return the overridden $ref node or an empty if overriding is not possible due to lack of information
   */
  private Optional<JsonNode> overrideEmptyReferenceNode(JsonNode document, SchemaAttribute schemaAttribute)
  {
    SchemaAttribute parentAttribute = schemaAttribute.getParent();
    Optional<SchemaAttribute> valueAttribute = parentAttribute.getSubAttributes().stream().filter(attribute -> {
      return attribute.getName().equals(AttributeNames.RFC7643.VALUE);
    }).findAny();
    Optional<SchemaAttribute> typeAttribute = parentAttribute.getSubAttributes().stream().filter(attribute -> {
      return attribute.getName().equals(AttributeNames.RFC7643.TYPE);
    }).findAny();
    if (!valueAttribute.isPresent() || !typeAttribute.isPresent())
    {
      return Optional.empty();
    }
    String referenceId = Optional.ofNullable(document.get(valueAttribute.get().getName()))
                                 .map(JsonNode::textValue)
                                 .orElse(null);
    String typeReference = Optional.ofNullable(document.get(typeAttribute.get().getName()))
                                   .map(JsonNode::textValue)
                                   .orElse(null);
    ResourceType referencedResourceType = resourceTypeFactory.getResourceTypeByName(typeReference).orElse(null);

    if (referenceId == null || typeReference == null || referencedResourceType == null)
    {
      return Optional.empty();
    }

    ObjectNode objectNode = (ObjectNode)document;
    JsonNode newReferencenode = new ScimTextNode(schemaAttribute,
                                                 baseUrlSupplier.get() + referencedResourceType.getEndpoint() + "/"
                                                                  + referenceId);
    objectNode.set(schemaAttribute.getName(), newReferencenode);
    return Optional.of(newReferencenode);
  }

  /**
   * this method will verify that complex and multi valued attributes are sent as defined in the schema. An
   * exception are simple multi valued arrays. If sent as a primitive they should be accepted as an array with a
   * single attribute
   *
   * @param document the document part to validate
   * @param schemaAttribute the meta information of the attribute
   */
  private JsonNode validateComplexAndArrayTypeAttribute(JsonNode document, SchemaAttribute schemaAttribute)
  {
    Supplier<String> errorMessage = () -> String.format("the attribute '%s' does not apply to its defined type. The "
                                                        + "received document node is of type '%s' but the schema"
                                                        + " defintion is as follows: \n\tmultivalued: %s\n\ttype: "
                                                        + "%s\nfor schema with id %s\n%s",
                                                        schemaAttribute.getScimNodeName(),
                                                        document.getNodeType(),
                                                        schemaAttribute.isMultiValued(),
                                                        schemaAttribute.getType(),
                                                        schemaAttribute.getSchema().getId().orElse(null),
                                                        document.toString());
    if (schemaAttribute.isMultiValued())
    {
      boolean isComplexExpected = Type.COMPLEX.equals(schemaAttribute.getType());
      boolean isNodeMultiValuedComplex = document == null
                                         || document.isArray() && document.size() > 0 && document.get(0).isObject()
                                         || document.isArray() && document.size() == 0;

      boolean isSimpleMultiValuedExpected = !isComplexExpected;
      boolean isNodeSimpleMultiValued = document == null || document.isArray();

      if (isSimpleMultiValuedExpected && !isNodeSimpleMultiValued && !isNodeMultiValuedComplex)
      {
        ArrayNode arrayNode = new ScimArrayNode(schemaAttribute);
        arrayNode.add(document);
        return arrayNode;
      }

      if (isSimpleMultiValuedExpected && !isNodeSimpleMultiValued || isComplexExpected && !isNodeMultiValuedComplex)
      {
        throw new DocumentValidationException(errorMessage.get(), null, getHttpStatus(), null);
      }
    }
    else if (Type.COMPLEX.equals(schemaAttribute.getType()))
    {
      boolean isNodeComplex = document == null || document.isObject();
      if (!isNodeComplex)
      {
        throw new DocumentValidationException(errorMessage.get(), null, getHttpStatus(), null);
      }
    }
    return document;
  }

  /**
   * validates attributes that are marked as multiValued attributes in the meta attribute
   *
   * @param document the document that holds the multiValued attribute
   * @param schemaAttribute the meta information of the attribute
   * @return the attribute if present in the document or an empty else
   */
  private Optional<JsonNode> handleMultivaluedNodes(JsonNode document, SchemaAttribute schemaAttribute)
  {
    if (Type.COMPLEX.equals(schemaAttribute.getType()))
    {
      // we will throw an exception if the primary counter exceeds 1
      AtomicInteger countPrimary = new AtomicInteger(0);
      return handleMultivaluedNode(document, schemaAttribute, (jsonNode, scimArrayNode) -> {
        countPrimary.set(checkForPrimary(jsonNode, schemaAttribute, countPrimary.get()));
        handleComplexNode(jsonNode, schemaAttribute).ifPresent(returnedAttribute -> {
          JsonHelper.addAttributeToArray(scimArrayNode, returnedAttribute);
        });
      });
    }
    else
    {
      return handleMultivaluedNode(document, schemaAttribute, (jsonNode, scimArrayNode) -> {
        JsonNode attribute = handleSimpleNode(jsonNode, schemaAttribute);
        JsonHelper.addAttributeToArray(scimArrayNode, attribute);
      });
    }
  }

  /**
   * checks if the given jsonNode contains a primary value and will throw an exception if the counter exceeds
   * more than 1 detected primary values
   *
   * @param jsonNode the multi valued complex type node that might contain a primary value
   * @param primaryCounter the current number of found primary values
   * @return the new calculated number of primary values
   */
  private int checkForPrimary(JsonNode jsonNode, SchemaAttribute schemaAttribute, int primaryCounter)
  {
    boolean isPrimary = JsonHelper.getSimpleAttribute(jsonNode, AttributeNames.RFC7643.PRIMARY, Boolean.class)
                                  .orElse(false);
    int counter = primaryCounter + (isPrimary ? 1 : 0);
    if (counter > 1)
    {
      String errorMessage = "multiple primary values detected in attribute with name '"
                            + schemaAttribute.getFullResourceName() + "'";
      throw getException(errorMessage, null);
    }
    return counter;
  }

  /**
   * handles a json array with complex node types
   *
   * @param document the document that should be validated
   * @param schemaAttribute the meta information of the attribute
   * @param handleMultivaluedNode a consumer that handles either a simple multivalued node or a multivalued
   *          complex node
   * @return the attribute if present in the document or an empty else
   */
  private Optional<JsonNode> handleMultivaluedNode(JsonNode document,
                                                   SchemaAttribute schemaAttribute,
                                                   BiConsumer<JsonNode, ScimArrayNode> handleMultivaluedNode)
  {
    ArrayNode arrayNode;
    if (document.isArray())
    {
      arrayNode = (ArrayNode)document;
    }
    else
    {
      arrayNode = new ArrayNode(JsonNodeFactory.instance);
      arrayNode.add(document);
    }
    ScimArrayNode scimArrayNode = new ScimArrayNode(schemaAttribute);
    for ( JsonNode jsonNode : arrayNode )
    {
      checkForUniqueAttribute(schemaAttribute, scimArrayNode, jsonNode);
      handleMultivaluedNode.accept(jsonNode, scimArrayNode);
    }
    AttributeValidator.validateArrayNode(schemaAttribute, scimArrayNode);
    if (scimArrayNode.size() == 0)
    {
      validateNonPresentAttributes(schemaAttribute);
      return Optional.empty();
    }
    return Optional.of(scimArrayNode);
  }

  /**
   * handles a simple json node with a primitive value
   *
   * @param document the document that should be validated
   * @param schemaAttribute the meta information of the attribute
   * @return the attribute if present in the document or an empty else
   */
  private Optional<JsonNode> handleNode(JsonNode document, SchemaAttribute schemaAttribute)
  {
    if (Type.COMPLEX.equals(schemaAttribute.getType()))
    {
      return handleComplexNode(document, schemaAttribute);
    }
    else
    {
      return Optional.of(handleSimpleNode(document, schemaAttribute));
    }
  }

  /**
   * handles a complex json node type. A complex node type has its own meta-attribute array and is itself a full
   * fleshed json document so this method will initiate a recursive call to
   * {@link #validateDocument(Schema, JsonNode)} to do its work
   *
   * @param document the document complex node to validate
   * @param schemaAttribute the meta information of the attribute
   * @return the attribute if present in the document or an empty else
   */
  private Optional<JsonNode> handleComplexNode(JsonNode document, SchemaAttribute schemaAttribute)
  {
    validateIsRequired(document, schemaAttribute);
    List<SchemaAttribute> metaSubAttributes = schemaAttribute.getSubAttributes();
    return Optional.ofNullable(validateAttributes(metaSubAttributes, document, schemaAttribute));
  }

  /**
   * the handling of a simple json node with a primitive type
   *
   * @param simpleDocumentNode the simple value node that should be validated
   * @param schemaAttribute the meta information of the attribute
   * @return the attribute as a {@link JsonNode} that implements the interface {@link ScimNode} in its
   *         corresponding node type
   */
  private JsonNode handleSimpleNode(JsonNode simpleDocumentNode, SchemaAttribute schemaAttribute)
  {
    checkCanonicalValues(schemaAttribute, simpleDocumentNode);
    Type type = schemaAttribute.getType();
    switch (type)
    {
      case STRING:
        isNodeOfExpectedType(schemaAttribute,
                             simpleDocumentNode,
                             jsonNode -> jsonNode.isTextual() || jsonNode.isObject());
        return new ScimTextNode(schemaAttribute, simpleDocumentNode.isTextual() ? simpleDocumentNode.textValue()
          : simpleDocumentNode.toString());
      case BOOLEAN:
        isNodeOfExpectedType(schemaAttribute, simpleDocumentNode, JsonNode::isBoolean);
        return new ScimBooleanNode(schemaAttribute, simpleDocumentNode.booleanValue());
      case INTEGER:
        isNodeOfExpectedType(schemaAttribute,
                             simpleDocumentNode,
                             jsonNode -> jsonNode.isInt() || jsonNode.isLong() || jsonNode.isBigDecimal());
        if (simpleDocumentNode.intValue() == simpleDocumentNode.longValue())
        {
          return new ScimIntNode(schemaAttribute, simpleDocumentNode.intValue());
        }
        else
        {
          return new ScimLongNode(schemaAttribute, simpleDocumentNode.intValue());
        }
      case DECIMAL:
        isNodeOfExpectedType(schemaAttribute,
                             simpleDocumentNode,
                             jsonNode -> jsonNode.isInt() || jsonNode.isLong() || jsonNode.isFloat()
                                         || jsonNode.isDouble() || jsonNode.isBigDecimal());
        return new ScimDoubleNode(schemaAttribute, simpleDocumentNode.doubleValue());
      case DATE_TIME:
        isNodeOfExpectedType(schemaAttribute, simpleDocumentNode, JsonNode::isTextual);
        parseDateTime(simpleDocumentNode.textValue());
        return new ScimTextNode(schemaAttribute, simpleDocumentNode.textValue());
      default:
        isNodeOfExpectedType(schemaAttribute, simpleDocumentNode, JsonNode::isTextual);
        validateValueNodeWithReferenceTypes(schemaAttribute, simpleDocumentNode);
        return new ScimTextNode(schemaAttribute, simpleDocumentNode.textValue());
    }
  }

  /**
   * checks if the given json node is a required node and throws an exception if the required node is not
   * present within the document.
   *
   * @param document the document that should contain the attribute
   * @param schemaAttribute the meta information of the attribute
   */
  private void validateIsRequired(JsonNode document, SchemaAttribute schemaAttribute)
  {
    if (!schemaAttribute.isRequired())
    {
      return;
    }
    if (DirectionType.REQUEST.equals(directionType))
    {
      validateIsRequiredForRequest(document, schemaAttribute);
    }
    else
    {
      validateIsRequiredForResponse(document, schemaAttribute);
    }
  }

  /**
   * checks if the attribute is required in a request
   *
   * @param document the document that should contain the attribute
   * @param schemaAttribute the meta information of the attribute
   */
  private void validateIsRequiredForRequest(JsonNode document, SchemaAttribute schemaAttribute)
  {
    boolean isNodeNull = document == null || document.isNull();
    Supplier<String> errorMessage = () -> "the attribute '" + schemaAttribute.getFullResourceName() + "' is required "
                                          + (httpMethod == null ? "" : "for http method '" + httpMethod + "' ")
                                          + "\n\tmutability: '" + schemaAttribute.getMutability() + "'"
                                          + "\n\treturned: '" + schemaAttribute.getReturned() + "'";
    if ((Mutability.READ_WRITE.equals(schemaAttribute.getMutability())
         || Mutability.WRITE_ONLY.equals(schemaAttribute.getMutability()))
        && isNodeNull)
    {
      throw new DocumentValidationException(errorMessage.get(), null, getHttpStatus(), ScimType.Custom.REQUIRED);
    }
    else if (Mutability.IMMUTABLE.equals(schemaAttribute.getMutability()) && HttpMethod.POST.equals(httpMethod)
             && isNodeNull)
    {
      throw new DocumentValidationException(errorMessage.get(), null, getHttpStatus(), ScimType.Custom.REQUIRED);
    }
  }

  /**
   * checks if the attribute is required in a response
   *
   * @param document the document that should contain the attribute
   * @param schemaAttribute the meta information of the attribute
   */
  private void validateIsRequiredForResponse(JsonNode document, SchemaAttribute schemaAttribute)
  {
    boolean isNodeNull = document == null || document.isNull();
    // @formatter:off
    Supplier<String> errorMessage = () -> String.format("the attribute '%s' is required on response." +
                                                          "\n\t\tname: '%s'" +
                                                          "\n\t\ttype: '%s'" +
                                                          "\n\t\tdescription: '%s'" +
                                                          "\n\t\tmutability: '%s'" +
                                                          "\n\t\treturned: '%s'" +
                                                          "\n\t\tuniqueness: '%s'" +
                                                          "\n\t\tmultivalued: '%s'" +
                                                          "\n\t\trequired: '%s'" +
                                                          "\n\t\tcaseExact: '%s'",
                                                        schemaAttribute.getFullResourceName(),
                                                        schemaAttribute.getName(),
                                                        schemaAttribute.getType().toString(),
                                                        schemaAttribute.getDescription(),
                                                        schemaAttribute.getMutability(),
                                                        schemaAttribute.getReturned(),
                                                        schemaAttribute.getUniqueness().toString(),
                                                        schemaAttribute.isMultiValued(),
                                                        schemaAttribute.isRequired(),
                                                        schemaAttribute.isCaseExact());
    // @formatter:on
    if (isNodeNull && !Mutability.WRITE_ONLY.equals(schemaAttribute.getMutability()))
    {
      throw getException(errorMessage.get(), null);
    }
  }

  /**
   * this method checks if the given array does already contain an equally jsonNode as the given one and throws
   * an exception if the uniqueness is not set to none
   *
   * @param schemaAttribute the attribute definition
   * @param scimArrayNode the scimArrayNode that should not contain any duplicate nodes
   * @param jsonNode the node that should not have any duplicates if the uniqueness has another value than none
   */
  private void checkForUniqueAttribute(SchemaAttribute schemaAttribute, ScimArrayNode scimArrayNode, JsonNode jsonNode)
  {
    if (!Uniqueness.NONE.equals(schemaAttribute.getUniqueness()))
    {
      for ( JsonNode complexNode : scimArrayNode )
      {
        if (complexNode.equals(jsonNode))
        {
          String errorMessage = "the array node with name '" + schemaAttribute.getFullResourceName()
                                + "' has a uniqueness of '" + schemaAttribute.getUniqueness() + "' but "
                                + "has at least one duplicate value: '" + complexNode.toString() + "'";
          throw getException(errorMessage, null);
        }
      }
    }
  }

  /**
   * validates if the missing attribute should be present or not
   *
   * @param schemaAttribute the attribute definition that holds the necessary meta information
   */
  private void validateNonPresentAttributes(SchemaAttribute schemaAttribute)
  {
    if (DirectionType.RESPONSE.equals(directionType))
    {
      validateNonPresentAttributesForResponse(schemaAttribute);
    }
    // in case of request there is nothing to validate here since the validation was already preformed by the
    // isRequired... method
  }

  /**
   * this method is called if the node represented by the schemaAttribute is not present in the document. The
   * validation will simply add log messages for debugging purposes so that the developer will be able to
   * understand what went wrong. The validation is reduced to log messages only because there might be use cases
   * in which an exception would be fatal for the developer
   *
   * @param schemaAttribute the schema attribute definition of a node that is not present within the document
   */
  private void validateNonPresentAttributesForResponse(SchemaAttribute schemaAttribute)
  {
    final String scimNodeName = schemaAttribute.getScimNodeName();
    if (Returned.ALWAYS.equals(schemaAttribute.getReturned()))
    {
      log.debug("the attribute '{}' has a returned value of " + "'{}' and is therefore a required attribute in the"
                + "minimal dataset of the resource but it is missing in the response document.",
                scimNodeName,
                schemaAttribute.getReturned());
    }
    else if ((Returned.REQUEST.equals(schemaAttribute.getReturned())
              || Returned.DEFAULT.equals(schemaAttribute.getReturned()))
             && attributes.stream().anyMatch(s -> StringUtils.equalsIgnoreCase(s, scimNodeName)))
    {
      log.debug("the attribute '{}' was requested by the client but it is not present within the document. "
                + "Maybe the value has not been set on the resource?",
                scimNodeName);
    }
  }

  /**
   * checks if an attribute must be removed from the current document
   *
   * @param schemaAttribute the schema meta definition that holds the necessary information
   * @return true if the attribute should be kept in the response, false if the attribute should be removed
   */
  private boolean validatePresentAttributes(SchemaAttribute schemaAttribute)
  {
    if (DirectionType.RESPONSE.equals(directionType))
    {
      return validatePresentAttributesForResponse(schemaAttribute);
    }
    else if (DirectionType.REQUEST.equals(directionType))
    {
      return validatePresentAttributesForRequest(schemaAttribute);
    }
    // in case for schema validation. in this case the directionType will be null
    return true;
  }

  private boolean validatePresentAttributesForRequest(SchemaAttribute schemaAttribute)
  {
    if (Mutability.READ_ONLY.equals(schemaAttribute.getMutability()))
    {
      log.debug("removed attribute '{}' from request since it has a mutability of {}",
                schemaAttribute.getFullResourceName(),
                schemaAttribute.getMutability());
      return false;
    }
    return true;
  }

  /**
   * checks if an attribute must be removed from the response document
   *
   * @param schemaAttribute the schema meta definition that holds the necessary information
   * @return true if the attribute should be kept in the response, false if the attribute should be removed
   */
  private boolean validatePresentAttributesForResponse(SchemaAttribute schemaAttribute)
  {
    if (Returned.ALWAYS.equals(schemaAttribute.getReturned()))
    {
      return true;
    }
    if (!excludedAttributes.isEmpty() && isExcludedParameterPresent(schemaAttribute))
    {
      return false;
    }
    if (Returned.NEVER.equals(schemaAttribute.getReturned()))
    {
      log.warn("attribute '{}' was present on the response document but has a returned value of '{}'. Attribute is "
               + "being removed from response document",
               schemaAttribute.getFullResourceName(),
               schemaAttribute.getReturned());
      return false;
    }
    if (Returned.DEFAULT.equals(schemaAttribute.getReturned()) && !attributes.isEmpty()
        && isAttributeMissingInAttributeParameter(schemaAttribute) && !isAttributePresentInRequest(schemaAttribute))
    {
      log.trace("removing attribute '{}' from response for its returned value is '{}' and its name is not in the list"
                + " of requested attributes: {}",
                schemaAttribute.getFullResourceName(),
                schemaAttribute.getReturned(),
                attributes);
      return false;
    }
    if (Returned.REQUEST.equals(schemaAttribute.getReturned())
        && isAttributeMissingInAttributeParameter(schemaAttribute) && !isAttributePresentInRequest(schemaAttribute))
    {
      log.trace("removing attribute '{}' from response for its returned value is '{}' and its name is not in the list"
                + " of requested attributes: {}",
                schemaAttribute.getFullResourceName(),
                schemaAttribute.getReturned(),
                attributes);
      return false;
    }
    return true;
  }

  /**
   * will check if the given attribute is set in the excludedAttributes parameter list
   *
   * @param schemaAttribute the attribute to check if it is excluded
   * @return true if the attribute should be excluded, false else
   */
  private boolean isExcludedParameterPresent(SchemaAttribute schemaAttribute)
  {
    final String shortName = schemaAttribute.getScimNodeName();
    final String fullName = schemaAttribute.getResourceUri() + ":" + shortName;
    // this will check if the full name is matching any parameter in the attributes parameter list or
    // if this attribute to check is a subnode of the attributes defined in the attributes parameter list
    boolean anyFullNameMatch = excludedAttributes.stream()
                                                 .anyMatch(param -> StringUtils.equalsIgnoreCase(fullName, param)
                                                                    || StringUtils.equalsIgnoreCase(shortName, param)
                                                                    || StringUtils.equalsIgnoreCase(param,
                                                                                                    schemaAttribute.getResourceUri()));
    return anyFullNameMatch;
  }

  /**
   * checks if the given attribute name is missing within the attributes parameter
   *
   * @param schemaAttribute the schema attribute definition of the parameter
   * @return false if the attribute is present within the attributes parameter, true else
   */
  private boolean isAttributeMissingInAttributeParameter(SchemaAttribute schemaAttribute)
  {
    final String shortName = schemaAttribute.getScimNodeName();
    final String fullName = schemaAttribute.getResourceUri() + ":" + shortName;
    // this will check if the full name is matching any parameter in the attributes parameter list or
    // if this attribute to check is a subnode of the attributes defined in the attributes parameter list
    boolean anyNameMatch = attributes.stream()
                                     .anyMatch(param -> StringUtils.equalsIgnoreCase(fullName, param)
                                                        || StringUtils.equalsIgnoreCase(shortName, param)
                                                        || (StringUtils.startsWithIgnoreCase(fullName, param)
                                                            && StringUtils.endsWithIgnoreCase(fullName,
                                                                                              "." + schemaAttribute.getName()))
                                                        || StringUtils.startsWithIgnoreCase(shortName, param + ".")
                                                        || StringUtils.startsWith(param, fullName + ".")
                                                        || StringUtils.startsWithIgnoreCase(param, shortName + ".")
                                                        || StringUtils.equalsIgnoreCase(param,
                                                                                        schemaAttribute.getResourceUri()));
    return !anyNameMatch;
  }

  /**
   * this method will check if the given attribute was present in the request document.<br>
   * <b>NOTE:</b>:<br>
   * this type of validation is ignored for multivalued complex types because this might lead to drastic
   * performance issues under specific circumstances
   *
   * @param schemaAttribute the meta definition of the attribute
   * @return true if the attribute was present within the response, false else
   */
  private boolean isAttributePresentInRequest(SchemaAttribute schemaAttribute)
  {
    String[] scimNodeParts = schemaAttribute.getScimNodeName().split("\\.");
    if (validatedRequest == null)
    {
      return false;
    }
    JsonNode jsonNode = validatedRequest.get(scimNodeParts[0]);
    if (jsonNode == null)
    {
      return false;
    }

    if (scimNodeParts.length == 1)
    {
      return true;
    }
    else
    {
      ScimNode subNode = (ScimNode)jsonNode.get(scimNodeParts[1]);
      // this case is not validated to reduce the possibility of performance issues
      if (subNode == null)
      {
        return false;
      }
      else
        return !subNode.isMultiValued();
    }
  }

  /**
   * will verify that the current value node does define one of the canonical values of the attribute definition
   * if some are defined
   *
   * @param attributeDefinition the attribute definition from the meta schema
   * @param valueNode the value that matches to this definition
   */
  private void checkCanonicalValues(SchemaAttribute attributeDefinition, JsonNode valueNode)
  {
    if (attributeDefinition.getCanonicalValues().isEmpty())
    {
      // all values are valid
      return;
    }
    final String value = valueNode.textValue();
    if (attributeDefinition.getCanonicalValues().stream().noneMatch(s -> StringUtils.equalsIgnoreCase(s, value)))
    {
      final String errorMessage = "attribute with name '" + attributeDefinition.getName()
                                  + "' does not have one of the " + "canonicalValues: '"
                                  + attributeDefinition.getCanonicalValues() + "' actual value is: '" + value + "'";
      throw getException(errorMessage, null);
    }
  }

  /**
   * checks if the given node is of the expected type
   *
   * @param attributeDefinition the meta attribute definition
   * @param valueNode the current value node that should be checked
   * @param isOfType the check that will validate if the node has the expected type
   */
  private void isNodeOfExpectedType(SchemaAttribute attributeDefinition,
                                    JsonNode valueNode,
                                    Function<JsonNode, Boolean> isOfType)
  {
    Type type = attributeDefinition.getType();
    final String errorMessage = "value of field with name '" + attributeDefinition.getFullResourceName()
                                + "' is not of type '" + type.getValue() + "' but of type: "
                                + StringUtils.lowerCase(valueNode.getNodeType().toString());
    checkAttributeValidity(isOfType.apply(valueNode), errorMessage);
  }

  /**
   * checks if the expression is valid and throws an exception if not
   *
   * @param aBoolean the value of the expression to be checked
   * @param errorMessage the error message to display if the expression is false
   */
  private void checkAttributeValidity(Boolean aBoolean, String errorMessage)
  {
    if (!aBoolean)
    {
      throw getException(errorMessage, null);
    }
  }

  /**
   * tries to parse the given text as a xsd:datetime representation as defined in RFC7643 chapter 2.3.5
   */
  private void parseDateTime(String textValue)
  {
    try
    {
      TimeUtils.parseDateTime(textValue);
    }
    catch (InvalidDateTimeRepresentationException ex)
    {
      throw new DocumentValidationException("given value is not a valid dateTime: " + textValue, null, getHttpStatus(),
                                            null);
    }
  }

  /**
   * validates a simple value node against the valid resource types defined in the meta schema
   *
   * @param attributeDefinition the meta attribute definition
   * @param valueNode the value node
   */
  private void validateValueNodeWithReferenceTypes(SchemaAttribute attributeDefinition, JsonNode valueNode)
  {
    boolean isValidReferenceType = false;
    for ( ReferenceTypes referenceType : attributeDefinition.getReferenceTypes() )
    {
      switch (referenceType)
      {
        case RESOURCE:
        case URI:
          isValidReferenceType = parseUri(valueNode.textValue());
          break;
        default:
          isValidReferenceType = true;
      }
      if (isValidReferenceType)
      {
        break;
      }
    }
    checkAttributeValidity(isValidReferenceType,
                           "given value is not a valid reference type: " + valueNode.textValue()
                                                 + ": was expected to be of one of the following types: "
                                                 + attributeDefinition.getReferenceTypes());
  }

  /**
   * tries to parse the given text into a URI
   */
  private boolean parseUri(String textValue)
  {
    try
    {
      new URI(textValue);
      return true;
    }
    catch (URISyntaxException ex)
    {
      log.debug(ex.getMessage());
      return false;
    }
  }

  /**
   * this method will verify that the meta schema is the correct schema to validate the document. This is done
   * by comparing the "id"-attribute of the metaSchema with the "schemas"-attribute of the document
   *
   * @param metaSchema the meta schema that should be used to validate the document
   * @param document the document that should be validated
   */
  private JsonNode checkDocumentAndMetaSchemaRelationship(Schema metaSchema, JsonNode document)
  {
    final String metaSchemaId = metaSchema.getNonNullId();

    final String schemasAttribute = AttributeNames.RFC7643.SCHEMAS;
    final String documentNoSchemasMessage = "document does not have a '" + schemasAttribute + "'-attribute";
    List<String> documentSchemas = JsonHelper.getSimpleAttributeArray(document, schemasAttribute)
                                             .orElseThrow(() -> getException(documentNoSchemasMessage, null));
    if (!documentSchemas.contains(metaSchemaId))
    {
      final String errorMessage = "document can not be validated against meta-schema with id '" + metaSchemaId
                                  + "' for id is missing in the '" + schemasAttribute + "'-list. The given document "
                                  + "can only be validated against the following schemas: " + documentSchemas;
      throw getException(errorMessage, null);
    }
    log.trace("meta schema with id {} does apply to document with schemas '{}'", metaSchemaId, documentSchemas);
    ScimArrayNode schemasNode = new ScimArrayNode(null);
    schemasNode.addAll(documentSchemas.stream().map(s -> new ScimTextNode(null, s)).collect(Collectors.toList()));
    return schemasNode;
  }

  /**
   * builds an exception
   *
   * @param errorMessage the error message of the exception
   * @param cause the cause of this exception, may be null
   * @return a document validation exception
   */
  private DocumentValidationException getException(String errorMessage, Exception cause)
  {
    return new DocumentValidationException(errorMessage, cause, getHttpStatus(), null);
  }

  /**
   * @return the current http status for this validation
   */
  private Integer getHttpStatus()
  {
    return directionType == null ? HttpStatus.INTERNAL_SERVER_ERROR : directionType.getHttpStatus();
  }

  /**
   * the direction type is used for validation. It tells us how a schema should be validated because there are
   * some differences. The meta-attribute for example is a required attribute for the response and therefore has
   * a readOnly mutability. In order to validate those attribute correctly we need to know if we validate the
   * schema as a request or as a response
   */
  protected enum DirectionType
  {

    REQUEST(HttpStatus.BAD_REQUEST), RESPONSE(HttpStatus.INTERNAL_SERVER_ERROR);

    /**
     * should be interna l server error if the response validation fails and a bad request if the request
     * validation fails
     */
    @Getter(AccessLevel.PRIVATE)
    private int httpStatus;

    DirectionType(int httpStatus)
    {
      this.httpStatus = httpStatus;
    }
  }
}
