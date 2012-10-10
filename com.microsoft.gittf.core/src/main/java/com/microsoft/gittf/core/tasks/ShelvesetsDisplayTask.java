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

import java.util.Set;
import java.util.TreeSet;

import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.interfaces.VersionControlService;
import com.microsoft.gittf.core.tasks.framework.Task;
import com.microsoft.gittf.core.tasks.framework.TaskProgressDisplay;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.ShelvesetCompartor;
import com.microsoft.gittf.core.util.ShelvesetSortOption;
import com.microsoft.gittf.core.util.ShelvesetView;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.PendingSet;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Shelveset;

public class ShelvesetsDisplayTask
    extends Task
{
    private final VersionControlService versionControlService;
    private final ShelvesetView view;

    private boolean displayDetails = false;
    private ShelvesetSortOption sortOption = ShelvesetSortOption.DATE;
    private String user = null;
    private String name = null;

    public ShelvesetsDisplayTask(final VersionControlService versionControlService, final ShelvesetView view)
    {
        Check.notNull(versionControlService, "versionControlService"); //$NON-NLS-1$
        Check.notNull(view, "view"); //$NON-NLS-1$

        this.versionControlService = versionControlService;
        this.view = view;
    }

    public void setDisplayDetails(final boolean displayDetails)
    {
        this.displayDetails = displayDetails;
    }

    public void setSortOption(final ShelvesetSortOption sortOption)
    {
        this.sortOption = sortOption;
    }

    public void setUser(final String user)
    {
        this.user = user;
    }

    public void setName(final String name)
    {
        this.name = name;
    }

    @Override
    public TaskStatus run(TaskProgressMonitor progressMonitor)
        throws Exception
    {
        progressMonitor.beginTask(Messages.getString("ShelvesetsDisplayTask.DownloadingShelvesets"), //$NON-NLS-1$
            1,
            TaskProgressDisplay.DISPLAY_PROGRESS.combine(TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL));

        Shelveset[] results = versionControlService.queryShelvesets(name, user);

        if (results.length == 0)
        {
            progressMonitor.endTask();
            return new TaskStatus(TaskStatus.ERROR, Messages.getString("ShelvesetsDisplayTask.NoShelvesetsFound")); //$NON-NLS-1$
        }

        if (displayDetails && results.length == 1)
        {
            // display shelveset details
            PendingSet[] shelvesetDetails = versionControlService.queryShelvesetChanges(results[0], false);
            progressMonitor.endTask();

            view.displayShelvesetDetails(results[0], shelvesetDetails);
        }
        else
        {
            progressMonitor.endTask();

            // Sort shelvesets
            Set<Shelveset> shelvesets = new TreeSet<Shelveset>(new ShelvesetCompartor(sortOption));
            for (Shelveset shelveset : results)
            {
                shelvesets.add(shelveset);
            }

            // display all shelvesets
            view.displayShelvesets(shelvesets.toArray(new Shelveset[shelvesets.size()]), displayDetails);
        }
        return TaskStatus.OK_STATUS;
    }
}
