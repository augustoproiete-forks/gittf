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

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.ChangesetCommitMap;
import com.microsoft.gittf.core.interfaces.WorkspaceService;
import com.microsoft.gittf.core.tasks.framework.Task;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.TfsBranchUtil;
import com.microsoft.tfs.core.clients.versioncontrol.CheckinFlags;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.PendingChange;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.WorkItemCheckinInfo;

public class CheckinPendingChangesTask
    extends Task
{
    private final Repository repository;
    private final RevCommit commit;
    private final WorkspaceService workspace;
    private final PendingChange[] changes;

    private WorkItemCheckinInfo[] workItems;
    private boolean overrideGatedCheckin;
    private String comment = null;

    private int changesetID = -1;

    public CheckinPendingChangesTask(
        final Repository repository,
        final RevCommit commit,
        final WorkspaceService workspace,
        final PendingChange[] changes)
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNull(commit, "commit"); //$NON-NLS-1$
        Check.notNull(workspace, "workspace"); //$NON-NLS-1$
        Check.isTrue(changes.length > 0, "changes.length > 0"); //$NON-NLS-1$

        this.repository = repository;
        this.commit = commit;
        this.workspace = workspace;
        this.changes = changes;
    }

    public void setWorkItemCheckinInfo(WorkItemCheckinInfo[] workItems)
    {
        this.workItems = workItems;
    }

    public void setOverrideGatedCheckin(boolean overrideGatedCheckin)
    {
        this.overrideGatedCheckin = overrideGatedCheckin;
    }

    public String getComment()
    {
        return comment;
    }

    public void setComment(String comment)
    {
        this.comment = comment;
    }

    @Override
    public TaskStatus run(final TaskProgressMonitor progressMonitor)
    {
        progressMonitor.beginTask(Messages.getString("CheckinPendingChangesTask.CheckingIn"), 100); //$NON-NLS-1$

        try
        {
            ChangesetCommitMap commitMap = new ChangesetCommitMap(repository);

            CheckinFlags checkinFlags = CheckinFlags.NONE;

            if (overrideGatedCheckin)
            {
                checkinFlags = checkinFlags.combine(CheckinFlags.OVERRIDE_GATED_CHECK_IN);
            }

            if (workspace.canCheckIn())
            {
                changesetID =
                    workspace.checkIn(
                        changes,
                        null,
                        null,
                        comment == null ? commit.getFullMessage() : comment,
                        null,
                        workItems,
                        null,
                        checkinFlags);

                commitMap.setChangesetCommit(changesetID, commit.getId());

                /* udpate tfs branch */
                try
                {
                    TfsBranchUtil.update(repository, commit);
                }
                catch (Exception e)
                {
                    return new TaskStatus(TaskStatus.ERROR, e);
                }
            }
            progressMonitor.endTask();
        }
        catch (Exception e)
        {
            return new TaskStatus(TaskStatus.ERROR, e);
        }
        finally
        {
            progressMonitor.dispose();
        }

        return TaskStatus.OK_STATUS;
    }

    public int getChangesetID()
    {
        return changesetID;
    }
}
