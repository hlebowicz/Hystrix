package com.netflix.hystrix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.hamcrest.core.Is.is;

import com.netflix.hystrix.HystrixThreadPool.Factory;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.concurrency.*;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisher;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisherFactory;
import com.netflix.hystrix.strategy.metrics.HystrixMetricsPublisherThreadPool;

import org.junit.Before;
import org.junit.Test;

import rx.Scheduler;
import rx.functions.Action0;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class HystrixThreadPoolTest {
    @Before
    public void setup() {
        Hystrix.reset();
    }

    @Test
    public void testShutdown() {
        // other unit tests will probably have run before this so get the count
        int count = Factory.threadPools.size();

        HystrixThreadPool pool = Factory.getInstance(HystrixThreadPoolKey.Factory.asKey("threadPoolFactoryTest"),
                HystrixThreadPoolProperties.Setter.getUnitTestPropertiesBuilder());

        assertEquals(count + 1, Factory.threadPools.size());
        assertFalse(pool.getExecutor().isShutdown());

        Factory.shutdown();

        // ensure all pools were removed from the cache
        assertEquals(0, Factory.threadPools.size());
        assertTrue(pool.getExecutor().isShutdown());
    }

    @Test
    public void testShutdownWithWait() {
        // other unit tests will probably have run before this so get the count
        int count = Factory.threadPools.size();

        HystrixThreadPool pool = Factory.getInstance(HystrixThreadPoolKey.Factory.asKey("threadPoolFactoryTest"),
                HystrixThreadPoolProperties.Setter.getUnitTestPropertiesBuilder());

        assertEquals(count + 1, Factory.threadPools.size());
        assertFalse(pool.getExecutor().isShutdown());

        Factory.shutdown(1, TimeUnit.SECONDS);

        // ensure all pools were removed from the cache
        assertEquals(0, Factory.threadPools.size());
        assertTrue(pool.getExecutor().isShutdown());
    }

    private static class HystrixMetricsPublisherThreadPoolContainer implements HystrixMetricsPublisherThreadPool {
        private final HystrixThreadPoolMetrics hystrixThreadPoolMetrics;

        private HystrixMetricsPublisherThreadPoolContainer(HystrixThreadPoolMetrics hystrixThreadPoolMetrics) {
            this.hystrixThreadPoolMetrics = hystrixThreadPoolMetrics;
        }

        @Override
        public void initialize() {
        }

        public HystrixThreadPoolMetrics getHystrixThreadPoolMetrics() {
            return hystrixThreadPoolMetrics;
        }
    }

    @Test
    public void ensureThreadPoolInstanceIsTheOneRegisteredWithMetricsPublisherAndThreadPoolCache() throws IllegalAccessException, NoSuchFieldException {
        HystrixPlugins.getInstance().registerMetricsPublisher(new HystrixMetricsPublisher() {
            @Override
            public HystrixMetricsPublisherThreadPool getMetricsPublisherForThreadPool(HystrixThreadPoolKey threadPoolKey, HystrixThreadPoolMetrics metrics, HystrixThreadPoolProperties properties) {
                return new HystrixMetricsPublisherThreadPoolContainer(metrics);
            }
        });
        HystrixThreadPoolKey threadPoolKey = HystrixThreadPoolKey.Factory.asKey("threadPoolFactoryConcurrencyTest");
        HystrixThreadPool poolOne = new HystrixThreadPool.HystrixThreadPoolDefault(
                threadPoolKey, HystrixThreadPoolProperties.Setter.getUnitTestPropertiesBuilder());
        HystrixThreadPool poolTwo = new HystrixThreadPool.HystrixThreadPoolDefault(
                threadPoolKey, HystrixThreadPoolProperties.Setter.getUnitTestPropertiesBuilder());

        assertThat(poolOne.getExecutor(), is(poolTwo.getExecutor())); //Now that we get the threadPool from the metrics object, this will always be equal
        HystrixMetricsPublisherThreadPoolContainer hystrixMetricsPublisherThreadPool =
                (HystrixMetricsPublisherThreadPoolContainer)HystrixMetricsPublisherFactory
                        .createOrRetrievePublisherForThreadPool(threadPoolKey, null, null);
        ThreadPoolExecutor threadPoolExecutor = hystrixMetricsPublisherThreadPool.getHystrixThreadPoolMetrics().getThreadPool();

        //assert that both HystrixThreadPools share the same ThreadPoolExecutor as the one in HystrixMetricsPublisherThreadPool
        assertTrue(threadPoolExecutor.equals(poolOne.getExecutor()) && threadPoolExecutor.equals(poolTwo.getExecutor()));
        assertFalse(threadPoolExecutor.isShutdown());

        //Now the HystrixThreadPool ALWAYS has the same reference to the ThreadPoolExecutor so that it no longer matters which
        //wins to be inserted into the HystrixThreadPool.Factory.threadPools cache.
    }
    @Test(timeout = 2500)
    public void testUnsubscribeHystrixThreadPool() throws InterruptedException {
        // methods are package-private so can't test it somewhere else
        HystrixThreadPool pool = Factory.getInstance(HystrixThreadPoolKey.Factory.asKey("threadPoolFactoryTest"),
                HystrixThreadPoolProperties.Setter.getUnitTestPropertiesBuilder());
        
        final AtomicBoolean interrupted = new AtomicBoolean();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch end = new CountDownLatch(1);

        HystrixContextScheduler hcs = new HystrixContextScheduler(HystrixPlugins.getInstance().getConcurrencyStrategy(), pool);

        Scheduler.Worker w = hcs.createWorker();

        try {
            w.schedule(new Action0() {
                @Override
                public void call() {
                    start.countDown();
                    try {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ex) {
                            interrupted.set(true);
                        }
                    } finally {
                        end.countDown();
                    }
                }
            });
            
            start.await();
            
            w.unsubscribe();
            
            end.await();
            
            Factory.shutdown();
            
            assertTrue(interrupted.get());
        } finally {
            w.unsubscribe();
        }
    }

}
