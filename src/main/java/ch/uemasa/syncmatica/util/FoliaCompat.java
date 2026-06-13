package ch.uemasa.syncmatica.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runtime detection of Folia (regionised multithreaded server) and compatibility helpers.
 *
 * <p>Folia removes the global {@code BukkitScheduler} and replaces it with
 * {@code AsyncScheduler}, {@code RegionScheduler} and {@code EntityScheduler}.
 * Every Bukkit API method that previously ran on "the main thread" now runs on
 * a region / entity thread, so all shared mutable state must be thread-safe.</p>
 *
 * <p>Paper 26.1+ backports the Folia scheduler API, so {@code AsyncScheduler}
 * is available on both server types. This class uses it unconditionally for
 * async tasks and falls back to {@code BukkitScheduler} for the timer only
 * when running on Paper (to preserve main-thread semantics where desired).</p>
 *
 * <p>All methods in this class are safe to call regardless of server type.</p>
 */
public final class FoliaCompat {

    private static final boolean FOLIA;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (ClassNotFoundException ignored) {
            // Not running on Folia
        }
        FOLIA = folia;
    }

    private FoliaCompat() {
    }

    /** @return {@code true} if the current server is a Folia fork. */
    public static boolean isFolia() {
        return FOLIA;
    }

    // -- Scheduler abstraction ------------------------------------------------

    /**
     * A handle that can cancel a repeating / delayed task, wrapping either a
     * {@link ScheduledTask} (Paper/Folia's {@code AsyncScheduler}) or a
     * {@link BukkitTask} (Paper's {@code BukkitScheduler}).
     */
    public interface TaskHandle {
        void cancel();
    }

    /**
     * Runs {@code action} at a fixed rate.
     *
     * <p>On Paper this uses the global {@code BukkitScheduler} so the action
     * runs on the main thread (matching the original behaviour). On Folia
     * the global {@code BukkitScheduler} does not exist, so it uses the
     * {@code AsyncScheduler} instead — the action must not perform region-
     * bound operations.</p>
     *
     * @param plugin      the owning plugin
     * @param action      the task to run repeatedly
     * @param delayTicks  initial delay in server ticks (50 ms each)
     * @param periodTicks period between runs in server ticks
     * @return a handle that can cancel the task
     */
    public static TaskHandle runTaskTimer(
            final JavaPlugin plugin,
            final Runnable action,
            final long delayTicks,
            final long periodTicks) {
        if (FOLIA) {
            return runFoliaTimer(plugin, action, delayTicks, periodTicks);
        }
        return runPaperTimer(plugin, action, delayTicks, periodTicks);
    }

    @SuppressWarnings("deprecation")
    private static TaskHandle runPaperTimer(
            final JavaPlugin plugin,
            final Runnable action,
            final long delayTicks,
            final long periodTicks) {
        final BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, action, delayTicks, periodTicks);
        return task::cancel;
    }

    private static TaskHandle runFoliaTimer(
            final JavaPlugin plugin,
            final Runnable action,
            final long delayTicks,
            final long periodTicks) {
        final long delayMs = delayTicks * 50L;
        final long periodMs = periodTicks * 50L;
        final ScheduledTask task = Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin,
                scheduledTask -> action.run(),
                delayMs,
                periodMs,
                TimeUnit.MILLISECONDS);
        return task::cancel;
    }

    /**
     * Runs {@code action} asynchronously on the next tick.
     *
     * <p>On Folia this runs on the async scheduler pool; on Paper this runs via
     * {@code BukkitScheduler#runTaskAsynchronously}. Both are off the main/
     * region thread so it is safe for file I/O.</p>
     *
     * @param plugin           the owning plugin
     * @param action           the task to run
     * @param exceptionHandler optional handler invoked on the async thread if
     *                         {@code action} throws; may be {@code null}
     * @return a handle that can cancel the task
     */
    public static TaskHandle runAsync(
            final JavaPlugin plugin,
            final Runnable action,
            final Consumer<? super Throwable> exceptionHandler) {
        if (FOLIA) {
            final ScheduledTask task = Bukkit.getAsyncScheduler().runNow(
                    plugin,
                    scheduledTask -> {
                        try {
                            action.run();
                        } catch (Throwable t) {
                            if (exceptionHandler != null) {
                                exceptionHandler.accept(t);
                            }
                        }
                    });
            return task::cancel;
        }
        @SuppressWarnings("deprecation")
        final BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously(plugin, action);
        return task::cancel;
    }
}
