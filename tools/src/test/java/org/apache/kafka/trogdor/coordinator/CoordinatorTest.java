/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.trogdor.coordinator;

import org.apache.kafka.common.utils.MockScheduler;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.common.utils.Scheduler;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.test.TestCondition;
import org.apache.kafka.test.TestUtils;
import org.apache.kafka.trogdor.agent.AgentClient;
import org.apache.kafka.trogdor.common.CapturingCommandRunner;
import org.apache.kafka.trogdor.common.ExpectedTasks;
import org.apache.kafka.trogdor.common.ExpectedTasks.ExpectedTaskBuilder;
import org.apache.kafka.trogdor.common.MiniTrogdorCluster;

import org.apache.kafka.trogdor.fault.NetworkPartitionFaultSpec;
import org.apache.kafka.trogdor.rest.CoordinatorStatusResponse;
import org.apache.kafka.trogdor.rest.CreateTaskRequest;
import org.apache.kafka.trogdor.rest.StopTaskRequest;
import org.apache.kafka.trogdor.rest.TaskDone;
import org.apache.kafka.trogdor.rest.TaskPending;
import org.apache.kafka.trogdor.rest.TaskRunning;
import org.apache.kafka.trogdor.rest.WorkerDone;
import org.apache.kafka.trogdor.rest.WorkerRunning;
import org.apache.kafka.trogdor.task.NoOpTaskSpec;
import org.junit.Rule;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CoordinatorTest {
    private static final Logger log = LoggerFactory.getLogger(CoordinatorTest.class);

    @Rule
    final public Timeout globalTimeout = Timeout.millis(120000);

    @Test
    public void testCoordinatorStatus() throws Exception {
        try (MiniTrogdorCluster cluster = new MiniTrogdorCluster.Builder().
                addCoordinator("node01").
                build()) {
            CoordinatorStatusResponse status = cluster.coordinatorClient().status();
            assertEquals(cluster.coordinator().status(), status);
        }
    }

    @Test
    public void testCreateTask() throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        Scheduler scheduler = new MockScheduler(time);
        try (MiniTrogdorCluster cluster = new MiniTrogdorCluster.Builder().
                addCoordinator("node01").
                addAgent("node02").
                scheduler(scheduler).
                build()) {
            new ExpectedTasks().waitFor(cluster.coordinatorClient());

            NoOpTaskSpec fooSpec = new NoOpTaskSpec(1, 2);
            cluster.coordinatorClient().createTask(
                new CreateTaskRequest("foo", fooSpec));
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").
                    taskState(new TaskPending(fooSpec)).
                    build()).
                waitFor(cluster.coordinatorClient());

            time.sleep(2);
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").
                    taskState(new TaskRunning(fooSpec, 2)).
                    workerState(new WorkerRunning(fooSpec, 2, "")).
                    build()).
                waitFor(cluster.coordinatorClient()).
                waitFor(cluster.agentClient("node02"));

            time.sleep(3);
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").
                    taskState(new TaskDone(fooSpec, 2, 5, "", false)).
                    build()).
                waitFor(cluster.coordinatorClient());
        }
    }

    @Test
    public void testTaskDistribution() throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        Scheduler scheduler = new MockScheduler(time);
        try (MiniTrogdorCluster cluster = new MiniTrogdorCluster.Builder().
                addCoordinator("node01").
                addAgent("node01").
                addAgent("node02").
                scheduler(scheduler).
                build()) {
            CoordinatorClient coordinatorClient = cluster.coordinatorClient();
            AgentClient agentClient1 = cluster.agentClient("node01");
            AgentClient agentClient2 = cluster.agentClient("node02");

            new ExpectedTasks().
                waitFor(coordinatorClient).
                waitFor(agentClient1).
                waitFor(agentClient2);

            NoOpTaskSpec fooSpec = new NoOpTaskSpec(5, 2);
            coordinatorClient.createTask(new CreateTaskRequest("foo", fooSpec));
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").taskState(new TaskPending(fooSpec)).build()).
                waitFor(coordinatorClient).
                waitFor(agentClient1).
                waitFor(agentClient2);

            time.sleep(11);
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").
                    taskState(new TaskRunning(fooSpec, 11)).
                    workerState(new WorkerRunning(fooSpec, 11, "")).
                    build()).
                waitFor(coordinatorClient).
                waitFor(agentClient1).
                waitFor(agentClient2);

            time.sleep(2);
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").
                    taskState(new TaskDone(fooSpec, 11, 13, "", false)).
                    workerState(new WorkerDone(fooSpec, 11, 13, "", "")).
                    build()).
                waitFor(coordinatorClient).
                waitFor(agentClient1).
                waitFor(agentClient2);
        }
    }

    @Test
    public void testTaskCancellation() throws Exception {
        MockTime time = new MockTime(0, 0, 0);
        Scheduler scheduler = new MockScheduler(time);
        try (MiniTrogdorCluster cluster = new MiniTrogdorCluster.Builder().
            addCoordinator("node01").
            addAgent("node01").
            addAgent("node02").
            scheduler(scheduler).
            build()) {
            CoordinatorClient coordinatorClient = cluster.coordinatorClient();
            AgentClient agentClient1 = cluster.agentClient("node01");
            AgentClient agentClient2 = cluster.agentClient("node02");

            new ExpectedTasks().
                waitFor(coordinatorClient).
                waitFor(agentClient1).
                waitFor(agentClient2);

            NoOpTaskSpec fooSpec = new NoOpTaskSpec(5, 2);
            coordinatorClient.createTask(new CreateTaskRequest("foo", fooSpec));
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").taskState(new TaskPending(fooSpec)).build()).
                waitFor(coordinatorClient).
                waitFor(agentClient1).
                waitFor(agentClient2);

            time.sleep(11);
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").
                    taskState(new TaskRunning(fooSpec, 11)).
                    workerState(new WorkerRunning(fooSpec, 11, "")).
                    build()).
                waitFor(coordinatorClient).
                waitFor(agentClient1).
                waitFor(agentClient2);

            time.sleep(1);
            coordinatorClient.stopTask(new StopTaskRequest("foo"));
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("foo").
                    taskState(new TaskDone(fooSpec, 11, 12, "", true)).
                    workerState(new WorkerDone(fooSpec, 11, 12, "", "")).
                    build()).
                waitFor(coordinatorClient).
                waitFor(agentClient1).
                waitFor(agentClient2);
        }
    }

    public static class ExpectedLines {
        List<String> expectedLines = new ArrayList<>();

        public ExpectedLines addLine(String line) {
            expectedLines.add(line);
            return this;
        }

        public ExpectedLines waitFor(final String nodeName,
                final CapturingCommandRunner runner) throws InterruptedException {
            TestUtils.waitForCondition(new TestCondition() {
                @Override
                public boolean conditionMet() {
                    return linesMatch(nodeName, runner.lines(nodeName));
                }
            }, "failed to find the expected lines " + this.toString());
            return this;
        }

        private boolean linesMatch(final String nodeName, List<String> actualLines) {
            int matchIdx = 0, i = 0;
            while (true) {
                if (matchIdx == expectedLines.size()) {
                    log.debug("Got expected lines for {}", nodeName);
                    return true;
                }
                if (i == actualLines.size()) {
                    log.info("Failed to find the expected lines for {}.  First " +
                        "missing line on index {}: {}",
                        nodeName, matchIdx, expectedLines.get(matchIdx));
                    return false;
                }
                String actualLine = actualLines.get(i++);
                String expectedLine = expectedLines.get(matchIdx);
                if (expectedLine.equals(actualLine)) {
                    matchIdx++;
                } else {
                    log.trace("Expected:\n'{}', Got:\n'{}'", expectedLine, actualLine);
                    matchIdx = 0;
                }
            }
        }

        @Override
        public String toString() {
            return Utils.join(expectedLines, ", ");
        }
    }

    private static List<List<String>> createPartitionLists(String[][] array) {
        List<List<String>> list = new ArrayList<>();
        for (String[] a : array) {
            list.add(Arrays.asList(a));
        }
        return list;
    }

    @Test
    public void testNetworkPartitionFault() throws Exception {
        CapturingCommandRunner runner = new CapturingCommandRunner();
        try (MiniTrogdorCluster cluster = new MiniTrogdorCluster.Builder().
                addCoordinator("node01").
                addAgent("node01").
                addAgent("node02").
                addAgent("node03").
                commandRunner(runner).
                build()) {
            CoordinatorClient coordinatorClient = cluster.coordinatorClient();
            NetworkPartitionFaultSpec spec = new NetworkPartitionFaultSpec(0, Long.MAX_VALUE,
                createPartitionLists(new String[][] {
                    new String[] {"node01", "node02"},
                    new String[] {"node03"},
                }));
            coordinatorClient.createTask(new CreateTaskRequest("netpart", spec));
            new ExpectedTasks().
                addTask(new ExpectedTaskBuilder("netpart").taskSpec(spec).build()).
                waitFor(coordinatorClient);
            checkLines("-A", runner);
        }
        checkLines("-D", runner);
    }

    private void checkLines(String prefix, CapturingCommandRunner runner) throws InterruptedException {
        new ExpectedLines().
            addLine("sudo iptables " + prefix + " INPUT -p tcp -s 127.0.0.1 -j DROP " +
                "-m comment --comment node03").
            waitFor("node01", runner);
        new ExpectedLines().
            addLine("sudo iptables " + prefix + " INPUT -p tcp -s 127.0.0.1 -j DROP " +
                "-m comment --comment node03").
            waitFor("node02", runner);
        new ExpectedLines().
            addLine("sudo iptables " + prefix + " INPUT -p tcp -s 127.0.0.1 -j DROP " +
                "-m comment --comment node01").
            addLine("sudo iptables " + prefix + " INPUT -p tcp -s 127.0.0.1 -j DROP " +
                "-m comment --comment node02").
            waitFor("node03", runner);
    }
};
