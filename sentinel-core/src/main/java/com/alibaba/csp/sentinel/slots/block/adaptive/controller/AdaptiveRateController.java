package com.alibaba.csp.sentinel.slots.block.adaptive.controller;

import com.alibaba.csp.sentinel.concurrent.NamedThreadFactory;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.slots.system.SystemStatusListener;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Liu Yiming
 * @date 2019-07-28 17:34
 */
public class AdaptiveRateController implements TrafficShapingController {
    private final int maxQueueingTimeMs;

    private final AtomicLong bucketCount = new AtomicLong(20);
    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    private final static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
            new NamedThreadFactory("sentinel-system-status-record-task", true));
    private static SystemStatusListener statusListener = null;

    static {
        statusListener = new SystemStatusListener();
        scheduler.scheduleAtFixedRate(statusListener, 5, 1, TimeUnit.SECONDS);
    }

    public AdaptiveRateController(int timeOut) {
        this.maxQueueingTimeMs = timeOut;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
        // Pass when acquire count is less or equal than 0.
        if (acquireCount <= 0) {
            return true;
        }

        long count = bucketCount.get();

        long currentTime = TimeUtil.currentTimeMillis();
        // Calculate the interval between every two requests.
        long costTime = Math.round(1.0 * (acquireCount) / count * 1000);

        // Expected pass time of this request.
        long expectedTime = costTime + latestPassedTime.get();

        if (expectedTime <= currentTime) {
            // Contention may exist here, but it's okay.
            latestPassedTime.set(currentTime);
            return true;
        } else {
            // Calculate the time to wait.
            long waitTime = costTime + latestPassedTime.get() - TimeUtil.currentTimeMillis();
            if (waitTime > maxQueueingTimeMs) {
                long newCount = (long)(count * 2 * Math.min(1.0, 0.6 / getCurrentCpuUsage()));
                bucketCount.compareAndSet(count, newCount);
                return false;
            } else {
                long oldTime = latestPassedTime.addAndGet(costTime);
                try {
                    waitTime = oldTime - TimeUtil.currentTimeMillis();
                    if (waitTime > maxQueueingTimeMs) {
                        latestPassedTime.addAndGet(-costTime);
                        long newCount = (long)(count * 2 * Math.min(1.0, 0.6 / getCurrentCpuUsage()));
                        bucketCount.compareAndSet(count, newCount);
                        return false;
                    }
                    // in race condition waitTime may <= 0
                    if (waitTime > 0) {
                        Thread.sleep(waitTime);
                    }
                    return true;
                } catch (InterruptedException e) {
                }
            }
        }
        return false;
    }

    public static double getCurrentCpuUsage() {
        try {
            return statusListener.getCpuUsage();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }
}
