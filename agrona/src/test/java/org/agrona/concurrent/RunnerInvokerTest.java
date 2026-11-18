/*
 * Copyright 2014-2025 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.agrona.concurrent;

import org.agrona.ErrorHandler;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static java.nio.ByteBuffer.allocateDirect;
import static org.agrona.concurrent.status.CountersReader.COUNTER_LENGTH;
import static org.agrona.concurrent.status.CountersReader.METADATA_LENGTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RunnerInvokerTest
{
    private static final int NUMBER_OF_COUNTERS = 1;

    private Agent agent;
    private ErrorHandler errorHandler;
    private CountersManager countersManager;
    private AtomicCounter errorCounter;

    @BeforeEach
    void setUp()
    {
        agent = Mockito.mock(Agent.class);
        doReturn("runner-invoker-agent").when(agent).roleName();

        errorHandler = Mockito.mock(ErrorHandler.class);

        final UnsafeBuffer metadataBuffer = new UnsafeBuffer(allocateDirect(NUMBER_OF_COUNTERS * METADATA_LENGTH));
        final UnsafeBuffer valuesBuffer = new UnsafeBuffer(allocateDirect(NUMBER_OF_COUNTERS * COUNTER_LENGTH));
        countersManager = new CountersManager(metadataBuffer, valuesBuffer);

        errorCounter = countersManager.newCounter("error");
    }

    @AfterEach
    void tearDown()
    {
        errorCounter.close();
    }

    @Test
    void startInvoker()
    {
        final AgentInvoker invoker = new AgentInvoker(errorHandler, errorCounter, agent);
        invoker.start();

        assertEquals(true, invoker.isStarted());
        assertEquals(true, invoker.isRunning());
        assertEquals(false, invoker.isClosed());

        invoker.invoke();
        invoker.close();

        verify(agent, times(1)).onStart();
        verify(agent, times(1)).onClose();
        verifyNoInteractions(errorHandler);

        assertEquals(0, errorCounter.get());
    }

    @Test
    void agentInvokerThrowsInterruptedException() throws Exception
    {
        final InterruptedException exception = new InterruptedException();
        doThrow(exception).when(agent).doWork();

        final AgentInvoker invoker = new AgentInvoker(errorHandler, errorCounter, agent);

        invoker.start();
        invoker.invoke();

        verify(agent, times(1)).onStart();
        verify(agent, times(1)).onClose();
        verifyNoInteractions(errorHandler);

        assertEquals(true, invoker.isStarted());
        assertEquals(false, invoker.isRunning());
        assertEquals(true, invoker.isClosed());
    }

    @Test
    void agentRunnerThrowsInterruptedException() throws Exception
    {
        final InterruptedException exception = new InterruptedException();
        doThrow(exception).when(agent).doWork();

        final AgentRunner runner = new AgentRunner(YieldingIdleStrategy.INSTANCE, errorHandler, errorCounter, agent);
        final Thread thread = AgentRunner.startOnThread(runner);
        thread.join();

        verify(agent, times(1)).onStart();
        verify(agent, times(1)).onClose();
        verifyNoInteractions(errorHandler);

        assertEquals(true, runner.isClosed());
    }

    @Test
    void invokerThrowError() throws Exception
    {
        final Error error = new Error();
        doThrow(error).when(agent).doWork();

        final AgentInvoker invoker = new AgentInvoker(errorHandler, errorCounter, agent);
        invoker.start();

        final Error thrownError = assertThrows(Error.class, invoker::invoke);
        assertEquals(error, thrownError);

        verify(agent, times(1)).onStart();
        verify(agent, times(1)).doWork();
        verify(agent, never()).onClose();
        verifyNoMoreInteractions(agent);

        verify(errorHandler).onError(error);
        verifyNoMoreInteractions(errorHandler);

        assertEquals(true, invoker.isRunning());
        assertEquals(false, invoker.isClosed());

        invoker.close();

        verify(agent, times(1)).onClose();
        verifyNoMoreInteractions(agent);
        verifyNoMoreInteractions(errorHandler);

        assertEquals(false, invoker.isRunning());
        assertEquals(true, invoker.isClosed());
    }

    @Test
    void runnerThrowsError() throws Exception
    {
        final Error error = new Error();
        doThrow(error).when(agent).doWork();

        final AgentRunner runner = new AgentRunner(YieldingIdleStrategy.INSTANCE, errorHandler, errorCounter, agent);
        final Thread thread = AgentRunner.startOnThread(runner);
        thread.join();

        verify(agent, times(1)).onStart();
        verify(agent, times(1)).doWork();
        verify(agent, times(1)).roleName();
        verify(agent, never()).onClose();
        verifyNoMoreInteractions(agent);

        verify(errorHandler).onError(error);
        verifyNoMoreInteractions(errorHandler);

        assertEquals(true, runner.isRunning());
        assertEquals(true, runner.isClosed()); // FIXME - this is different from invoker

        runner.close();

        // FIXME which means that closing runner doesn't call Agent#onClose
        // verify(agent, times(1)).onClose();
        verifyNoMoreInteractions(agent);
        verifyNoMoreInteractions(errorHandler);

        assertEquals(true, runner.isRunning()); // FIXME this is different
        assertEquals(true, runner.isClosed());
    }
}
