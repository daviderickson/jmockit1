/*
 * Copyright (c) 2006-2015 Rogério Liesenfeld
 * This file is subject to the terms of the MIT license (see LICENSE.txt).
 */
package mockit.integration.internal;

import java.lang.reflect.*;

import org.jetbrains.annotations.*;

import mockit.internal.*;
import mockit.internal.expectations.*;
import mockit.internal.expectations.injection.*;
import mockit.internal.expectations.mocking.*;
import mockit.internal.mockups.*;
import mockit.internal.state.*;
import mockit.internal.util.*;

/**
 * Base class for "test runner decorators", which provide integration between JMockit and specific
 * test runners from JUnit and TestNG.
 */
public class TestRunnerDecorator
{
   //@Nullable private static SavePoint savePointForTestClass;
   //@Nullable private static SavePoint savePointForTest;
   private static ThreadLocal<SavePoint> savePointForTestClass = new ThreadLocal<SavePoint>();
   private static ThreadLocal<SavePoint> savePointForTest = new ThreadLocal<SavePoint>();

   /**
    * A "volatile boolean" is as good as a java.util.concurrent.atomic.AtomicBoolean here,
    * since we only need the basic get/set operations.
    */
   protected volatile boolean shouldPrepareForNextTest;

   protected TestRunnerDecorator() { shouldPrepareForNextTest = true; }

   protected static void updateTestClassState(@Nullable Object target, @NotNull Class<?> testClass)
   {
      try {
         handleSwitchToNewTestClassIfApplicable(testClass);

         if (target != null) {
            handleMockFieldsForWholeTestClass(target);
         }
      }
      catch (Error e) {
         try {
            rollbackForTestClass();
         }
         catch (Error err) {
            StackTrace.filterStackTrace(err);
            throw err;
         }

         throw e;
      }
      catch (RuntimeException e) {
         rollbackForTestClass();
         StackTrace.filterStackTrace(e);
         throw e;
      }
   }

   private static void handleSwitchToNewTestClassIfApplicable(@NotNull Class<?> testClass)
   {
      Class<?> currentTestClass = TestRun.getCurrentTestClass();

      if (testClass != currentTestClass) {
         if (currentTestClass == null) {
            savePointForTestClass.set(new SavePoint());
         }
         else if (!currentTestClass.isAssignableFrom(testClass)) {
            cleanUpMocksFromPreviousTestClass();
            savePointForTestClass.set(new SavePoint());
         }

         TestRun.setCurrentTestClass(testClass);
      }
   }

   public static void cleanUpMocksFromPreviousTestClass() { cleanUpMocks(true); }
   public static void cleanUpMocksFromPreviousTest() { cleanUpMocks(false); }

   private static void cleanUpMocks(boolean forTestClassAsWell)
   {
      discardTestLevelMockedTypes();

      if (forTestClassAsWell) {
         rollbackForTestClass();
      }

      TypeRedefinitions fieldTypeRedefinitions = TestRun.getFieldTypeRedefinitions();

      if (fieldTypeRedefinitions != null) {
         fieldTypeRedefinitions.cleanUp();
         TestRun.setFieldTypeRedefinitions(null);
      }
   }

   private static void rollbackForTestClass()
   {
      SavePoint savePoint = savePointForTestClass.get();

      if (savePoint != null) {
         savePoint.rollback();
         savePointForTestClass.set(null);
      }
   }

   protected static void prepareForNextTest()
   {
      if (savePointForTest.get() == null) {
         savePointForTest.set(new SavePoint());
      }

      TestRun.prepareForNextTest();
   }

   protected static void discardTestLevelMockedTypes()
   {
      SavePoint savePoint = savePointForTest.get();

      if (savePoint != null) {
         savePoint.rollback();
         savePointForTest.set(null);
      }
   }

   private static void handleMockFieldsForWholeTestClass(@NotNull Object target)
   {
      FieldTypeRedefinitions fieldTypeRedefinitions = TestRun.getFieldTypeRedefinitions();

      if (fieldTypeRedefinitions == null) {
         fieldTypeRedefinitions = new FieldTypeRedefinitions(target);
         TestRun.setFieldTypeRedefinitions(fieldTypeRedefinitions);
      }

      //noinspection ObjectEquality
      if (target != TestRun.getCurrentTestInstance()) {
         fieldTypeRedefinitions.assignNewInstancesToMockFields(target);
      }
   }

   protected static void createInstancesForTestedFields(@NotNull Object target, boolean beforeSetup)
   {
      FieldTypeRedefinitions fieldTypeRedefinitions = TestRun.getFieldTypeRedefinitions();

      if (fieldTypeRedefinitions != null) {
         TestedClassInstantiations testedClasses = fieldTypeRedefinitions.getTestedClassInstantiations();

         if (testedClasses != null) {
            TestRun.enterNoMockingZone();

            try {
               testedClasses.assignNewInstancesToTestedFields(target, beforeSetup);
            }
            finally {
               TestRun.exitNoMockingZone();
            }
         }
      }
   }

   @Nullable
   protected static Object[] createInstancesForMockParameters(
      @NotNull Method testMethod, @Nullable Object[] parameterValues)
   {
      if (testMethod.getParameterTypes().length == 0) {
         return null;
      }

      TestRun.enterNoMockingZone();

      try {
         ParameterTypeRedefinitions redefinitions = new ParameterTypeRedefinitions(testMethod, parameterValues);
         TestRun.getExecutingTest().setParameterTypeRedefinitions(redefinitions);

         return redefinitions.getParameterValues();
      }
      finally {
         TestRun.exitNoMockingZone();
      }
   }

   protected static void concludeTestMethodExecution(
      @NotNull SavePoint savePoint, @Nullable Throwable thrownByTest, boolean thrownAsExpected)
      throws Throwable
   {
      TestRun.enterNoMockingZone();

      Error expectationsFailure = RecordAndReplayExecution.endCurrentReplayIfAny();
      MockStates mockStates = TestRun.getMockStates();

      try {
         clearTestedFieldsIfAny();

         if (expectationsFailure == null && (thrownByTest == null || thrownAsExpected)) {
            mockStates.verifyMissingInvocations();
         }
      }
      finally {
         mockStates.resetExpectations();
         savePoint.rollback();
         TestRun.exitNoMockingZone();
      }

      if (thrownByTest != null) {
         if (expectationsFailure == null || !thrownAsExpected || isUnexpectedOrMissingInvocation(thrownByTest)) {
            throw thrownByTest;
         }

         Throwable expectationsFailureCause = expectationsFailure.getCause();

         if (expectationsFailureCause != null) {
            expectationsFailureCause.initCause(thrownByTest);
         }
      }

      if (expectationsFailure != null) {
         throw expectationsFailure;
      }
   }

   private static void clearTestedFieldsIfAny()
   {
      FieldTypeRedefinitions fieldTypeRedefinitions = TestRun.getFieldTypeRedefinitions();

      if (fieldTypeRedefinitions != null) {
         TestedClassInstantiations testedClasses = fieldTypeRedefinitions.getTestedClassInstantiations();

         if (testedClasses != null) {
            testedClasses.clearTestedFields();
         }
      }
   }

   private static boolean isUnexpectedOrMissingInvocation(@NotNull Throwable error)
   {
      Class<?> errorType = error.getClass();
      return errorType == UnexpectedInvocation.class || errorType == MissingInvocation.class;
   }
}
