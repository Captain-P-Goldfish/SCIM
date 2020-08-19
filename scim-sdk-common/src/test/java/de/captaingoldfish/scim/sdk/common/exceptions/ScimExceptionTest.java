package de.captaingoldfish.scim.sdk.common.exceptions;

import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.util.ConfigurationBuilder;

import lombok.extern.slf4j.Slf4j;


/**
 * author Pascal Knueppel <br>
 * created at: 28.09.2019 - 15:43 <br>
 * <br>
 */
@Slf4j
public class ScimExceptionTest
{

  /**
   * extracts all exceptions defined in the exceptions package
   */
  public static Stream<Arguments> getExceptions()
  {
    final String exceptionPackage = ScimException.class.getPackage().getName();
    final String resourcePath = "/" + exceptionPackage.replaceAll("\\.", "/");
    final URL exceptionPackageUrl = ScimException.class.getResource(resourcePath);
    final URL mainExceptionPackageUrl;
    try
    {
      mainExceptionPackageUrl = new URL(exceptionPackageUrl.toString().replace("test-classes", "classes"));
    }
    catch (MalformedURLException e)
    {
      throw new IllegalStateException(e.getMessage(), e);
    }
    Assertions.assertNotNull(exceptionPackageUrl);
    Reflections reflections = new Reflections(new ConfigurationBuilder().setUrls(mainExceptionPackageUrl)
                                                                        .setScanners(new SubTypesScanner()));
    Set<Class<? extends Exception>> subTypes = reflections.getSubTypesOf(Exception.class);
    subTypes.removeIf(RuntimeException.class::equals);
    List<Arguments> argumentList = new ArrayList<>();
    subTypes.forEach(aClass -> argumentList.add(Arguments.of(aClass)));
    return Stream.of(argumentList.toArray(new Arguments[0]));
  }

  /**
   * this test will assert that all exception within the exception package do extend the class
   * {@link ScimException}
   */
  @ParameterizedTest
  @MethodSource("getExceptions")
  public void testAllExceptionsExtendScimException(Class exceptionType)
  {
    log.info("checking exception of type: {}", exceptionType);
    Assertions.assertTrue(RuntimeException.class.isAssignableFrom(exceptionType),
                          exceptionType + " is not a " + RuntimeException.class);
    Assertions.assertTrue(ScimException.class.isAssignableFrom(exceptionType),
                          exceptionType + " is not a " + ScimException.class);
  }

  /**
   * will verify that the {@link ScimException} which is the base exception of scim errors is abstract
   */
  @Test
  public void testScimExceptionIsAbstract()
  {
    Assertions.assertTrue(Modifier.isAbstract(ScimException.class.getModifiers()));
  }

}
