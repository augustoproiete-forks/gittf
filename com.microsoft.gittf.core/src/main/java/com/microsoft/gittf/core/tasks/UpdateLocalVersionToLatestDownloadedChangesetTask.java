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

import com.microsoft.gittf.core.config.ChangesetCommitMap;
import com.microsoft.gittf.core.config.GitTFConfiguration;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.tfs.core.clients.versioncontrol.GetOptions;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.GetOperation;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.GetRequest;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.RecursionType;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Workspace;
import com.microsoft.tfs.core.clients.versioncontrol.specs.ItemSpec;
import com.microsoft.tfs.core.clients.versioncontrol.specs.version.ChangesetVersionSpec;

public class UpdateLocalVersionToLatestDownloadedChangesetTask
    extends UpdateLocalVersionTask
{
    private final Repository repository;

    private GetOperation[][] tfsGetOperations;

    public UpdateLocalVersionToLatestDownloadedChangesetTask(final Workspace workspace, final Repository repository)
    {
        super(workspace);

        Check.notNull(repository, "repository"); //$NON-NLS-1$

        this.repository = repository;
    }

    public GetOperation[][] getGetOperations()
    {
        if (tfsGetOperations == null)
        {
            tfsGetOperations = getLatestDownloadedChangesetGetOps();
        }

        return tfsGetOperations;
    }

    private GetOperation[][] getLatestDownloadedChangesetGetOps()
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$

        final Workspace workspace = getWorkspace();
        Check.notNull(workspace, "workspace"); //$NON-NLS-1$

        GitTFConfiguration configuration = GitTFConfiguration.loadFrom(repository);
        ChangesetCommitMap commitMap = new ChangesetCommitMap(repository);
        int lastDownloadedChangeset = commitMap.getLastBridgedChangesetID(true);

        /*
         * If this is a repo that was just configured and never checked in there
         * will be nothing to update here
         */
        if (lastDownloadedChangeset < 0)
        {
            return null;
        }

        GetOperation[][] tfsGetOperations =
            workspace.getClient().getWebServiceLayer().get(
                workspace.getName(),
                workspace.getOwnerName(),
                new GetRequest[]
                {
                    new GetRequest(
                        new ItemSpec(configuration.getServerPath(), RecursionType.FULL),
                        new ChangesetVersionSpec(lastDownloadedChangeset))
                },
                0,
                GetOptions.NO_DISK_UPDATE.combine(GetOptions.GET_ALL),
                null,
                null,
                false);

        return tfsGetOperations;
    }
}
