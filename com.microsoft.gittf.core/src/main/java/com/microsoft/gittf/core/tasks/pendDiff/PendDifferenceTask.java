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
import com.microsoft.gittf.core.util.ObjectIdUtil;
import com.microsoft.gittf.core.util.WorkspaceOperationErrorListener;
import com.microsoft.tfs.core.clients.versioncontrol.GetOptions;
import com.microsoft.tfs.core.clients.versioncontrol.PendChangesOptions;
import com.microsoft.tfs.core.clients.versioncontrol.path.ServerPath;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.LockLevel;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.PendingChange;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.PendingSet;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.RecursionType;
import com.microsoft.tfs.core.clients.versioncontrol.specs.ItemSpec;

/**
 * The task converts the differences between two commits into a list of pending
 * changes and pends these changes in the workspace specified
 * 
 */
public class PendDifferenceTask
    extends Task
{
    /**
     * Error code - there was no differences between the two commits, there is
     * nothing to pend
     */
    public static final int NOTHING_TO_PEND = 1;

    private final static Log log = LogFactory.getLog(PendDifferenceTask.class);

    private final Repository repository;
    private final RevCommit commitFrom;
    private final RevCommit commitTo;
    private final WorkspaceService workspace;
    private final String serverPathRoot;
    private final File localWorkingFolder;

    private final GitTFConfiguration configuration;

    private RenameMode renameMode = RenameMode.JUSTFILES;

    private PendingChange[] pendingChanges;

    private boolean validated = false;

    /**
     * Constructor
     * 
     * @param repository
     *        the git repository
     * @param commitFrom
     *        the source commit to use when determining the changes needed to
     *        pend
     * @param commitTo
     *        the target commit to use when determining the changes needed to
     *        pend
     * @param workspace
     *        the workspace to pend changes in
     * @param serverPathRoot
     *        the server path root that is mapped
     * @param localWorkingFolder
     *        the local working folder to pend changes in
     */
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

    /**
     * Gets the list of pending changes that have been pended
     * 
     * @return
     */
    public PendingChange[] getPendingChanges()
    {
        return pendingChanges;
    }

    /**
     * Sets the rename mode to use when determining the changes that needs to be
     * pended
     * 
     * @param renameMode
     */
    public void setRenameMode(RenameMode renameMode)
    {
        this.renameMode = renameMode;
    }

    @Override
    public TaskStatus run(final TaskProgressMonitor progressMonitor)
    {
        progressMonitor.beginTask(
            Messages.formatString(
                "PendDifferencesTask.PendingChangesFormat", ObjectIdUtil.abbreviate(repository, commitTo.getId())), //$NON-NLS-1$
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

        /* Get the RevTree objects for the to and from commits */
        RevTree fromTree = (commitFrom != null) ? commitFrom.getTree() : null;
        RevTree toTree = commitTo.getTree();
        Check.notNull(toTree, "toTree"); //$NON-NLS-1$

        final TaskProgressMonitor analyzeMonitor = progressMonitor.newSubTask(75);
        final CheckinAnalysisChangeCollection analysis;

        try
        {
            /* Validate the commit tree objects for any violations */
            validate();

            /*
             * If we are comparing two commits analyze the difference between
             * both commits
             */
            if (fromTree != null)
            {
                analysis = analyzeDifferences(repository, fromTree, toTree, renameMode, analyzeMonitor);
            }
            /*
             * Otherwise we need to create ADD changes for all the items in the
             * tree
             */
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

        /* If the analysis is empty then there is nothing to pend */
        if (analysis.isEmpty())
        {
            return new TaskStatus(TaskStatus.OK, NOTHING_TO_PEND);
        }

        try
        {
            /* Pend the changes found in the analysis in the workspace */
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

    /**
     * Runs all the validations required on the source and destination commits
     * 
     * @throws Exception
     */
    public void validate()
        throws Exception
    {
        if (!validated)
        {
            validateTree(commitTo);
        }

        validated = true;
    }

    /**
     * Validates the trees
     * 
     * @param commit
     *        the commit tree to validate
     * 
     * @throws Exception
     */
    private void validateTree(RevCommit commit)
        throws Exception
    {
        /* Validates TFS case sensitivity requirements */
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
        /* Create a tree walker */
        RevTree tree = commit.getTree();
        final TreeWalk treeWalker = new NameConflictTreeWalk(repository);

        try
        {
            treeWalker.addTree(tree);
            treeWalker.setFilter(TreeFilter.ALL);

            /*
             * Build a list of items in the tree in a hash set for quicker
             * lookup
             */
            Set<String> existingTreeItems = new HashSet<String>();

            /* Walk the tree looking for duplicates in the tree */
            while (treeWalker.next())
            {
                String pathString = treeWalker.getPathString().toLowerCase();

                if (existingTreeItems.contains(pathString))
                {
                    throw new Exception(
                        Messages.formatString(
                            "PendDifferenceTask.SimilarItemWithDifferentCaseInCommitFormat", pathString, ObjectIdUtil.abbreviate(repository, commit.getId()))); //$NON-NLS-1$
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

    /**
     * Creates the CheckinAnalysisChangeCollection analysis object that includes
     * the list of pending changes needed that map to the differences between
     * the fromCommit and toCommit
     * 
     * @param repository
     *        the git repository
     * @param fromRootTree
     *        the from commit tree
     * @param toRootTree
     *        the to commit tree
     * @param renameMode
     *        the rename mode to use when generating the analysis
     * @param progressMonitor
     *        the progress monitor to use to report progress
     * @return
     * @throws Exception
     */
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

        /* Init the analysis object */
        final CheckinAnalysisChangeCollection analysis =
            new CheckinAnalysisChangeCollection(repository, fromRootTree, toRootTree);

        /* Init the tree walker object */
        final TreeWalk treeWalker = new NameConflictTreeWalk(repository);
        final RenameDetector repositoryRenameDetector = new RenameDetector(repository);

        try
        {
            treeWalker.setRecursive(true);

            treeWalker.addTree(fromRootTree);
            treeWalker.addTree(toRootTree);

            treeWalker.setFilter(TreeFilter.ANY_DIFF);

            List<DiffEntry> treeDifferences = DiffEntry.scan(treeWalker);

            /*
             * If we need to detect file renames then use the rename detector to
             * analayze the differences first
             */
            if (renameMode != RenameMode.NONE)
            {
                repositoryRenameDetector.addAll(treeDifferences);
                treeDifferences = repositoryRenameDetector.compute();
            }

            /*
             * If the rename mode is either none or file only then we do not
             * need to detect folder deletes as well, since deleting empty
             * folders that have items that have been renamed out is not
             * supported in TFS
             */
            if (renameMode != RenameMode.ALL)
            {
                analysis.setProcessDeletedFolders(false);
            }

            /* Append each change in to the analysis object */
            for (DiffEntry change : treeDifferences)
            {
                switch (change.getChangeType())
                {
                    case ADD:
                    case COPY:
                        analysis.pendAdd(new AddChange(change.getNewPath(), CommitUtil.resolveAbbreviatedId(
                            repository,
                            change.getNewId())));
                        break;

                    case DELETE:
                        analysis.pendDelete(new DeleteChange(change.getOldPath(), change.getOldMode()));
                        break;

                    case MODIFY:
                        analysis.pendEdit(new EditChange(change.getNewPath(), CommitUtil.resolveAbbreviatedId(
                            repository,
                            change.getNewId())));
                        break;

                    case RENAME:
                        analysis.pendRename(new RenameChange(
                            change.getOldPath(),
                            change.getNewPath(),
                            CommitUtil.resolveAbbreviatedId(repository, change.getNewId()),
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

    /**
     * Creates a CheckinAnalysisChangeCollection for the to commit tree,
     * creating an ADD change for every item in the tree.
     * 
     * @param repository
     *        the git repository
     * @param toRootTree
     *        the to commit tree
     * @param progressMonitor
     *        the progress monitor to use to report progress
     * @return
     * @throws Exception
     */
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

        /* Create the CheckinAnalysisChangeCollection object */
        final CheckinAnalysisChangeCollection analysis = new CheckinAnalysisChangeCollection();
        final TreeWalk treeWalker = new NameConflictTreeWalk(repository);

        try
        {
            treeWalker.setRecursive(true);
            treeWalker.addTree(toRootTree);
            treeWalker.setFilter(TreeFilter.ANY_DIFF);

            /* Walk the tree and pend and add for every item in the tree */
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

    /**
     * Pend the changes in the CheckinAnalysisChangeCollection in the workspace
     * 
     * @param analysis
     *        the collection of changes to pend
     * @param progressMonitor
     *        the progress monitor to use to report progress
     * @return
     * @throws Exception
     */
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

            /* Pend Edits */
            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingEdits")); //$NON-NLS-1$
            pendEdits(analysis, errorListener);
            progressMonitor.worked(1);

            /* Pend Adds */
            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingAdds")); //$NON-NLS-1$
            pendAdds(analysis, errorListener);
            progressMonitor.worked(1);

            /* Pend Renames */
            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingRenames")); //$NON-NLS-1$
            pendRenames(analysis, errorListener);
            progressMonitor.worked(1);

            /* Pend Deletes */
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

    /**
     * Pends the deletes in the CheckinAnalysisChangeCollection
     * 
     * @param analysis
     *        the collection of changes to pend
     * @param errorListener
     *        the error listener to use
     * @throws Exception
     */
    private void pendDeletes(CheckinAnalysisChangeCollection analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        if (analysis.getDeletes().size() == 0)
        {
            return;
        }

        /* Build the delete specs */
        ItemSpec[] deleteSpecs = new ItemSpec[analysis.getDeletes().size()];
        for (int i = 0; i < analysis.getDeletes().size(); i++)
        {
            final DeleteChange delete = analysis.getDeletes().get(i);

            deleteSpecs[i] =
                new ItemSpec(ServerPath.combine(serverPathRoot, delete.getPath()), delete.getType() == FileMode.TREE
                    ? RecursionType.FULL : RecursionType.NONE);
        }

        /* Pend the deletes in the workspace */
        int count =
            workspace.pendDelete(deleteSpecs, LockLevel.NONE, GetOptions.NO_DISK_UPDATE, PendChangesOptions.NONE);

        /*
         * Validate that there were no errors when pending the deletes and that
         * the count of the pending changes matches the count of expected
         * changes
         */
        errorListener.validate();

        if (count < deleteSpecs.length)
        {
            throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
        }
    }

    /**
     * Pends the edits in the CheckinAnalysisChangeCollection
     * 
     * @param analysis
     *        the collection of changes to pend
     * @param errorListener
     *        the error listener to use
     * @throws Exception
     */
    private void pendEdits(CheckinAnalysisChangeCollection analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        if (analysis.getEdits().size() == 0)
        {
            return;
        }

        /* Builds the edit specs */
        List<ItemSpec> editSpecs = new ArrayList<ItemSpec>();
        List<LockLevel> lockLevels = new ArrayList<LockLevel>();

        for (EditChange edit : analysis.getEdits())
        {
            extractToWorkingFolder(edit.getPath(), edit.getObjectID());

            editSpecs.add(new ItemSpec(ServerPath.combine(serverPathRoot, edit.getPath()), RecursionType.NONE));
            lockLevels.add(LockLevel.NONE);
        }

        Check.isTrue(editSpecs.size() == lockLevels.size(), "editSpecs.size == lockLevels.size"); //$NON-NLS-1$

        /* Pends the edits in the workspace */
        int count =
            workspace.pendEdit(
                editSpecs.toArray(new ItemSpec[editSpecs.size()]),
                lockLevels.toArray(new LockLevel[lockLevels.size()]),
                null,
                GetOptions.NO_DISK_UPDATE,
                PendChangesOptions.NONE,
                null,
                true);

        /* Validate that the items have been pended correctly */
        errorListener.validate();

        if (count != editSpecs.size())
        {
            throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
        }
    }

    /**
     * Pends the adds in the CheckinAnalysisChangeCollection
     * 
     * @param analysis
     *        the collection of changes to pend
     * @param errorListener
     *        the error listener to use
     * @throws Exception
     */
    private void pendAdds(CheckinAnalysisChangeCollection analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        int addCount = analysis.getAdds().size();

        if (addCount == 0)
        {
            return;
        }

        /* Build the adds item spec */
        String[] addPaths = new String[addCount];
        for (int i = 0; i < addCount; i++)
        {
            final AddChange add = analysis.getAdds().get(i);

            extractToWorkingFolder(add.getPath(), add.getObjectID());

            addPaths[i] = ServerPath.combine(serverPathRoot, add.getPath());
        }

        /* Pend the adds in the workspace */
        int count =
            workspace.pendAdd(addPaths, false, null, LockLevel.NONE, GetOptions.NO_DISK_UPDATE, PendChangesOptions.NONE);

        /* Validate that the adds have been pended correctly */
        errorListener.validate();

        if (count != addCount)
        {
            throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
        }
    }

    /**
     * Pends the renames in the CheckinAnalysisChangeCollection
     * 
     * @param analysis
     *        the collection of changes to pend
     * @param errorListener
     *        the error listener to use
     * @throws Exception
     */
    private void pendRenames(CheckinAnalysisChangeCollection analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        /*
         * If the rename mode was to detect all renames including folder renames
         * we need create a folder rename detector object to determine the
         * folder rename operations. Git does not track folder renames only file
         * renames, TFS does not allow renameing items and deleteing its parent
         * if it is empty and thus if we only pended renames on items TFS will
         * have stale folders.
         */
        if (renameMode == RenameMode.ALL)
        {
            /* Compute folder renames */
            TfsFolderRenameDetector folderRenameDetector = analysis.createFolderRenameDetector();
            folderRenameDetector.compute();

            /*
             * Due to a tfs limitation we cannot pend a rename for a folder and
             * an item inside the folder in the same pendRename call, thus we
             * are batching the rename calls by the depth of the item path
             */
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

    /**
     * Pends renames for the items specified in the list
     * 
     * @param renames
     *        the renames to pend
     * @param errorListener
     *        the error listener to report the errors through
     * @throws Exception
     */
    private void pendBatchRenames(List<RenameChange> renames, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(renames, "renames"); //$NON-NLS-1$
        Check.notNull(errorListener, "errorListener"); //$NON-NLS-1$

        if (renames.size() == 0)
        {
            return;
        }

        /* Build the item specs for renames */
        List<String> renameOldPaths = new ArrayList<String>();
        List<String> renameNewPaths = new ArrayList<String>();
        List<Boolean> renameEdits = new ArrayList<Boolean>();

        /*
         * Build the item specs for rename edits since some of the items can be
         * renames and edits in the same time
         */
        List<ItemSpec> editSpecs = new ArrayList<ItemSpec>();
        List<LockLevel> lockLevels = new ArrayList<LockLevel>();

        int renameCountToValidate = 0, editCountToValidate = 0;
        for (int i = 0; i < renames.size(); i++)
        {
            final RenameChange rename = renames.get(i);

            /* if the path has changed then the item is a rename */
            if (!rename.getOldPath().equals(rename.getNewPath()))
            {
                renameOldPaths.add(ServerPath.combine(serverPathRoot, rename.getOldPath()));
                renameNewPaths.add(ServerPath.combine(serverPathRoot, rename.getNewPath()));
                renameEdits.add(rename.isEdit());

                renameCountToValidate++;
            }

            /* if the item was edited as well pend an edit */
            if (rename.isEdit())
            {
                editSpecs.add(new ItemSpec(ServerPath.combine(serverPathRoot, rename.getNewPath()), RecursionType.NONE));
                lockLevels.add(LockLevel.NONE);

                extractToWorkingFolder(rename.getOldPath(), rename.getObjectID());

                editCountToValidate++;
            }
        }

        /* Pend the renames */
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

            /* Validate that the renames were pended correctly */
            errorListener.validate();

            if (count < renameCountToValidate)
            {
                throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
            }
        }

        /* Pend the rename edits */
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

            /* Validate that the edits were pendded correctly */
            errorListener.validate();

            if (count < editCountToValidate)
            {
                throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
            }
        }
    }

    /**
     * Extracts an item for the git repository to the path specified. This is
     * used to extract files whose content have changed and will need to be
     * uploaded to pend an edit for
     * 
     * @param itemPath
     *        the path on disk to extract the item to
     * @param objectID
     *        the object id of the blob to extract to the file
     * @throws Exception
     */
    private void extractToWorkingFolder(String itemPath, ObjectId objectID)
        throws Exception
    {
        /* Ensure that the location exits */
        File workingFile = new File(localWorkingFolder, itemPath);

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

        /* Extract the item from git */
        FileOutputStream workingOutput = new FileOutputStream(workingFile);

        try
        {
            /* Copy the blob from the object database to the file */
            repository.getObjectDatabase().open(objectID, OBJ_BLOB).copyTo(workingOutput);

            if (!workingFile.exists())
            {
                throw new Exception(Messages.formatString("PendDifferenceTask.CouldNotCreateItemFormat", itemPath)); //$NON-NLS-1$
            }
        }
        finally
        {
            workingOutput.close();
        }
    }
}
