package io.github.ivannavas.autocraftai.web;

import java.lang.management.ManagementFactory;

import com.sun.management.OperatingSystemMXBean;

/**
 * What the game is costing the machine.
 *
 * <p>Sampled on the overlay's own thread, never the game's. Nothing here reaches into Minecraft: the
 * numbers come from the JVM's management beans, which are safe to read from any thread at any time.
 *
 * <p>CPU loads are fractions of the whole machine, not of one core: {@code 1.0} means every core busy.
 * A negative value means this JVM would not say, and the page leaves the tile blank rather than guessing.
 */
record Stats(double processCpu, double systemCpu, long heapUsed, long heapMax, long ramUsed, long ramTotal) {

    /**
     * The {@code com.sun.management} bean rather than the portable one, because the portable interface has
     * no CPU reading at all. It is a HotSpot extension, so the cast is guarded: a JVM without it costs the
     * panel two numbers, not the overlay.
     */
    private static final OperatingSystemMXBean OS =
            ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean bean ? bean : null;

    static Stats sample() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        // maxMemory() is what -Xmx set, which is the number the launcher's memory slider shows and the one
        // a player recognises. totalMemory() is only how far the heap has grown so far, and a heap that has
        // not needed to grow yet would read as "full" against it.
        long max = runtime.maxMemory();
        if (OS == null) {
            return new Stats(-1, -1, used, max, 0, 0);
        }
        long ram = OS.getTotalMemorySize();
        return new Stats(OS.getProcessCpuLoad(), OS.getCpuLoad(), used, max, ram - OS.getFreeMemorySize(), ram);
    }
}
