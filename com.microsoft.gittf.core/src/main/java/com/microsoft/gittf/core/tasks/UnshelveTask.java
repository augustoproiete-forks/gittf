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

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.interfaces.VersionControlService;
import com.microsoft.gittf.core.tasks.framework.Task;
import com.microsoft.gittf.core.tasks.framework.TaskExecutor;
import com.microsoft.gittf.core.tasks.framework.TaskProgressDisplay;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.CommitUtil;
import com.microsoft.gittf.core.util.StashUtil;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Shelveset;

public class UnshelveTask
    extends Task
{
    private final VersionControlService versionControlService;
    private final Repository repository;
    private final String shelvesetOwnerName;
    private final String shelvesetName;

    private boolean apply = false;

    public UnshelveTask(
        final VersionControlService versionControlService,
        final Repository repository,
        final String shelvesetName,
        final String shelvesetOwnerName)
    {
        Check.notNull(versionControlService, "versionControlService"); //$NON-NLS-1$
        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNullOrEmpty(shelvesetName, "shelvesetName"); //$NON-NLS-1$

        this.versionControlService = versionControlService;
        this.repository = repository;
        this.shelvesetName = shelvesetName;
        this.shelvesetOwnerName = shelvesetOwnerName;
    }

    public void setApply(boolean apply)
    {
        this.apply = apply;
    }

    @Override
    public TaskStatus run(TaskProgressMonitor progressMonitor)
        throws Exception
    {
        progressMonitor.beginTask(Messages.getString("UnshelveTask.LookingUpShelveset"), //$NON-NLS-1$
            1,
            TaskProgressDisplay.DISPLAY_PROGRESS.combine(TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL));

        Shelveset[] results = versionControlService.queryShelvesets(shelvesetName, shelvesetOwnerName);

        if (results.length == 0)
        {
            progressMonitor.endTask();
            return new TaskStatus(TaskStatus.ERROR, Messages.getString("UnshelveTask.NoShelvesetsFound")); //$NON-NLS-1$
        }

        if (results.length > 1)
        {
            progressMonitor.endTask();
            return new TaskStatus(TaskStatus.ERROR, Messages.getString("UnshelveTask.MultipleShelvesetsFound")); //$NON-NLS-1$
        }

        Shelveset shelveset = results[0];

        CreateCommitForShelvesetTask unshelveTask =
            new CreateCommitForShelvesetTask(repository, versionControlService, shelveset, null);

        TaskStatus unshelveTaskStatus = new TaskExecutor(progressMonitor.newSubTask(1)).execute(unshelveTask);

        if (!unshelveTaskStatus.isOK())
        {
            return unshelveTaskStatus;
        }

        ObjectId shelvesetCommitId = unshelveTask.getCommitID();

        String shelvesetTagName = generateValidTagName(shelveset);
        PersonIdent shelvesetOwner = new PersonIdent(shelveset.getOwnerDisplayName(), shelveset.getOwnerName());
        boolean tagCreated = CommitUtil.createTag(repository, shelvesetCommitId, shelvesetTagName, shelvesetOwner);

        if (apply)
        {
            StashUtil.apply(repository, shelvesetCommitId);
        }

        progressMonitor.endTask();

        progressMonitor.displayMessage(Messages.formatString("UnshelveTask.SuccessMessageFormat", //$NON-NLS-1$
            tagCreated ? shelvesetTagName : CommitUtil.abbreviate(repository, shelvesetCommitId),
            shelveset.getName()));

        return TaskStatus.OK_STATUS;
    }

    private String generateValidTagName(Shelveset shelveset)
    {
        String tagName = Messages.formatString("UnshelveTask.ShelvesetTagFormat", shelveset.getName()); //$NON-NLS-1$

        if (Repository.isValidRefName(Constants.R_TAGS + tagName))
        {
            return tagName;
        }

        tagName = tagName.replace(' ', '_');

        if (Repository.isValidRefName(Constants.R_TAGS + tagName))
        {
            return tagName;
        }

        return Messages.formatString("UnshelveTask.ShelvesetTagFormat", Long.toString(System.currentTimeMillis())); //$NON-NLS-1$
    }
}
