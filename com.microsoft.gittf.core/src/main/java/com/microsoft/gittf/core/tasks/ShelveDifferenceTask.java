/***********************************************************************************************
 * Copyright (c) Microsoft Corporation All rights reserved.
 * 
 * MIT License:
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ***********************************************************************************************/

package com.microsoft.gittf.core.tasks;

import java.io.File;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.ChangesetCommitMap;
import com.microsoft.gittf.core.config.GitTFConfiguration;
import com.microsoft.gittf.core.interfaces.WorkspaceService;
import com.microsoft.gittf.core.tasks.framework.NullTaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskExecutor;
import com.microsoft.gittf.core.tasks.framework.TaskProgressDisplay;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.tasks.pendDiff.PendDifferenceTask;
import com.microsoft.gittf.core.tasks.pendDiff.RenameMode;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.CommitWalker;
import com.microsoft.gittf.core.util.CommitWalker.CommitDelta;
import com.microsoft.tfs.core.clients.versioncontrol.VersionControlClient;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.WorkItemCheckinInfo;
import com.microsoft.tfs.core.clients.versioncontrol.specs.version.ChangesetVersionSpec;
import com.microsoft.tfs.core.clients.versioncontrol.specs.version.VersionSpec;

public class ShelveDifferenceTask
    extends WorkspaceTask
{
    private static final Log log = LogFactory.getLog(ShelveDifferenceTask.class);

    private final ObjectId shelveCommitID;
    private final String shelvesetName;

    private WorkItemCheckinInfo[] workItems;
    private boolean replace = false;
    private RenameMode renameMode = RenameMode.ALL;
    private String message = null;

    private VersionSpec shelveAgainstVersion = null;

    public ShelveDifferenceTask(
        final Repository repository,
        final ObjectId shelveCommitID,
        final VersionControlClient versionControlClient,
        final String serverPath,
        final String shelvesetName)
    {
        super(repository, versionControlClient, serverPath);

        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNull(shelveCommitID, "shelveCommitID"); //$NON-NLS-1$
        Check.notNull(versionControlClient, "versionControlClient"); //$NON-NLS-1$
        Check.notNullOrEmpty(serverPath, "serverPath"); //$NON-NLS-1$
        Check.notNullOrEmpty(shelvesetName, "shelvesetName"); //$NON-NLS-1$

        this.shelveCommitID = shelveCommitID;
        this.shelvesetName = shelvesetName;
    }

    public void setWorkItemCheckinInfo(WorkItemCheckinInfo[] workItems)
    {
        this.workItems = workItems;
    }

    public void setReplaceExistingShelveset(boolean replace)
    {
        this.replace = replace;
    }

    public void setRenameMode(RenameMode renameMode)
    {
        this.renameMode = renameMode;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    @Override
    public TaskStatus run(final TaskProgressMonitor progressMonitor)
    {
        GitTFWorkspaceData workspaceData = null;

        progressMonitor.beginTask(
            Messages.formatString(
                "ShelveDifferenceTask.ShelvingDifferencesFormat", GitTFConfiguration.loadFrom(repository).getServerPath()), 1, //$NON-NLS-1$ 
            TaskProgressDisplay.DISPLAY_PROGRESS.combine(TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL));

        try
        {
            progressMonitor.setDetail(Messages.getString("ShelveDifferenceTask.ExaminingRepository")); //$NON-NLS-1$

            CommitDelta deltaToShelve = getOptimalCommitDelta();

            final RevCommit fromCommit = deltaToShelve.getFromCommit();
            final RevCommit toCommit = deltaToShelve.getToCommit();

            progressMonitor.setDetail(Messages.getString("ShelveDifferenceTask.PreparingWorkspace")); //$NON-NLS-1$

            workspaceData = createWorkspace(progressMonitor.newSubTask(1), false, shelveAgainstVersion);

            final WorkspaceService workspace = workspaceData.getWorkspace();
            final File workingFolder = workspaceData.getWorkingFolder();

            final PendDifferenceTask pendTask =
                new PendDifferenceTask(repository, fromCommit, toCommit, workspace, serverPath, workingFolder);
            pendTask.setRenameMode(renameMode);

            final TaskStatus pendStatus = new TaskExecutor(progressMonitor.newSubTask(1)).execute(pendTask);

            if (!pendStatus.isOK())
            {
                return pendStatus;
            }

            final ShelvePendingChangesTask shelveTask =
                new ShelvePendingChangesTask(
                    repository,
                    message == null ? toCommit.getFullMessage() : message,
                    workspace,
                    pendTask.getPendingChanges(),
                    shelvesetName);

            shelveTask.setReplaceExistingShelveset(replace);
            shelveTask.setWorkItemCheckinInfo(workItems);

            progressMonitor.setDetail(Messages.getString("ShelveDifferenceTask.Shelving")); //$NON-NLS-1$

            final TaskStatus shelveStatus = new TaskExecutor(progressMonitor.newSubTask(1)).execute(shelveTask);

            progressMonitor.setDetail(null);

            if (!shelveStatus.isOK())
            {
                return shelveStatus;
            }

            disposeWorkspace(progressMonitor.newSubTask(1));
            workspaceData = null;

            progressMonitor.endTask();
            return TaskStatus.OK_STATUS;
        }
        catch (Exception e)
        {
            log.error("Task exited with the following error", e); //$NON-NLS-1$

            return new TaskStatus(TaskStatus.ERROR, e);
        }
        finally
        {
            if (workspaceData != null)
            {
                disposeWorkspace(new NullTaskProgressMonitor());
            }
        }
    }

    private CommitDelta getOptimalCommitDelta()
        throws Exception
    {
        final ChangesetCommitMap commitMap = new ChangesetCommitMap(repository);
        final int shelvesetChangesetId = commitMap.getChangesetID(shelveCommitID);

        if (shelvesetChangesetId > 0)
        {
            // this already maps to an existing changeset;
            throw new Exception(Messages.formatString("ShelveDifferencesTask.NoChangesToShelveFormat", //$NON-NLS-1$
                shelvesetChangesetId));
        }

        List<CommitDelta> commitDeltas = null;

        int currentChangesetId = commitMap.getLastBridgedChangesetID(true);
        while (currentChangesetId > 0)
        {
            ObjectId changesetCommitId = commitMap.getCommitID(currentChangesetId, true);

            try
            {
                commitDeltas = CommitWalker.getAutoSquashedCommitList(repository, changesetCommitId, shelveCommitID);
            }
            catch (Exception exception)
            {
                // eat exception here we do not care if the path does not exist
            }

            if (commitDeltas == null)
            {
                currentChangesetId = commitMap.getPreviousBridgedChangeset(currentChangesetId, true);
            }
            else
            {
                break;
            }
        }

        if (commitDeltas == null)
        {
            RevWalk walker = new RevWalk(repository);
            try
            {
                RevCommit toCommit = walker.parseCommit(shelveCommitID);
                return new CommitDelta(null, toCommit);
            }
            finally
            {
                if (walker != null)
                {
                    walker.release();
                }
            }
        }

        shelveAgainstVersion = new ChangesetVersionSpec(currentChangesetId);

        final RevCommit fromCommit = commitDeltas.get(0).getFromCommit();
        final RevCommit toCommit = commitDeltas.get(commitDeltas.size() - 1).getToCommit();

        return new CommitDelta(fromCommit, toCommit);
    }
}
