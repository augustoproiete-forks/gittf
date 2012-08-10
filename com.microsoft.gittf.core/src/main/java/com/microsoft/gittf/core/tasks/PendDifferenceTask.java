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

import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;
import static org.eclipse.jgit.lib.Constants.OBJ_TREE;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.treewalk.NameConflictTreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.GitTFConfiguration;
import com.microsoft.gittf.core.tasks.framework.Task;
import com.microsoft.gittf.core.tasks.framework.TaskProgressDisplay;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.CommitUtil;
import com.microsoft.gittf.core.util.RepositoryPath;
import com.microsoft.gittf.core.util.WorkspaceOperationErrorListener;
import com.microsoft.tfs.core.clients.versioncontrol.GetOptions;
import com.microsoft.tfs.core.clients.versioncontrol.PendChangesOptions;
import com.microsoft.tfs.core.clients.versioncontrol.path.ServerPath;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.LockLevel;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.PendingChange;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.PendingSet;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.RecursionType;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Workspace;
import com.microsoft.tfs.core.clients.versioncontrol.specs.ItemSpec;

public class PendDifferenceTask
    extends Task
{
    public static final int NOTHING_TO_PEND = 1;

    private final static Log log = LogFactory.getLog(PendDifferenceTask.class);

    private final Repository repository;
    private final RevCommit commitFrom;
    private final RevCommit commitTo;
    private final Workspace workspace;
    private final String serverPathRoot;
    private final File localWorkingFolder;

    private final GitTFConfiguration configuration;

    private PendingChange[] pendingChanges;

    public PendDifferenceTask(
        final Repository repository,
        final RevCommit commitFrom,
        final RevCommit commitTo,
        final Workspace workspace,
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
        final CheckinAnalysis analysis;

        try
        {
            validateTree(commitTo);

            analysis = analyzeDifferences(fromTree, toTree, analyzeMonitor);
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

    private CheckinAnalysis analyzeDifferences(
        RevObject fromRootTree,
        RevObject toRootTree,
        final TaskProgressMonitor progressMonitor)
        throws IOException
    {
        Check.notNull(toRootTree, "toRootTree"); //$NON-NLS-1$
        Check.notNull(progressMonitor, "progressMonitor"); //$NON-NLS-1$

        progressMonitor.beginTask(
            Messages.getString("PendDifferencesTask.AnalyzingCommits"), TaskProgressMonitor.INDETERMINATE, TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL); //$NON-NLS-1$

        final CheckinAnalysis analysis = new CheckinAnalysis();
        final TreeWalk treeWalker = new NameConflictTreeWalk(repository);

        try
        {
            int fromIndex = -1, toIndex = -1;

            treeWalker.setRecursive(true);

            if (fromRootTree != null)
            {
                treeWalker.addTree(fromRootTree);
                fromIndex = 0;
            }
            if (toRootTree != null)
            {
                treeWalker.addTree(toRootTree);
                toIndex = (fromRootTree == null) ? 0 : 1;
            }

            treeWalker.setFilter(TreeFilter.ANY_DIFF);

            Set<String> candidateDeletedFolders = new HashSet<String>();
            Set<String> candidateCaseSensitiveRenamedFolders = new HashSet<String>();

            while (treeWalker.next())
            {
                final ObjectId fromID = (fromRootTree != null) ? treeWalker.getObjectId(fromIndex) : ObjectId.zeroId();
                final ObjectId toID = (toRootTree != null) ? treeWalker.getObjectId(toIndex) : ObjectId.zeroId();

                /* TODO: better sym link support */

                if (ObjectId.zeroId().equals(fromID) && !ObjectId.zeroId().equals(toID))
                {
                    String pathString = treeWalker.getPathString();
                    int objectType = treeWalker.getFileMode(0).getObjectType();

                    analysis.getAdds().add(new Add(pathString, toID, treeWalker.getFileMode(toIndex)));

                    candidateCaseSensitiveRenamedFolders.add(objectType == OBJ_TREE ? pathString
                        : RepositoryPath.getParent(pathString));
                }

                else if (!ObjectId.zeroId().equals(fromID) && ObjectId.zeroId().equals(toID))
                {
                    String pathString = treeWalker.getPathString();
                    int objectType = treeWalker.getFileMode(0).getObjectType();

                    analysis.getDeletes().add(new Delete(pathString, objectType));

                    candidateDeletedFolders.add(objectType == OBJ_TREE ? pathString
                        : RepositoryPath.getParent(pathString));
                }

                /* Two blobs that differ is an edit. */
                else if (treeWalker.getFileMode(fromIndex).getObjectType() == OBJ_BLOB
                    && treeWalker.getFileMode(toIndex).getObjectType() == OBJ_BLOB)
                {
                    final boolean contentModified = !treeWalker.idEqual(fromIndex, toIndex);

                    analysis.getEdits().add(
                        new Edit(
                            treeWalker.getPathString(),
                            toID,
                            contentModified,
                            treeWalker.getFileMode(fromIndex),
                            treeWalker.getFileMode(toIndex)));
                }

                /*
                 * Two directories that differ don't matter, this merely implies
                 * a child has changed.
                 */
                else if (treeWalker.getFileMode(fromIndex).getObjectType() == OBJ_TREE
                    && treeWalker.getFileMode(toIndex).getObjectType() == OBJ_TREE)
                {
                    continue;
                }

                /*
                 * Name conflict - delete the old, add the new.
                 */
                else if ((treeWalker.getFileMode(fromIndex).getObjectType() == OBJ_TREE && treeWalker.getFileMode(
                    toIndex).getObjectType() == OBJ_BLOB)
                    || (treeWalker.getFileMode(fromIndex).getObjectType() == OBJ_BLOB && treeWalker.getFileMode(toIndex).getObjectType() == OBJ_TREE))
                {
                    analysis.getDeletes().add(
                        new Delete(treeWalker.getPathString(), treeWalker.getFileMode(fromIndex).getObjectType()));

                    analysis.getAdds().add(
                        new Add(
                            treeWalker.getPathString(),
                            treeWalker.getObjectId(toIndex),
                            treeWalker.getFileMode(toIndex)));
                }

                else
                {
                    log.info(MessageFormat.format("Ignoring item {0} - type {1} -> {2}", //$NON-NLS-1$
                        treeWalker.getPathString(),
                        (fromRootTree != null ? treeWalker.getFileMode(0).getObjectType() : "none"), //$NON-NLS-1$
                        (toRootTree != null ? treeWalker.getFileMode(1).getObjectType() : "none"))); //$NON-NLS-1$
                }
            }

            /* Build Candidate Deleted and Case Sensitive renamed Folders */
            if (candidateDeletedFolders.size() > 0)
            {
                ObjectReader objectReader = repository.newObjectReader();
                for (String filePath : candidateDeletedFolders)
                {
                    /* if this is a case sensitive rename */
                    if (addFolderRenames(filePath, candidateCaseSensitiveRenamedFolders, analysis))
                    {
                        continue;
                    }

                    /* if this is a folder delete */
                    String folderToDelete = getFolderToDelete(filePath, null, objectReader, toRootTree);

                    if (folderToDelete != null && folderToDelete.length() != 0)
                    {
                        analysis.getDeletes().add(new Delete(folderToDelete, OBJ_TREE));
                    }
                }
                objectReader.release();
            }

            /* Build Candidate Case Sensitive renames */
            analysis.prepareCaseSensitiveRenames();

        }
        finally
        {
            treeWalker.release();

            progressMonitor.endTask();
        }

        return analysis;
    }

    private boolean addFolderRenames(String filePath, Set<String> candidateRenamedFolders, CheckinAnalysis analysis)
    {
        String candidateCaseSensitiveNewName = getNewCaseSensitiveName(candidateRenamedFolders, filePath);
        if (candidateCaseSensitiveNewName != null && candidateCaseSensitiveNewName.length() > 0)
        {
            String currentOldFolderPath = filePath;
            String currentNewFolderPath = candidateCaseSensitiveNewName;
            while (currentOldFolderPath != null && currentOldFolderPath.length() > 0)
            {
                if (currentNewFolderPath.equalsIgnoreCase(currentOldFolderPath)
                    && !currentNewFolderPath.equals(currentOldFolderPath))
                {
                    analysis.getRenames().add(
                        new CaseSensitiveRename(currentOldFolderPath, currentNewFolderPath, null, false));
                }
                else
                {
                    break;
                }

                currentOldFolderPath = RepositoryPath.getParent(currentOldFolderPath);
                currentNewFolderPath = RepositoryPath.getParent(currentNewFolderPath);
            }

            return true;
        }

        return false;
    }

    private String getNewCaseSensitiveName(Set<String> candidateRenamedFolders, String oldFolderName)
    {
        for (String candidateNewName : candidateRenamedFolders)
        {
            if (candidateNewName.equalsIgnoreCase(oldFolderName) && !candidateNewName.equals(oldFolderName))
            {
                return candidateNewName;
            }
        }
        return null;
    }

    private String getFolderToDelete(
        String filePath,
        String previousFilePath,
        ObjectReader objectReader,
        RevObject commitRevTree)
        throws MissingObjectException,
            IncorrectObjectTypeException,
            CorruptObjectException,
            IOException
    {
        TreeWalk folder = null;

        try
        {
            if (filePath == null || filePath.length() == 0)
            {
                return previousFilePath;
            }

            folder = TreeWalk.forPath(objectReader, filePath, commitRevTree);

            if (folder == null)
            {
                return getFolderToDelete(RepositoryPath.getParent(filePath), filePath, objectReader, commitRevTree);
            }

            return previousFilePath;
        }
        finally
        {
            if (folder != null)
            {
                folder.release();
            }
        }
    }

    private PendingChange[] pendChanges(CheckinAnalysis analysis, final TaskProgressMonitor progressMonitor)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        progressMonitor.beginTask(
            Messages.getString("PendDifferencesTask.PendingChanges"), 5, TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL); //$NON-NLS-1$

        WorkspaceOperationErrorListener errorListener = null;

        try
        {
            errorListener = new WorkspaceOperationErrorListener(workspace);

            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingDeletes")); //$NON-NLS-1$
            pendDeletes(analysis, errorListener);
            progressMonitor.worked(1);

            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingEdits")); //$NON-NLS-1$
            pendEdits(analysis, errorListener);
            progressMonitor.worked(1);

            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingAdds")); //$NON-NLS-1$
            pendAdds(analysis, errorListener);
            progressMonitor.worked(1);

            progressMonitor.setDetail(Messages.getString("PendDifferencesTask.PendingRenames")); //$NON-NLS-1$
            pendCaseSensitiveRenames(analysis, errorListener);
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

            return pendingSet.getPendingChanges();
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

    private void pendDeletes(CheckinAnalysis analysis, WorkspaceOperationErrorListener errorListener)
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
            final Delete delete = analysis.getDeletes().get(i);

            deleteSpecs[i] =
                new ItemSpec(ServerPath.combine(serverPathRoot, delete.getPath()), delete.getType() == OBJ_TREE
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

    private void pendEdits(CheckinAnalysis analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        if (analysis.getEdits().size() == 0)
        {
            return;
        }

        List<ItemSpec> editSpecs = new ArrayList<ItemSpec>();
        List<LockLevel> lockLevels = new ArrayList<LockLevel>();

        for (Edit edit : analysis.getEdits())
        {
            /* TODO: property changes +x / -x */
            if (!edit.isContentModified())
            {
                continue;
            }

            extractToWorkingFolder(edit.getPath(), edit.getObjectID());

            /* Set up the edit item specs */
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
                null);

        errorListener.validate();

        /* TODO: property changes */

        if (count != editSpecs.size())
        {
            throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
        }
    }

    private void pendAdds(CheckinAnalysis analysis, WorkspaceOperationErrorListener errorListener)
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
            final Add add = analysis.getAdds().get(i);

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

    private void pendCaseSensitiveRenames(CheckinAnalysis analysis, WorkspaceOperationErrorListener errorListener)
        throws Exception
    {
        Check.notNull(analysis, "analysis"); //$NON-NLS-1$

        int renameCount = analysis.getRenames().size();

        if (renameCount == 0)
        {
            return;
        }

        List<String> renameOldPaths = new ArrayList<String>();
        List<String> renameNewPaths = new ArrayList<String>();

        List<ItemSpec> editSpecs = new ArrayList<ItemSpec>();
        List<LockLevel> lockLevels = new ArrayList<LockLevel>();

        for (int i = 0; i < renameCount; i++)
        {
            final CaseSensitiveRename rename = analysis.getRenames().get(i);

            if (!rename.isParentOnlyRenamed())
            {
                renameOldPaths.add(ServerPath.combine(serverPathRoot, rename.getOldPath()));
                renameNewPaths.add(ServerPath.combine(serverPathRoot, rename.getNewPath()));
            }

            if (rename.getObjectID() != null)
            {
                editSpecs.add(new ItemSpec(ServerPath.combine(serverPathRoot, rename.getOldPath()), RecursionType.NONE));
                lockLevels.add(LockLevel.NONE);

                extractToWorkingFolder(rename.getOldPath(), rename.getObjectID());
            }
        }

        // If this is a file we need to pend an edit as well just in case the
        // content also changed. We pend an edit for all renames because it
        // is expensive to figure out if the item content really changed
        // or not. However, the server will automatically strip out the edit
        // change if the contents are the same.
        if (editSpecs.size() > 0)
        {
            workspace.pendEdit(
                editSpecs.toArray(new ItemSpec[editSpecs.size()]),
                lockLevels.toArray(new LockLevel[lockLevels.size()]),
                null,
                GetOptions.NO_DISK_UPDATE,
                PendChangesOptions.NONE,
                null);
        }

        errorListener.validate();

        int count =
            workspace.pendRename(
                renameOldPaths.toArray(new String[renameOldPaths.size()]),
                renameNewPaths.toArray(new String[renameNewPaths.size()]),
                LockLevel.NONE,
                GetOptions.NO_DISK_UPDATE,
                false,
                PendChangesOptions.NONE);

        errorListener.validate();

        if (count < renameCount)
        {
            throw new Exception(Messages.getString("PendDifferencesTask.PendFailed")); //$NON-NLS-1$
        }
    }

    /*
     * Get the data out of the object database and into the working folder
     */
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

    private class CheckinAnalysis
    {
        private final List<Add> adds = new ArrayList<Add>();
        private final List<Edit> edits = new ArrayList<Edit>();
        private final List<Delete> deletes = new ArrayList<Delete>();
        private final List<CaseSensitiveRename> renames = new ArrayList<CaseSensitiveRename>();

        public CheckinAnalysis()
        {
        }

        public boolean isEmpty()
        {
            return adds.isEmpty() && edits.isEmpty() && deletes.isEmpty() && renames.isEmpty();
        }

        public final List<Add> getAdds()
        {
            return adds;
        }

        public final List<Edit> getEdits()
        {
            return edits;
        }

        public final List<Delete> getDeletes()
        {
            return deletes;
        }

        public final List<CaseSensitiveRename> getRenames()
        {
            return renames;
        }

        public void prepareCaseSensitiveRenames()
        {
            if (adds.size() == 0 || deletes.size() == 0)
            {
                return;
            }

            List<Add> addsToRemove = new ArrayList<Add>();
            List<Delete> deletesToRemove = new ArrayList<Delete>();

            for (Add add : adds)
            {
                for (Delete delete : deletes)
                {
                    if (add.getPath().equalsIgnoreCase(delete.getPath()))
                    {
                        addsToRemove.add(add);
                        deletesToRemove.add(delete);

                        String newFileName = RepositoryPath.getFileName(add.getPath());
                        String oldFileName = RepositoryPath.getFileName(delete.getPath());
                        boolean isFileRenamed =
                            newFileName.equalsIgnoreCase(oldFileName) && !newFileName.equals(oldFileName);

                        renames.add(new CaseSensitiveRename(
                            delete.getPath(),
                            add.getPath(),
                            add.getObjectID(),
                            !isFileRenamed));
                    }
                }
            }

            for (Add toRemove : addsToRemove)
            {
                adds.remove(toRemove);
            }

            for (Delete toRemove : deletesToRemove)
            {
                deletes.remove(toRemove);
            }

            ensureNoDuplicates();
        }

        private void ensureNoDuplicates()
        {
            Set<String> entries = new HashSet<String>();
            List<CaseSensitiveRename> renamesToRemove = new ArrayList<CaseSensitiveRename>();

            for (CaseSensitiveRename rename : getRenames())
            {
                String oldName = rename.getOldPath().toLowerCase();
                if (entries.contains(oldName))
                {
                    renamesToRemove.add(rename);
                }
                else
                {
                    entries.add(oldName);
                }
            }

            for (CaseSensitiveRename toRemove : renamesToRemove)
            {
                renames.remove(toRemove);
            }
        }
    }

    private class CaseSensitiveRename
    {
        private final String oldPath;
        private final String newPath;
        private final boolean isParentOnlyRenamed;
        private final ObjectId objectID;

        public CaseSensitiveRename(
            final String oldPath,
            final String newPath,
            final ObjectId objectID,
            boolean isParentOnlyRenamed)
        {
            Check.notNullOrEmpty(oldPath, "oldPath"); //$NON-NLS-1$
            Check.notNullOrEmpty(newPath, "newPath"); //$NON-NLS-1$

            this.oldPath = oldPath;
            this.newPath = newPath;
            this.objectID = objectID;
            this.isParentOnlyRenamed = isParentOnlyRenamed;
        }

        public String getOldPath()
        {
            return oldPath;
        }

        public String getNewPath()
        {
            return newPath;
        }

        public ObjectId getObjectID()
        {
            return objectID;
        }

        public boolean isParentOnlyRenamed()
        {
            return isParentOnlyRenamed;
        }
    }

    private class Delete
    {
        private final String path;
        private final int type;

        public Delete(final String path, final int type)
        {
            Check.notNullOrEmpty(path, "path"); //$NON-NLS-1$

            this.path = path;
            this.type = type;
        }

        public String getPath()
        {
            return path;
        }

        public int getType()
        {
            return type;
        }
    }

    private class Edit
    {
        private final String path;
        private final ObjectId objectID;
        private final boolean modified;

        public Edit(
            final String path,
            ObjectId objectID,
            final boolean modified,
            final FileMode oldMode,
            final FileMode newMode)
        {
            Check.notNullOrEmpty(path, "path"); //$NON-NLS-1$
            Check.notNull(objectID, "objectID"); //$NON-NLS-1$
            Check.notNull(oldMode, "oldMode"); //$NON-NLS-1$
            Check.notNull(newMode, "newMode"); //$NON-NLS-1$

            this.path = path;
            this.objectID = objectID;
            this.modified = modified;
        }

        public String getPath()
        {
            return path;
        }

        public ObjectId getObjectID()
        {
            return objectID;
        }

        public boolean isContentModified()
        {
            return modified;
        }
    }

    private static class Add
    {
        private final String path;
        private final ObjectId objectID;

        public Add(final String path, final ObjectId objectID, final FileMode fileMode)
        {
            Check.notNullOrEmpty(path, "path"); //$NON-NLS-1$
            Check.notNull(objectID, "objectID"); //$NON-NLS-1$
            Check.notNull(fileMode, "fileMode"); //$NON-NLS-1$

            this.path = path;
            this.objectID = objectID;
        }

        public String getPath()
        {
            return path;
        }

        public ObjectId getObjectID()
        {
            return objectID;
        }
    }
}
