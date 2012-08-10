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

import java.net.URI;

import org.eclipse.jgit.lib.Repository;

import com.microsoft.gittf.core.GitTFConstants;
import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.GitTFConfiguration;
import com.microsoft.gittf.core.tasks.framework.Task;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.tfs.core.clients.versioncontrol.path.ServerPath;

public class ConfigureRepositoryTask
    extends Task
{
    private final Repository repository;
    private final URI projectCollectionURI;
    private final String tfsPath;

    private boolean deep;

    public ConfigureRepositoryTask(final Repository repository, final URI projectCollectionURI, final String tfsPath)
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNull(repository.getDirectory(), "repository.directory"); //$NON-NLS-1$
        Check.notNull(projectCollectionURI, "projectCollectionURI"); //$NON-NLS-1$
        Check.notNullOrEmpty(tfsPath, "tfsPath"); //$NON-NLS-1$

        this.repository = repository;
        this.projectCollectionURI = projectCollectionURI;
        this.tfsPath = tfsPath;
        this.deep = GitTFConstants.GIT_TF_DEFAULT_DEEP;
    }

    public void setDeep(boolean deep)
    {
        this.deep = deep;
    }

    public boolean getDeep()
    {
        return deep;
    }

    @Override
    public TaskStatus run(final TaskProgressMonitor progressMonitor)
    {
        progressMonitor.beginTask(Messages.getString("ConfigureRepositoryTask.ConfiguringRepository"), //$NON-NLS-1$
            TaskProgressMonitor.INDETERMINATE);

        if (!ServerPath.isServerPath(tfsPath))
        {
            return new TaskStatus(TaskStatus.ERROR, Messages.formatString(
                "ConfigureRepositoryTask.TFSPathNotValidFormat", //$NON-NLS-1$
                tfsPath));
        }

        new GitTFConfiguration(
            projectCollectionURI,
            tfsPath,
            null,
            null,
            deep,
            GitTFConstants.GIT_TF_CURRENT_FORMAT_VERSION).saveTo(repository);

        progressMonitor.endTask();

        return TaskStatus.OK_STATUS;
    }
}
