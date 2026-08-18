package vip.mate.channel.webchat;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

final class WebChatDisconnectTestSupport {

    private WebChatDisconnectTestSupport() {
    }

    static ExecutorService swapExecutor(WebChatController controller,
                                        ExecutorService replacement) throws Exception {
        Field field = WebChatController.class.getDeclaredField("sseExecutor");
        field.setAccessible(true);
        ExecutorService original = (ExecutorService) field.get(controller);
        field.set(controller, replacement);
        return original;
    }

    static void fireCompletion(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("completionCallback");
        field.setAccessible(true);
        ((Runnable) field.get(emitter)).run();
    }

    static final class QueuedExecutorService extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            var remaining = List.copyOf(tasks);
            tasks.clear();
            return remaining;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            Runnable task = tasks.poll();
            if (task == null) {
                throw new AssertionError("expected a queued SSE worker");
            }
            task.run();
        }
    }
}
