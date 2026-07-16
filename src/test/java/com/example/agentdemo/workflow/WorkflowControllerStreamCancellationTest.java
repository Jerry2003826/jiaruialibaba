package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowControllerStreamCancellationTest {

    @Test
    void streamTerminationCancelsAndInterruptsBackgroundGeneration() throws Exception {
        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicReference<FutureTask<Void>> taskRef = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000L);
            }
            catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return null;
        });
        taskRef.set(task);
        Thread worker = Thread.ofPlatform().start(task);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        WorkflowController.cancelStreamTask(terminal, taskRef);

        worker.join(2_000L);
        assertThat(terminal).isTrue();
        assertThat(task.isCancelled()).isTrue();
        assertThat(interrupted).isTrue();
        assertThat(worker.isAlive()).isFalse();
    }
}
