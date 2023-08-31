package ScheduleTask;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class test {
    public static void main(String[] args) {
        /*
        条件控制使用方法（代替while）
         */
        // 设置控制符
        AtomicBoolean control = new AtomicBoolean(false);
        // 5秒后设置false停止
        new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            control.set(false);
        }).start();
        // 设置任务
        Runnable r = () -> {
            System.out.println("周期任务最先结束");
        };
        // 设置为true才能运行
        control.set(true);
        new ScheduleTask(r, 1, 1, control, TimeUnit.SECONDS).startTask();
        /*
        超时控制
         */
        r = () -> {
            System.out.println("超时任务最后结束");
        };
        // 10秒后自动停止
        new ScheduleTask(r, 1, 1, 10, TimeUnit.SECONDS).startTask();
        /*
        手动停止
         */
        r = () -> {
            System.out.println("手动停止任务中间结束");
        };
        ScheduleTask s = new ScheduleTask(r, 1, 1, 10, TimeUnit.SECONDS);
        s.startTask();
        new Thread(()->{
            try {
                Thread.sleep(7000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            s.cancelTask();
        }).start();
    }
}
