package android.app;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.window.TaskSnapshot;

oneway interface ITaskStackListener {
    const int FORCED_RESIZEABLE_REASON_SPLIT_SCREEN = 1;
    const int FORCED_RESIZEABLE_REASON_SECONDARY_DISPLAY = 2;

    void onTaskStackChanged();

    void onActivityPinned(String packageName, int userId, int taskId, int stackId);

    void onActivityUnpinned();

    void onActivityRestartAttempt(in ActivityManager.RunningTaskInfo task, boolean homeTaskVisible,
            boolean clearedTask, boolean wasVisible);

    void onActivityForcedResizable(String packageName, int taskId, int reason);

    void onActivityDismissingDockedTask();

    void onActivityLaunchOnSecondaryDisplayFailed(in ActivityManager.RunningTaskInfo taskInfo,
            int requestedDisplayId);

    void onActivityLaunchOnSecondaryDisplayRerouted(in ActivityManager.RunningTaskInfo taskInfo,
            int requestedDisplayId);

    void onTaskCreated(int taskId, in ComponentName componentName);

    void onTaskRemoved(int taskId);

    void onTaskMovedToFront(in ActivityManager.RunningTaskInfo taskInfo);

    void onTaskDescriptionChanged(in ActivityManager.RunningTaskInfo taskInfo);

    void onActivityRequestedOrientationChanged(int taskId, int requestedOrientation);

    void onTaskRemovalStarted(in ActivityManager.RunningTaskInfo taskInfo);

    void onTaskProfileLocked(in ActivityManager.RunningTaskInfo taskInfo, int userId);

    void onTaskSnapshotChanged(int taskId, in TaskSnapshot snapshot);

    void onTaskSnapshotInvalidated(int taskId);

    void onBackPressedOnTaskRoot(in ActivityManager.RunningTaskInfo taskInfo);

    void onTaskDisplayChanged(int taskId, int newDisplayId);

    void onRecentTaskListUpdated();

    void onRecentTaskListFrozenChanged(boolean frozen);

    void onRecentTaskRemovedForAddTask(int taskId);

    void onTaskFocusChanged(int taskId, boolean focused);

    void onTaskRequestedOrientationChanged(int taskId, int requestedOrientation);

    void onActivityRotation(int displayId);

    void onTaskMovedToBack(in ActivityManager.RunningTaskInfo taskInfo);

    void onLockTaskModeChanged(int mode);
}
