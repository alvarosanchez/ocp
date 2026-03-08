package com.github.alvarosanchez.ocp.command.interactive;

import com.github.alvarosanchez.ocp.service.RepositoryService.RepositoryCommitPushPreview;

record RepositoryDirtyState(boolean hasLocalChanges, boolean inspectionFailed) {

    static RepositoryDirtyState clean() {
        return new RepositoryDirtyState(false, false);
    }

    static RepositoryDirtyState inspectionError() {
        return new RepositoryDirtyState(false, true);
    }

    static RepositoryDirtyState fromPreview(RepositoryCommitPushPreview preview) {
        return new RepositoryDirtyState(preview.hasLocalChanges(), false);
    }
}
