/*
 * Copyright (c) 2006-2014 Rogério Liesenfeld
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.integration.testng.internal;

import java.lang.reflect.*;

import org.testng.annotations.*;

import mockit.*;
import mockit.integration.internal.*;
import mockit.internal.*;
import mockit.internal.state.*;
import static mockit.internal.util.StackTrace.*;

import org.jetbrains.annotations.*;
import org.jetbrains.annotations.Nullable;
import org.testng.*;
import org.testng.internal.Parameters;

/**
 * Provides callbacks to be called by the TestNG 5.14+ test runner for each test execution.
 * JMockit will then assert any expectations set during the test, including those specified through {@link Mock} and
 * those recorded in {@link Expectations} subclasses.
 * <p/>
 * This class is not supposed to be accessed from user code; it will be automatically loaded at startup.
 */
public final class TestNGRunnerDecorator extends TestRunnerDecorator
   implements IInvokedMethodListener, IExecutionListener
{
   public static final class MockParameters extends MockUp<Parameters>
   {
      @Mock
      public static void checkParameterTypes(
         String methodName, Class<?>[] parameterTypes, String methodAnnotation, String[] parameterNames) {}

      @Mock
      @Nullable
      public static Object getInjectedParameter(
         @NotNull Invocation invocation, Class<?> c, @Nullable Method method,
         ITestContext context, ITestResult testResult)
      {
         ((BaseInvocation) invocation).prepareToProceed();
         Object value = Parameters.getInjectedParameter(c, method, context, testResult);

         if (value != null) {
            return value;
         }

         if (method == null) {
            // Test execution didn't reach a test method yet.
            return null;
         }

         if (method.getParameterTypes().length == 0) {
            // A test method was reached, but it has no parameters.
            return null;
         }

         if (isMethodWithParametersProvidedByTestNG(method)) {
            // The test method has parameters, but they are to be provided by TestNG, not JMockit.
            return null;
         }

         // It's a mock parameter in a test method, to be provided by JMockit.
         return "";
      }
   }

   private static boolean isMethodWithParametersProvidedByTestNG(@NotNull Method method)
   {
      if (method.isAnnotationPresent(org.testng.annotations.Parameters.class)) {
         return true;
      }

      Test testMetadata = method.getAnnotation(Test.class);

      return testMetadata != null && !testMetadata.dataProvider().isEmpty();
   }

   @NotNull private final ThreadLocal<SavePoint> savePoint;

   public static void registerWithTestNG(@NotNull TestNG testNG)
   {
      Object runnerDecorator = new TestNGRunnerDecorator();
      testNG.addListener(runnerDecorator);
   }

   public TestNGRunnerDecorator()
   {
      savePoint = new ThreadLocal<SavePoint>();
      new MockParameters();
   }

   @Override
   public void beforeInvocation(@NotNull IInvokedMethod invokedMethod, @NotNull ITestResult testResult)
   {
      ITestNGMethod testNGMethod = testResult.getMethod();
      Class<?> testClass = testResult.getTestClass().getRealClass();

      if (!invokedMethod.isTestMethod()) {
         beforeConfigurationMethod(testNGMethod, testClass);
         return;
      }

      Object testInstance = testResult.getInstance();

      if (!testClass.isInstance(testInstance)) {
         // Happens when TestNG is running a JUnit test class, for which "TestResult#getInstance()" erroneously returns
         // a org.junit.runner.Description object.
         return;
      }

      TestRun.enterNoMockingZone();

      try {
         updateTestClassState(testInstance, testClass);
         TestRun.setRunningIndividualTest(testInstance);

         SavePoint testMethodSavePoint = new SavePoint();
         savePoint.set(testMethodSavePoint);

         if (shouldPrepareForNextTest) {
            TestRun.prepareForNextTest();
            shouldPrepareForNextTest = false;
         }

         Method method = testNGMethod.getConstructorOrMethod().getMethod();

         if (!isMethodWithParametersProvidedByTestNG(method)) {
            Object[] parameters = testResult.getParameters();
            Object[] mockParameters = createInstancesForMockParameters(method, parameters);

            if (mockParameters != null) {
               System.arraycopy(mockParameters, 0, parameters, 0, parameters.length);
            }
         }

         createInstancesForTestedFields(testInstance);
      }
      finally {
         TestRun.exitNoMockingZone();
      }
   }

   private void beforeConfigurationMethod(@NotNull ITestNGMethod method, @NotNull Class<?> testClass)
   {
      TestRun.enterNoMockingZone();

      try {
         updateTestClassState(null, testClass);

         if (method.isBeforeMethodConfiguration()) {
            if (shouldPrepareForNextTest) {
               discardTestLevelMockedTypes();
            }

            Object testInstance = method.getInstance();
            updateTestClassState(testInstance, testClass);

            if (shouldPrepareForNextTest) {
               prepareForNextTest();
               shouldPrepareForNextTest = false;
            }

            TestRun.setRunningIndividualTest(testInstance);
         }
         else if (!method.isAfterMethodConfiguration()) {
            TestRun.getExecutingTest().setRecordAndReplay(null);
            cleanUpMocksFromPreviousTestClass();
            TestRun.setRunningIndividualTest(null);
            TestRun.setCurrentTestClass(null);
         }
      }
      finally {
         TestRun.exitNoMockingZone();
      }
   }

   @Override
   public void afterInvocation(@NotNull IInvokedMethod invokedMethod, @NotNull ITestResult testResult)
   {
      if (!invokedMethod.isTestMethod()) {
         afterConfigurationMethod(testResult);
         return;
      }

      TestRun.enterNoMockingZone();

      shouldPrepareForNextTest = true;
      SavePoint testMethodSavePoint = savePoint.get();
      savePoint.set(null);

      Throwable thrownByTest = testResult.getThrowable();

      try {
         if (thrownByTest == null) {
            concludeTestExecutionWithNothingThrown(testMethodSavePoint, testResult);
         }
         else if (thrownByTest instanceof TestException) {
            concludeTestExecutionWithExpectedExceptionNotThrown(invokedMethod, testMethodSavePoint, testResult);
         }
         else if (testResult.isSuccess()) {
            concludeTestExecutionWithExpectedExceptionThrown(testMethodSavePoint, testResult, thrownByTest);
         }
         else {
            concludeTestExecutionWithUnexpectedExceptionThrown(testMethodSavePoint, thrownByTest);
         }
      }
      finally {
         TestRun.finishCurrentTestExecution(false);
         TestRun.exitNoMockingZone();
      }
   }

   private static void afterConfigurationMethod(@NotNull ITestResult testResult)
   {
      TestRun.enterNoMockingZone();

      try {
         ITestNGMethod method = testResult.getMethod();

         if (method.isAfterMethodConfiguration()) {
            Throwable thrownAfterTest = testResult.getThrowable();

            if (thrownAfterTest != null) {
               filterStackTrace(thrownAfterTest);
            }
         }
      }
      finally {
         TestRun.exitNoMockingZone();
      }
   }

   private static void concludeTestExecutionWithNothingThrown(
      @NotNull SavePoint testMethodSavePoint, @NotNull ITestResult testResult)
   {
      try {
         concludeTestMethodExecution(testMethodSavePoint, null, false);
      }
      catch (Throwable t) {
         filterStackTrace(t);
         testResult.setThrowable(t);
         testResult.setStatus(ITestResult.FAILURE);
      }
   }

   private static void concludeTestExecutionWithExpectedExceptionNotThrown(
      @NotNull IInvokedMethod invokedMethod, @NotNull SavePoint testMethodSavePoint, @NotNull ITestResult testResult)
   {
      try {
         concludeTestMethodExecution(testMethodSavePoint, null, false);
      }
      catch (Throwable t) {
         filterStackTrace(t);

         if (isExpectedException(invokedMethod, t)) {
            testResult.setThrowable(null);
            testResult.setStatus(ITestResult.SUCCESS);
         }
         else {
            filterStackTrace(testResult.getThrowable());
         }
      }
   }

   private static void concludeTestExecutionWithExpectedExceptionThrown(
      @NotNull SavePoint testMethodSavePoint, @NotNull ITestResult testResult, @NotNull Throwable thrownByTest)
   {
      filterStackTrace(thrownByTest);

      try {
         concludeTestMethodExecution(testMethodSavePoint, thrownByTest, true);
      }
      catch (Throwable t) {
         if (t != thrownByTest) {
            filterStackTrace(t);
            testResult.setThrowable(t);
            testResult.setStatus(ITestResult.FAILURE);
         }
      }
   }

   private static void concludeTestExecutionWithUnexpectedExceptionThrown(
      @NotNull SavePoint testMethodSavePoint, @NotNull Throwable thrownByTest)
   {
      filterStackTrace(thrownByTest);

      try {
         concludeTestMethodExecution(testMethodSavePoint, thrownByTest, false);
      }
      catch (Throwable ignored) {}
   }

   private static boolean isExpectedException(@NotNull IInvokedMethod invokedMethod, @NotNull Throwable thrownByTest)
   {
      Method testMethod = invokedMethod.getTestMethod().getConstructorOrMethod().getMethod();
      Class<?>[] expectedExceptions = testMethod.getAnnotation(Test.class).expectedExceptions();
      Class<? extends Throwable> thrownExceptionType = thrownByTest.getClass();

      for (Class<?> expectedException : expectedExceptions) {
         if (expectedException.isAssignableFrom(thrownExceptionType)) {
            return true;
         }
      }

      return false;
   }

   @Override
   public void onExecutionStart() {}

   @Override
   public void onExecutionFinish()
   {
      TestRun.enterNoMockingZone();

      try {
         TestRunnerDecorator.cleanUpMocksFromPreviousTestClass();
      }
      finally {
         TestRun.exitNoMockingZone();
      }
   }
}
