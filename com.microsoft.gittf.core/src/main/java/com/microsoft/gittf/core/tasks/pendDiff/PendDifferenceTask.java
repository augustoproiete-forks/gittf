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

package com.microsoft.gittf.core.tasks.pendDiff;

import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;

import java.io.File;
import java.io.FileOutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.RenameDetector;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.NameConflictTreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.GitTFConfiguration;
import com.microsoft.gittf.core.interfaces.WorkspaceService;
import com.microsoft.gittf.core.tasks.framework.Task;
import com.microsoft.gittf.core.tasks.framework.TaskProgressDisplay;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.CommitUtil;
import com.microsoft.gittf.core.util.RepositoryUtil;
import com.microsoft.gittf.core.util.WorkspaceOperationErrorListener;
import com.microsoft.tfs.core.clients.versioncontrol.GetOptions;
import com.microsoft.tfs.core.clients.versioncontrol.PendChangesOptions;
import com.microsoft.tfs.core.clients.versioncontrol.path.ServerPath;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.LockLevel;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.PendingChange;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.PendingSet;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.RecursionType;
import com.microsoft.tfs.core.clients.versioncontrol.specs.ItemSpec;

public class PendDifferenceTask
    extends Task
{
    public static final int NOTHING_TO_PEND = 1;

    private final static Log log = LogFactory.getLog(PendDifferenceTask.class);

    private final Repository repository;
    private final RevCommit commitFrom;
    private final RevCommit commitTo;
    private final WorkspaceService workspace;
    private final String serverPathRoot;
    private final File localWorkingFolder;

    private final GitTFConfiguration configuration;

    private RenameMode renameMode = RenameMode.ALL;

    private PendingChange[] pendingChanges;

    private boolean validated = false;

    public PendDifferenceTask(
        final Repository repository,
        final RevCommit commitFrom,
        final RevCommit commitTo,
        final WorkspaceService workspace,
        final String serverPathRoot,
        final File localWorkingFolder)
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$
        /* Commit from may be null to add all files */
        Check.notNull(commitTo, "commitTo"); //$NON-NLS-1$
        Check.notNull(workspace, "workspace"); //$NON-NLS-1$
        Check.notNullOrEmpty(serverPathRoot, "serverPathRoot"); //$NON-NLS-1$
        Check.notNull(localWorkingFolder, "localWorkingFolder"); //$NON-NLS-1$
        Check.isTrue(
            localWorkingFolder.exists() && localWorkingFolder.isDirectory(),
            "localWorkingFolder.exists && localWorkingFolder.isDirectory"); //$NON-NLS-1$

        this.repository = repository;
        this.commitFrom = commitFrom;
        this.commitTo = commitTo;
        this.workspace = workspace;
        this.serverPathRoot = serverPathRoot;
        this.localWorkingFolder = localWorkingFolder;

        this.configuration = GitTFConfiguration.loadFrom(repository);
        Check.notNull(this.configuration, "configuration"); //$NON-NLS-1$
    }

    public PendingChange[] getPendingChanges()
    {
        return pendingChanges;
    }

    public void setRenameMode(RenameMode renameMode)
    {
        this.renameMode = renameMode;
    }

    @Override
    public TaskStatus run(final TaskProgressMonitor progressMonitor)
    {
        progressMonitor.beginTask(
            Messages.formatString(
                "PendDifferencesTask.PendingChangesFormat", CommitUtil.abbreviate(repository, commitTo.getId())), //$NON-NLS-1$
            100,
            TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL);

        /* Nothing to do if we're not going anywhere */
        if (commitFrom != null && commitFrom.getId().equals(commitTo.getId()))
        {
            return TaskStatus.OK_STATUS;
        }

        progressMonitor.setDetail(Messages.getString("PendDifferencesTask.ExaminingServerState")); //$NON-NLS-1$

        /*
         * Sanity check: we do not expect any pending changes in the workspace
         */
        PendingSet existingChanges = workspace.getPendingChanges(new String[]
        {
            ServerPath.combine(serverPathRoot, "*") //$NON-NLS-1$
            },
            RecursionType.FULL,
            false);

        if (existingChanges != null && existingChanges.getPendingChanges().length > 0)
        {
            log.info("Workspace has existing pending changes - was a previous check-in aborted?"); //$NON-NLS-1$

            workspace.undo(new ItemSpec[]
            {
                new ItemSpec(ServerPath.combine(serverPathRoot, "*"), RecursionType.FULL) //$NON-NLS-1$
            });
        }

        progressMonitor.worked(5);
        progressMonitor.setDetail(null);

        RevTree fromTree = (commitFrom != null) ? commitFrom.getTree() : null;
        RevTree toTree = commitTo.getTree();
        Check.notNull(toTree, "toTree"); //$NON-NLS-1$

        final TaskProgressMonitor analyzeMonitor = progressMonitor.newSubTask(75);
        final CheckinAnalysisChangeCollection analysis;

        try
        {
            validate();

            if (fromTree != null)
            {
                analysis = analyzeDifferences(repository, fromTree, toTree, renameMode, analyzeMonitor);
            }
            else
            {
                analysis = analyzeTree(repository, toTree, analyzeMonitor);
            }
        }
        catch (Exception e)
        {
            return new TaskStatus(TaskStatus.ERROR, e);
        }

        analyzeMonitor.endTask();

        final TaskProgressMonitor pendMonitor = progressMonitor.newSubTask(20);

        if (analysis.isEmpty())
        {
            return new TaskStatus(TaskStatus.OK, NOTHING_TO_PEND);
        }

        try
        {
            pendingChanges = pendChanges(analysis, pendMonitor);
        }
        catch (Exception e)
        {
            workspace.undo(new ItemSpec[]
            {
                new ItemSpec(ServerPath.ROOT, RecursionType.FULL)
            });

            return new TaskStatus(TaskStatus.ERROR, e);
        }
        pendMonitor.endTask();

        progressMonitor.endTask();

        return TaskStatus.OK_STATUS;
    }

    public void validate()
        throws Exception
    {
        if (!validated)
        {
            validateTree(commitTo);
        }

        validated = true;
    }

    private void validateTree(RevCommit commit)
        throws Exception
    {
        validateCaseSensitivityRequirements(commit);
    }

    /**
     * Validates that the commit tree does not have the any item twice with the
     * same name only different in case. TFS does not support having the same
     * item with different case so we should not allow that.
     * 
     * @param commit
     * @throws Exception
     */
    private void validateCaseSensitivityRequirements(RevCommit commit)
        throws Exception
    {
        RevTree tree = commit.getTree();
        final TreeWalk treeWalker = new NameConflictTreeWalk(repository);
        try
        {
            treeWalker.addTree(tree);
            treeWalker.setFilter(TreeFilter.ALL);

            Set<String> existingTreeItems = new HashSet<String>();
            while (treeWalker.next())
            {
                String pathString = treeWalker.getPathString().toLowerCase();

                if (existingTreeItems.contains(pathString))
                {
                    throw new Exception(
                        Messages.formatString(
                            "PendDifferenceTask.SimilarItemWithDifferentCaseInCommitFormat", pathString, CommitUtil.abbreviate(repository, commit.getId()))); //$NON-NLS-1$
                }

                existingTreeItems.add(pathString);

                int objectType = treeWalker.getFileMode(0).getObjectType();
                if (objectType == OBJ_TREE)
                {
                    treeWalker.enterSubtree();
                }
            }
        }
        finally
        {
            if (treeWalker != null)
            {
                treeWalker.release();
            }
        }
    }

    public static CheckinAnalysisChangeCollection analyzeDifferences(
        Repository repository,
        RevObject fromRootTree,
        RevObject toRootTree,
        RenameMode renameMode,
        final TaskProgressMonitor progressMonitor)
        throws Exception
    {
        Check.notNull(fromRootTree, "fromRootTree"); //$NON-NLS-1$
        Check.notNull(toRootTree, "toRootTree"); //$NON-NLS-1$
        Check.notNull(progressMonitor, "progressMonitor"); //$NON-NLS-1$

        progressMonitor.beginTask(
            Messages.getString("PendDifferencesTask.AnalyzingCommits"), TaskProgressMonitor.INDETERMINATE, TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL); //$NON-NLS-1$

        final CheckinAnalysisChangeCollection analysis =
            new CheckinAnalysisChangeCollection(repository, fromRootTree, toRootTree);

        final TreeWalk treeWalker = new NameConflictTreeWalk(repository);
        final RenameDetector repositoryRenameDetector = new RenameDetector(repository);

        try
        {
            treeWalker.setRecursive(true);

            treeWalker.addTree(fromRootTree);
            treeWalker.addTree(toRootTree);

            treeWalker.setFilter(TreeFilter.ANY_DIFF);

            List<DiffEntry> treeDifferences = DiffEntry.scan(treeWalker);

            if (renameMode != RenameMode.NONE)
            {
                repositoryRenameDetector.addAll(treeDifferences);
                treeDifferences = repositoryRenameDetector.compute();
            }

            if (renameMode != RenameMode.ALL)
            {
                analysis.setProcessDeletedFolders(false);
            }

            for (DiffEntry change : treeDifferences)
            {
                switch (change.getChangeType())
                {
                    case ADD:
                    case COPY:
                        analysis.pendAdd(new AddChange(change.getNewPath(), RepositoryUtil.expandAbbreviatedId(
                            repository,
                            change.getNewId())));
                        break;

                    case DELETE:
                        analysis.pendDelete(new DeleteChange(change.getOldPath(), change.getOldMode()));
                        break;

                    case MODIFY:
                        analysis.pendEdit(new EditChange(change.getNewPath(), RepositoryUtil.expandAbbreviatedId(
                            repository,
                            change.getNewId())));
                        break;

                    case RENAME:
                        analysis.pendRename(new RenameChange(
                            change.getOldPath(),
                            change.getNewPath(),
                            RepositoryUtil.expandAbbreviatedId(repository, change.getNewId()),
                            !change.getOldId().equals(change.getNewId())));
                }
            }
        }
        finally
        {
            if (treeWalker != null)
            {
                treeWalker.release();
            }

            progressMonitor.endTask();
        }

        return analysis;
    }

    public static CheckinAnalysisChangeCollection analyzeTree(
        Repository repository,
        RevTree toRootTree,
        TaskProgressMonitor progressMonitor)
        throws Exception
    {
        Check.notNull(toRootTree, "toRootTree"); //$NON-NLS-1$
        Check.notNull(progressMonitor, "progressMonitor"); //$NON-NLS-1$

        progressMonitor.beginTask(
            Messages.getString("PendDifferencesTask.AnalyzingCommits"), TaskProgressMonitor.INDETERMINATE, TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL); //$NON-NLS-1$

        final CheckinAnalysisChangeCollection analysis = new CheckinAnalysisChangeCollection();
        final TreeWalk treeWalker = new NameConflictTreeWalk(repository);

        try
        {
            treeWalker.setRecursive(true);
            treeWalker.addTree(toRootTree);
            treeWalker.setFilter(TreeFilter.ANY_DIFF);

            while (treeWalker.next())
            {
                final ObjectId toID = treeWalker.getObjectId(0);

                if (!ObjectId.zeroId().equals(toID))
                {
                    analysis.pendAdd(new AddChange(treeWalker.getPathString(), toID));
                }
                else
                {
                    log.info(MessageFormat.format("Ignoring item {0} - type {1}", //$NON-NLS-1$
                        treeWalker.getPathString(),
                        (toRootTree != null ? treeWalker.getFileMode(1).getObjectType() : "none"))); //$NON-NLS-1$
                }
            }
        }
        finally
        {
            if (treeWalker != null)
            {
                treeWalker.release();
            }

            progressMonitor.endTask();
        }

        return analysis;
    }

    private PendingChange[] pendChanges(
        CheckinAnalysisChangeCollection analysis,
        final TaskProgressMonitor progressMonitor)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        progressMonitor.beginTask(
            Messages.getString("PendDifferencesTask.PendingChanges"), 5, TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL); //$NON-NLS-1$

        WorkspaceOperationErrorListener errorListener = null;

        try
        {
            errorListener = workspace.getErrorListener();

            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingEdits")); //$NON-NLS-1$
            pendEdits(analysis, errorListener);
            progressMonitor.worked(1);

            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingAdds")); //$NON-NLS-1$
            pendAdds(analysis, errorListener);
            progressMonitor.worked(1);

            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingRenames")); //$NON-NLS-1$
            pendRenames(analysis, errorListener);
            progressMonitor.worked(1);

            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingDeletes")); //$NON-NLS-1$
            pendDeletes(analysis, errorListener);
            progressMonitor.worked(1);

            /*
             * Only get pending changes that are *beneath* the server path. We
             * have a lock on the server path itself, and we want to maintain
             * that.
             */
            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.QueryingPendingChanges")); //$NON-NLS-1$
            PendingSet pendingSet = workspace.getPendingChanges(new String[]
            {
                ServerPath.combine(serverPathRoot, "*") //$NON-NLS-1$
                },
                RecursionType.FULL,
                false);
            progressMonitor.worked(1);

            return pendingSet != null ? pendingSet.getPendingChanges() : null;
        }
        finally
        {
            if (errorListener != null)
            {
                errorListener.dispose();
            }

            progressMonitor.endTask();
        }
    }

    private void pendDeletes(CheckinAnalysisChangeCollection analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        if (analysis.getDeletes().size() == 0)
        {
            return;
        }

        ItemSpec[] deleteSpecs = new ItemSpec[analysis.getDeletes().size()];
        for (int i = 0; i < analysis.getDeletes().size(); i++)
        {
            final DeleteChange delete = analysis.getDeletes().get(i);

            deleteSpecs[i] =
                new ItemSpec(ServerPath.combine(serverPathRoot, delete.getPath()), delete.getType() == FileMode.TREE
                    ? RecursionType.FULL : RecursionType.NONE);
        }

        int count =
            workspace.pendDelete(deleteSpecs, LockLevel.NONE, GetOptions.NO_DISK_UPDATE, PendChangesOptions.NONE);

        errorListener.validate();

        if (count < deleteSpecs.length)
        {
            throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
        }
    }

    private void pendEdits(CheckinAnalysisChangeCollection analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        if (analysis.getEdits().size() == 0)
        {
            return;
        }

        List<ItemSpec> editSpecs = new ArrayList<ItemSpec>();
        List<LockLevel> lockLevels = new ArrayList<LockLevel>();

        for (EditChange edit : analysis.getEdits())
        {
            extractToWorkingFolder(edit.getPath(), edit.getObjectID());

            editSpecs.add(new ItemSpec(ServerPath.combine(serverPathRoot, edit.getPath()), RecursionType.NONE));
            lockLevels.add(LockLevel.NONE);
        }

        Check.isTrue(editSpecs.size() == lockLevels.size(), "editSpecs.size == lockLevels.size"); //$NON-NLS-1$

        int count =
            workspace.pendEdit(
                editSpecs.toArray(new ItemSpec[editSpecs.size()]),
                lockLevels.toArray(new LockLevel[lockLevels.size()]),
                null,
                GetOptions.NO_DISK_UPDATE,
                PendChangesOptions.NONE,
                null,
                true);

        errorListener.validate();

        if (count != editSpecs.size())
        {
            throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
        }
    }

    private void pendAdds(CheckinAnalysisChangeCollection analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        int addCount = analysis.getAdds().size();

        if (addCount == 0)
        {
            return;
        }

        String[] addPaths = new String[addCount];

        for (int i = 0; i < addCount; i++)
        {
            final AddChange add = analysis.getAdds().get(i);

            extractToWorkingFolder(add.getPath(), add.getObjectID());

            addPaths[i] = ServerPath.combine(serverPathRoot, add.getPath());
        }

        int count =
            workspace.pendAdd(addPaths, false, null, LockLevel.NONE, GetOptions.NO_DISK_UPDATE, PendChangesOptions.NONE);

        errorListener.validate();

        if (count != addCount)
        {
            throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
        }
    }

    private void pendRenames(CheckinAnalysisChangeCollection analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        if (renameMode == RenameMode.ALL)
        {
            TfsFolderRenameDetector folderRenameDetector = analysis.createFolderRenameDetector();
            folderRenameDetector.compute();

            for (List<RenameChange> renames : folderRenameDetector.getRenameBatches())
            {
                pendBatchRenames(renames, errorListener);
            }
        }
        else
        {
            pendBatchRenames(analysis.getRenames(), errorListener);
        }
    }

    private void pendBatchRenames(List<RenameChange> renames, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(renames, "renames"); //$NON-NLS-1$
        Check.notNull(errorListener, "errorListener"); //$NON-NLS-1$

        if (renames.size() == 0)
        {
            return;
        }

        List<String> renameOldPaths = new ArrayList<String>();
        List<String> renameNewPaths = new ArrayList<String>();
        List<Boolean> renameEdits = new ArrayList<Boolean>();

        List<ItemSpec> editSpecs = new ArrayList<ItemSpec>();
        List<LockLevel> lockLevels = new ArrayList<LockLevel>();

        int renameCountToValidate = 0, editCountToValidate = 0;
        for (int i = 0; i < renames.size(); i++)
        {
            final RenameChange rename = renames.get(i);

            if (!rename.getOldPath().equals(rename.getNewPath()))
            {
                renameOldPaths.add(ServerPath.combine(serverPathRoot, rename.getOldPath()));
                renameNewPaths.add(ServerPath.combine(serverPathRoot, rename.getNewPath()));
                renameEdits.add(rename.isEdit());

                renameCountToValidate++;
            }

            if (rename.isEdit())
            {
                editSpecs.add(new ItemSpec(ServerPath.combine(serverPathRoot, rename.getNewPath()), RecursionType.NONE));
                lockLevels.add(LockLevel.NONE);

                extractToWorkingFolder(rename.getOldPath(), rename.getObjectID());

                editCountToValidate++;
            }
        }

        if (renameOldPaths.size() > 0)
        {
            int count =
                workspace.pendRename(
                    renameOldPaths.toArray(new String[renameOldPaths.size()]),
                    renameNewPaths.toArray(new String[renameNewPaths.size()]),
                    renameEdits.toArray(new Boolean[renameEdits.size()]),
                    LockLevel.NONE,
                    GetOptions.NONE,
                    false,
                    PendChangesOptions.NONE);

            errorListener.validate();

            if (count < renameCountToValidate)
            {
                throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
            }
        }

        // If this is a file we need to pend an edit as well just in case
        // the
        // content also changed. We pend an edit for all renames because it
        // is expensive to figure out if the item content really changed
        // or not. However, the server will automatically strip out the edit
        // change if the contents are the same.
        if (editSpecs.size() > 0)
        {
            int count =
                workspace.pendEdit(
                    editSpecs.toArray(new ItemSpec[editSpecs.size()]),
                    lockLevels.toArray(new LockLevel[lockLevels.size()]),
                    null,
                    GetOptions.NO_DISK_UPDATE,
                    PendChangesOptions.NONE,
                    null,
                    renameOldPaths.size() == 0);

            errorListener.validate();

            if (count < editCountToValidate)
            {
                throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
            }
        }
    }

    private void extractToWorkingFolder(String path, ObjectId objectID)
        throws Exception
    {
        File workingFile = new File(localWorkingFolder, path);

        File parentDir = workingFile.getParentFile();
        boolean parentDirExist = parentDir.exists();
        if (!parentDirExist)
        {
            parentDirExist = parentDir.mkdirs();
        }

        if (!parentDirExist)
        {
            throw new Exception(Messages.formatString(
                "PendDifferenceTask.CouldNotCreateItemPathFormat", parentDir.getAbsolutePath())); //$NON-NLS-1$
        }

        if (workingFile.exists())
        {
            workingFile.delete();
        }

        FileOutputStream workingOutput = new FileOutputStream(workingFile);

        try
        {
            repository.getObjectDatabase().open(objectID, OBJ_BLOB).copyTo(workingOutput);

            if (!workingFile.exists())
            {
                throw new Exception(Messages.formatString("PendDifferenceTask.CouldNotCreateItemFormat", path)); //$NON-NLS-1$
            }
        }
        finally
        {
            workingOutput.close();
        }
    }
}
