package io.github.hectorvent.floci.services.rds.model;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The storage and backup settings of a DB instance as a request carries them: a null member is
 * one the request left out. On create that means the AWS default, on modify it means unchanged.
 */
@RegisterForReflection
public record DbInstanceSettings(Boolean storageEncrypted,
                                 String kmsKeyId,
                                 Integer backupRetentionPeriod,
                                 String preferredBackupWindow,
                                 String preferredMaintenanceWindow,
                                 Boolean copyTagsToSnapshot) {

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final List<String> DAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");
    private static final int MINUTES_PER_DAY = 24 * 60;
    private static final int MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY;
    private static final int MINIMUM_WINDOW_MINUTES = 30;

    /**
     * Where AWS picks a random 30-minute window, Floci picks these; the alternates are used when a
     * window given on create overlaps the default of the other kind, since AWS would have picked a
     * random window clear of it.
     */
    public static final String DEFAULT_BACKUP_WINDOW = "04:00-06:00";
    public static final String DEFAULT_MAINTENANCE_WINDOW = "mon:00:00-mon:03:00";
    public static final String ALTERNATE_BACKUP_WINDOW = "06:30-07:00";
    public static final String ALTERNATE_MAINTENANCE_WINDOW = "sun:06:30-sun:07:00";

    public static DbInstanceSettings defaults() {
        return new DbInstanceSettings(null, null, null, null, null, null);
    }

    public static DbInstanceSettings unchanged() {
        return defaults();
    }

    /**
     * The per-parameter checks a live account applies, with its wording. The retention period is
     * not range-checked: AWS accepted 40 days on a postgres instance. Overlap between the windows
     * is checked by the service against the windows that will be in effect, since the counterpart
     * of a window given alone comes from the instance or from a default.
     */
    public void validate() {
        if (kmsKeyId != null && !kmsKeyId.isBlank() && !Boolean.TRUE.equals(storageEncrypted)) {
            throw new AwsException("InvalidParameterCombination",
                    "You must enable StorageEncrypted when you specify KmsKeyId", 400);
        }
        if (preferredBackupWindow != null) {
            parseBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null) {
            parseMaintenanceWindow(preferredMaintenanceWindow);
        }
    }

    /** Whether a daily backup window and a weekly maintenance window share any minute. */
    public static boolean windowsOverlap(String backupWindow, String maintenanceWindow) {
        return overlap(parseBackupWindow(backupWindow), parseMaintenanceWindow(maintenanceWindow));
    }

    public static AwsException overlappingWindows() {
        return new AwsException("InvalidParameterValue",
                "The backup window and maintenance window must not overlap.", 400);
    }

    /** Start and end as minutes of the day; the end is pushed past midnight when the window wraps. */
    private static int[] parseBackupWindow(String window) {
        String[] parts = window.split("-", -1);
        if (parts.length != 2) {
            throw invalidBackupTime(window);
        }
        int start = minuteOfDay(parts[0], () -> invalidBackupTime(parts[0]));
        int end = minuteOfDay(parts[1], () -> invalidBackupTime(parts[1]));
        if (end <= start) {
            end += MINUTES_PER_DAY;
        }
        if (end - start < MINIMUM_WINDOW_MINUTES) {
            throw new AwsException("InvalidParameterValue",
                    "Backup window must be at least " + MINIMUM_WINDOW_MINUTES + " minutes.", 400);
        }
        return new int[] {start, end};
    }

    /** Start and end as minutes of the week; the end is pushed past Sunday when the window wraps. */
    private static int[] parseMaintenanceWindow(String window) {
        String[] parts = window.split("-", -1);
        if (parts.length != 2) {
            throw invalidMaintenanceTime(window);
        }
        int start = minuteOfWeek(parts[0]);
        int end = minuteOfWeek(parts[1]);
        if (end <= start) {
            end += MINUTES_PER_WEEK;
        }
        if (end - start < MINIMUM_WINDOW_MINUTES) {
            throw new AwsException("InvalidParameterValue",
                    "Maintenance window must be at least " + MINIMUM_WINDOW_MINUTES + " minutes.", 400);
        }
        return new int[] {start, end};
    }

    private static int minuteOfWeek(String dayAndTime) {
        int colon = dayAndTime.indexOf(':');
        int day = colon < 0 ? -1 : DAYS.indexOf(dayAndTime.substring(0, colon).toLowerCase());
        if (day < 0) {
            throw invalidMaintenanceTime(dayAndTime);
        }
        return day * MINUTES_PER_DAY
                + minuteOfDay(dayAndTime.substring(colon + 1), () -> invalidMaintenanceTime(dayAndTime));
    }

    private static int minuteOfDay(String time, Supplier<AwsException> invalid) {
        Matcher m = TIME.matcher(time);
        if (!m.matches()) {
            throw invalid.get();
        }
        return Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
    }

    /**
     * The backup window recurs every day, so it is laid over each day of the week and compared
     * with the maintenance window in the same week, the week before and the week after — a
     * window that wraps past Sunday midnight or before Monday midnight lands in a neighbour.
     */
    private static boolean overlap(int[] dailyBackup, int[] weeklyMaintenance) {
        for (int day = 0; day < 7; day++) {
            int start = day * MINUTES_PER_DAY + dailyBackup[0];
            int end = day * MINUTES_PER_DAY + dailyBackup[1];
            for (int shift : new int[] {-MINUTES_PER_WEEK, 0, MINUTES_PER_WEEK}) {
                if (start < weeklyMaintenance[1] - shift && weeklyMaintenance[0] - shift < end) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AwsException invalidBackupTime(String time) {
        return new AwsException("InvalidParameterValue", "Invalid backup window time '" + time
                + "' specified. Should be specified as a time hh24:mi (24H Clock UTC). Example: 03:15", 400);
    }

    private static AwsException invalidMaintenanceTime(String time) {
        return new AwsException("InvalidParameterValue", "Invalid maintenance window time '" + time
                + "' specified. Should be specified as a time ddd:hh24:mi (24H Clock UTC). Example: Mon:00:15", 400);
    }

    public DbInstanceSettings withKmsKeyId(String resolvedKmsKeyId) {
        return new DbInstanceSettings(storageEncrypted, resolvedKmsKeyId, backupRetentionPeriod,
                preferredBackupWindow, preferredMaintenanceWindow, copyTagsToSnapshot);
    }

    public void applyTo(DbInstance instance) {
        if (storageEncrypted != null) {
            instance.setStorageEncrypted(storageEncrypted);
        }
        if (kmsKeyId != null && !kmsKeyId.isBlank()) {
            instance.setKmsKeyId(kmsKeyId);
        }
        if (backupRetentionPeriod != null) {
            instance.setBackupRetentionPeriod(backupRetentionPeriod);
        }
        if (preferredBackupWindow != null && !preferredBackupWindow.isBlank()) {
            instance.setPreferredBackupWindow(preferredBackupWindow);
        }
        if (preferredMaintenanceWindow != null && !preferredMaintenanceWindow.isBlank()) {
            instance.setPreferredMaintenanceWindow(preferredMaintenanceWindow.toLowerCase());
        }
        if (copyTagsToSnapshot != null) {
            instance.setCopyTagsToSnapshot(copyTagsToSnapshot);
        }
    }
}
