// Copyright 2015 The Bazel Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.testing.junit.runner.model;

import static com.google.testing.junit.runner.util.TestPropertyExporter.INITIAL_INDEX_FOR_REPEATED_PROPERTY;

import com.google.testing.junit.runner.model.TestResult.Status;
import com.google.testing.junit.runner.util.TestPropertyExporter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;
import org.junit.runner.Description;

/**
 * A leaf in the test suite model.
 */
class TestCaseNode extends TestNode implements TestPropertyExporter.Callback {
  private final TestSuiteNode parent;
  private final Map<String, String> properties = new ConcurrentHashMap<>();
  private final Map<String, Integer> repeatedPropertyNamesToRepetitions = new HashMap<>();
  private final Queue<Throwable> globalFailures = new ConcurrentLinkedQueue<>();
  private final ConcurrentMap<Description, List<Throwable>> dynamicTestToFailures =
      new ConcurrentHashMap<>();

  @Nullable private volatile TestInterval runTimeInterval = null;
  private volatile State state = State.INITIAL;

  TestCaseNode(Description description, TestSuiteNode parent) {
    super(description);
    this.parent = parent;
  }

  // VisibleForTesting
  @Override
  public List<TestNode> getChildren() {
    return Collections.emptyList();
  }


  /**
   * Indicates that the test represented by this node has started.
   *
   * @param now Time that the test started
   */
  public void started(long now) {
    compareAndSetState(State.INITIAL, State.STARTED, now);
  }

  @Override
  public void testInterrupted(long now) {
    if (compareAndSetState(State.STARTED, State.INTERRUPTED, now)) {
      return;
    }
    compareAndSetState(State.INITIAL, State.CANCELLED, now);
  }

  @Override
  public void exportProperty(String name, String value) {
    properties.put(name, value);
  }

  @Override
  public String exportRepeatedProperty(String name, String value) {
    String propertyName = getRepeatedPropertyName(name);
    properties.put(propertyName, value);
    return propertyName;
  }

  @Override
  public void testSkipped(long now) {
    compareAndSetState(State.STARTED, State.SKIPPED, now);
  }


  @Override
  public void testSuppressed(long now) {
    compareAndSetState(State.INITIAL, State.SUPPRESSED, now);
  }

  /**
   * Indicates that the test represented by this node has finished.
   *
   * @param now Time that the test finished
   */
  public void finished(long now) {
    compareAndSetState(State.STARTED, State.FINISHED, now);
  }

  @Override
  public void testFailure(Throwable throwable, long now) {
    compareAndSetState(State.INITIAL, State.FINISHED, now);
    globalFailures.add(throwable);
  }

  @Override
  public void dynamicTestFailure(Description test, Throwable throwable, long now) {
    compareAndSetState(State.INITIAL, State.FINISHED, now);
    addThrowableToDynamicTestToFailures(test, throwable);
  }

  private String getRepeatedPropertyName(String name) {
    int index = addNameToRepeatedPropertyNamesAndGetRepetitionsNr(name)
        + INITIAL_INDEX_FOR_REPEATED_PROPERTY;
    return name + index;
  }

  @Override
  public boolean isTestCase() {
    return true;
  }

  private synchronized void addThrowableToDynamicTestToFailures(
      Description test, Throwable throwable) {
    List<Throwable> throwables = dynamicTestToFailures.get(test);
    if (throwables == null) {
      throwables = new ArrayList<>();
      dynamicTestToFailures.put(test, throwables);
    }
    throwables.add(throwable);
  }

  private synchronized int addNameToRepeatedPropertyNamesAndGetRepetitionsNr(String name) {
    Integer previousRepetitionsNr = repeatedPropertyNamesToRepetitions.get(name);
    if (previousRepetitionsNr == null) {
      previousRepetitionsNr = 0;
    }
    repeatedPropertyNamesToRepetitions.put(name, previousRepetitionsNr + 1);
    return previousRepetitionsNr;
  }

  private synchronized boolean compareAndSetState(State fromState, State toState, long now) {
    if (fromState == null || toState == null || state == null) {
      throw new NullPointerException();
    }

    if (fromState == state && toState != state) {
      state = toState;
      runTimeInterval =
          runTimeInterval == null ? new TestInterval(now, now) : runTimeInterval.withEndMillis(now);
      return true;
    }
    return false;
  }

  @Nullable
  public TestInterval getRuntime() {
    return runTimeInterval;
  }

  /**
   * @return The equivalent {@link TestResult.Status} if the test execution ends with the FSM
   *      at this state.
   */
  public TestResult.Status getTestResultStatus() {
    return state.getTestResultStatus();
  }

  @Override
  protected TestResult buildResult() {
    // Some test descriptions, like those provided by JavaScript tests, are
    // constructed by Description.createSuiteDescription, not
    // createTestDescription, because they don't have a "class" per se.
    // In this case, getMethodName returns null and we fill in the className
    // attribute with the name of the parent test suite.
    String name = getDescription().getMethodName();
    String className = getDescription().getClassName();
    if (name == null) {
      name = className;
      className = parent.getDescription().getDisplayName();
    }

    // For now, we give each dynamic test an empty properties map and the same
    // run time and status as its parent test case, but this may change.
    List<TestResult> childResults = new ArrayList<>();
    for (Description dynamicTest : getDescription().getChildren()) {
      childResults.add(buildDynamicResult(dynamicTest, getRuntime(), getTestResultStatus()));
    }

    int numTests = getDescription().isTest() ? 1 : getDescription().getChildren().size();
    int numFailures = globalFailures.isEmpty() ? dynamicTestToFailures.keySet().size() : numTests;
    return new TestResult.Builder()
        .name(name)
        .className(className)
        .properties(properties)
        .failures(new ArrayList<>(globalFailures))
        .runTimeInterval(getRuntime())
        .status(getTestResultStatus())
        .numTests(numTests)
        .numFailures(numFailures)
        .childResults(childResults)
        .build();
  }

  private TestResult buildDynamicResult(
      Description test, @Nullable TestInterval runTime, TestResult.Status status) {
    // The dynamic test fails if the testcase itself fails or there is
    // a dynamic failure specifically for the dynamic test.
    List<Throwable> dynamicFailures = dynamicTestToFailures.get(test);
    if (dynamicFailures == null) {
      dynamicFailures = new ArrayList<>();
    }
    boolean failed = !globalFailures.isEmpty() || !dynamicFailures.isEmpty();
    return new TestResult.Builder()
        .name(test.getDisplayName())
        .className(getDescription().getDisplayName())
        .properties(Collections.<String, String>emptyMap())
        .failures(dynamicFailures)
        .runTimeInterval(runTime)
        .status(status)
        .numTests(1)
        .numFailures(failed ? 1 : 0)
        .childResults(Collections.<TestResult>emptyList())
        .build();
  }

  /**
   * States of a TestCaseNode (see (link) for all the transitions and states descriptions).
   */
  private static enum State {
    INITIAL(TestResult.Status.SKIPPED),
    STARTED(TestResult.Status.INTERRUPTED),
    SKIPPED(TestResult.Status.SKIPPED),
    SUPPRESSED(TestResult.Status.SUPPRESSED),
    CANCELLED(TestResult.Status.CANCELLED),
    INTERRUPTED(TestResult.Status.INTERRUPTED),
    FINISHED(TestResult.Status.COMPLETED);

    private final Status status;

    State(TestResult.Status status) {
      this.status = status;
    }

    /**
     * @return The equivalent {@link TestResult.Status} if the test execution ends with the FSM
     *      at this state.
     */
    public TestResult.Status getTestResultStatus() {
      return status;
    }
  }
}
