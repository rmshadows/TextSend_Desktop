package ScheduleTask;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * 可以用来代替while true检查元素
 */
public class ScheduleTask {
    // 定时器
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    // 任务管理
    private ScheduledFuture<?> taskHandle;
    // 任务
    private final Runnable task;
    // 延迟运行
    private final long initialDelaySecond;
    // 周期
    private final long periodSecond;
    // 超时
    private long taskTimeout = 0;
    // 时间单位
    private TimeUnit timeUnit = SECONDS;
    // 条件检查延时毫秒
    private long checkIinitialDelayMicroSecond = 800;
    // 条件检查周期毫秒
    private long checkPeriodMicroSecond = 800;
    // 执行模式 scheduleAtFixedRate scheduleWithFixedDelay
    private boolean execAsScheduleAtFixedRate = true;
    // 控制 是否继续执行定时器 true继续 false停止
    private AtomicBoolean hasControl = null;
    /**
     * 返回模式
     * @return
     */
    public boolean isExecAsScheduleAtFixedRate() {
        return execAsScheduleAtFixedRate;
    }

    /**
     * 设置模式是 scheduleAtFixedRate scheduleWithFixedDelay
     * scheduleAtFixedRate ，是以上一个任务开始的时间计时，period时间过去后，检测上一个任务是否执行完毕，如果上一个任务执行完毕，则当前任务立即执行，如果上一个任务没有执行完毕，则需要等上一个任务执行完毕后立即执行。
     * scheduleWithFixedDelay，是以上一个任务结束时开始计时，period时间过去后，立即执行。
     *
     * @param execAsScheduleAtFixedRate true:scheduleAtFixedRate false:scheduleWithFixedDelay
     */
    public void setExecAsScheduleAtFixedRate(boolean execAsScheduleAtFixedRate) {
        this.execAsScheduleAtFixedRate = execAsScheduleAtFixedRate;
    }

    // 条件超时
    public ScheduleTask(Runnable task, long initialDelaySecond, long periodSecond, AtomicBoolean control) {
        this.task = task;
        this.initialDelaySecond = initialDelaySecond;
        this.periodSecond = periodSecond;
        this.hasControl = control;
    }

    public ScheduleTask(Runnable task, long initialDelaySecond, long periodSecond, AtomicBoolean control, TimeUnit timeUnit) {
        this.task = task;
        this.initialDelaySecond = initialDelaySecond;
        this.periodSecond = periodSecond;
        this.hasControl = control;
        this.timeUnit = timeUnit;
    }

    /**
     * 条件超时构造方法
     *
     * @param task                          Runnable任务
     * @param initialDelaySecond            开始延时
     * @param periodSecond                  周期
     * @param control                       条件控制 true运行 false会关闭
     * @param checkIinitialDelayMicroSecond 检查条件任务的延时 时间单位是毫秒！
     * @param checkPeriodMicroSecond        检查条件任务的周期 时间单位是毫秒！
     * @param timeUnit                      时间单位
     */
    public ScheduleTask(Runnable task, long initialDelaySecond, long periodSecond, AtomicBoolean control, long checkIinitialDelayMicroSecond, long checkPeriodMicroSecond, TimeUnit timeUnit) {
        this.task = task;
        this.initialDelaySecond = initialDelaySecond;
        this.periodSecond = periodSecond;
        this.hasControl = control;
        this.checkIinitialDelayMicroSecond = checkIinitialDelayMicroSecond;
        this.checkPeriodMicroSecond = checkPeriodMicroSecond;
        this.timeUnit = timeUnit;
    }

    // 没有超时，一直运行
    public ScheduleTask(Runnable task, long initialDelaySecond, long periodSecond) {
        this.task = task;
        this.initialDelaySecond = initialDelaySecond;
        this.periodSecond = periodSecond;
    }

    /**
     * 会一直运行此任务，除非外部调用cancle方法
     *
     * @param task               任务
     * @param initialDelaySecond 延时
     * @param periodSecond       周期
     * @param timeUnit           时间单位
     */
    public ScheduleTask(Runnable task, long initialDelaySecond, long periodSecond, TimeUnit timeUnit) {
        this.task = task;
        this.initialDelaySecond = initialDelaySecond;
        this.periodSecond = periodSecond;
        this.timeUnit = timeUnit;
    }

    // 运行超时
    public ScheduleTask(Runnable task, long initialDelaySecond, long periodSecond, long taskTimeout) {
        this.task = task;
        this.initialDelaySecond = initialDelaySecond;
        this.periodSecond = periodSecond;
        this.taskTimeout = taskTimeout;
    }

    /**
     * 超时后会自动退出
     *
     * @param task               任务
     * @param initialDelaySecond 延时
     * @param periodSecond       周期
     * @param taskTimeout        超时
     * @param timeUnit           单位
     */
    public ScheduleTask(Runnable task, long initialDelaySecond, long periodSecond, long taskTimeout, TimeUnit timeUnit) {
        this.task = task;
        this.initialDelaySecond = initialDelaySecond;
        this.periodSecond = periodSecond;
        this.taskTimeout = taskTimeout;
        this.timeUnit = timeUnit;
    }

    /**
     * 开始任务
     */
    public void startTask() {
        if (hasControl != null) {
            startWithControl(hasControl);
        } else {
            startWithoutControl();
        }
    }

    /**
     * 检测控制元素来决定是否停止
     * @param control 控制
     */
    private void startWithControl(AtomicBoolean control) {
        // 首先设置检测任务
        scheduler.scheduleAtFixedRate(() -> {
            if (!control.get()) {
                cancelTask();
            }
//            System.out.println("Schedule Task Check ...");
        }, this.checkIinitialDelayMicroSecond, this.checkPeriodMicroSecond, MICROSECONDS);
        if (isExecAsScheduleAtFixedRate()) {
            taskHandle = scheduler.scheduleAtFixedRate(this.task, this.initialDelaySecond, this.periodSecond, this.timeUnit);
        } else {
            taskHandle = scheduler.scheduleWithFixedDelay(this.task, this.initialDelaySecond, this.periodSecond, this.timeUnit);
        }

    }

    /**
     * 超时控制
     */
    private void startWithoutControl() {
        if (isExecAsScheduleAtFixedRate()) {
            taskHandle = scheduler.scheduleAtFixedRate(this.task, this.initialDelaySecond, this.periodSecond, this.timeUnit);
        } else {
            taskHandle = scheduler.scheduleWithFixedDelay(this.task, this.initialDelaySecond, this.periodSecond, this.timeUnit);
        }
        if (this.taskTimeout != 0) {
            scheduler.schedule(this::cancelTask, this.taskTimeout, this.timeUnit);
        }
    }

    /**
     * 取消任务
     */
    public void cancelTask() {
        if (!taskHandle.isCancelled()) {
            taskHandle.cancel(true);
        }
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
        }
//        System.out.println("Schedule Task CANCEL !");
    }
}
