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
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.TreeMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import com.microsoft.gittf.core.GitTFConstants;
import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.ChangesetCommitMap;
import com.microsoft.gittf.core.config.GitTFConfiguration;
import com.microsoft.gittf.core.interfaces.VersionControlService;
import com.microsoft.gittf.core.tasks.framework.Task;
import com.microsoft.gittf.core.tasks.framework.TaskProgressDisplay;
import com.microsoft.gittf.core.tasks.framework.TaskProgressMonitor;
import com.microsoft.gittf.core.tasks.framework.TaskStatus;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.DirectoryUtil;
import com.microsoft.gittf.core.util.RepositoryPath;
import com.microsoft.gittf.core.util.tree.CommitTreeEntry;
import com.microsoft.gittf.core.util.tree.CommitTreePath;
import com.microsoft.gittf.core.util.tree.CommitTreePathComparator;
import com.microsoft.tfs.core.clients.versioncontrol.PropertyConstants;
import com.microsoft.tfs.core.clients.versioncontrol.PropertyUtils;
import com.microsoft.tfs.core.clients.versioncontrol.path.ServerPath;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Changeset;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.Item;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.ItemType;
import com.microsoft.tfs.core.clients.versioncontrol.soapextensions.RecursionType;
import com.microsoft.tfs.core.clients.versioncontrol.specs.version.ChangesetVersionSpec;
import com.microsoft.tfs.util.FileHelpers;

public class CreateCommitTask
    extends Task
{
    private static final Log log = LogFactory.getLog(CreateCommitTask.class);

    private final Repository repository;
    private final VersionControlService versionControlClient;
    private final int changesetID;
    private final ObjectId parentCommitID;

    private final String serverPath;
    private final File tempDir;

    private ObjectId commitID;
    private ObjectId commitTreeID;

    public CreateCommitTask(
        final Repository repository,
        final VersionControlService versionControlClient,
        int changesetID,
        ObjectId parentCommitID)
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$
        Check.notNull(versionControlClient, "versionControlClient"); //$NON-NLS-1$
        Check.isTrue(changesetID >= 0, "changesetID >= 0"); //$NON-NLS-1$

        this.repository = repository;
        this.versionControlClient = versionControlClient;
        this.changesetID = changesetID;
        this.parentCommitID = parentCommitID;

        final GitTFConfiguration configuration = GitTFConfiguration.loadFrom(repository);
        Check.notNull(configuration, "configuration"); //$NON-NLS-1$

        this.serverPath = configuration.getServerPath();
        Check.notNullOrEmpty(serverPath, "serverPath"); //$NON-NLS-1$

        /* Set up a temporary directory */
        this.tempDir = DirectoryUtil.getTempDir(repository);
        Check.notNull(tempDir, "tempDir"); //$NON-NLS-1$
    }

    public ObjectId getCommitID()
    {
        return commitID;
    }

    public ObjectId getCommitTreeID()
    {
        return commitTreeID;
    }

    @Override
    public TaskStatus run(final TaskProgressMonitor progressMonitor)
    {
        progressMonitor.beginTask(
            Messages.formatString("CreateCommitTask.CreatingCommitFormat", Integer.toString(changesetID)), 1, TaskProgressDisplay.DISPLAY_SUBTASK_DETAIL); //$NON-NLS-1$

        ObjectInserter repositoryInserter = null;

        try
        {
            /* Sanity-check the temporary directory */
            if (!tempDir.exists())
            {
                if (!tempDir.mkdirs())
                {
                    return new TaskStatus(TaskStatus.ERROR, Messages.formatString(
                        "CreateCommitTask.ErrorCreatingTempDirectoryMessageFormat", //$NON-NLS-1$
                        tempDir.getAbsolutePath()));
                }
            }

            if (!tempDir.isDirectory())
            {
                return new TaskStatus(TaskStatus.ERROR, Messages.formatString(
                    "CreateCommitTask.ErrorCreatingTempDirectoryMessageFormat", //$NON-NLS-1$
                    tempDir.getAbsolutePath()));
            }

            /* Make sure that the changeset requested exist on the server */
            final Changeset changeset = versionControlClient.getChangeset(changesetID);
            if (changeset == null)
            {
                return new TaskStatus(TaskStatus.ERROR, Messages.formatString(
                    "CreateCommitTask.ChangesetNotFoundFormat", //$NON-NLS-1$
                    Integer.toString(changesetID)));
            }

            /*
             * Retrieve the items at the specified changeset version from the
             * server
             */
            Item[] items =
                versionControlClient.getItems(serverPath, new ChangesetVersionSpec(changesetID), RecursionType.FULL);

            /*
             * We want to optimize the tree building process. To do so we will
             * inspect the changeset commit map for the previous changeset
             * downloaded and use it to extract the objectIds for the files that
             * have not changed.
             */

            final ChangesetCommitMap changesetCommitMap = new ChangesetCommitMap(repository);
            final int previousChangesetId = changesetCommitMap.getPreviousBridgedChangeset(changesetID, true);
            final ObjectId previousChangesetCommitId =
                previousChangesetId >= 0 ? changesetCommitMap.getCommitID(previousChangesetId, true) : null;

            final ChangesetCommitItemReader previousChangesetCommitReader =
                new ChangesetCommitItemReader(previousChangesetId, previousChangesetCommitId);

            /*
             * We want trees sorted by children first so we can simply walk them
             * (child-first) to build the hierarchy once we've finished
             * inserting blobs.
             */
            final Map<CommitTreePath, Map<CommitTreePath, CommitTreeEntry>> treeHierarchy =
                new TreeMap<CommitTreePath, Map<CommitTreePath, CommitTreeEntry>>(new CommitTreePathComparator());

            repositoryInserter = repository.newObjectInserter();

            /*
             * Phase one: insert files as blobs in the git repository and add
             * them to the TreeFormatter for their parent folder.
             */
            if (items != null)
            {
                progressMonitor.setWork(items.length);
                for (int i = 0; i < items.length; i++)
                {
                    createBlobs(
                        repositoryInserter,
                        treeHierarchy,
                        previousChangesetCommitReader,
                        items[i],
                        progressMonitor);

                    progressMonitor.worked(1);
                }
            }

            /*
             * Phase two: add child trees to their parents.
             */
            progressMonitor.setDetail(Messages.getString("CreateCommitTask.CreatingTrees")); //$NON-NLS-1$
            ObjectId rootTree = createTrees(repositoryInserter, treeHierarchy);

            /* Phase three: create the commit. */
            progressMonitor.setDetail(Messages.getString("CreateCommitTask.CreatingCommit")); //$NON-NLS-1$            
            ObjectId commit = createCommit(changeset, repositoryInserter, rootTree);

            repositoryInserter.flush();

            FileHelpers.deleteDirectory(tempDir);

            progressMonitor.endTask();

            this.commitID = commit;
            this.commitTreeID = rootTree;

            return TaskStatus.OK_STATUS;
        }
        catch (Exception e)
        {
            return new TaskStatus(TaskStatus.ERROR, e);
        }
        finally
        {
            if (repositoryInserter != null)
            {
                repositoryInserter.release();
            }
        }
    }

    private void createBlobs(
        final ObjectInserter repositoryInserter,
        final Map<CommitTreePath, Map<CommitTreePath, CommitTreeEntry>> treeHierarchy,
        final ChangesetCommitItemReader previousChangesetCommitReader,
        final Item item,
        final TaskProgressMonitor progressMonitor)
        throws Exception
    {
        Check.notNull(repositoryInserter, "repositoryInserter"); //$NON-NLS-1$
        Check.notNull(treeHierarchy, "treeHierarchy"); //$NON-NLS-1$
        Check.notNull(item, "item"); //$NON-NLS-1$
        Check.notNull(progressMonitor, "progressMonitor"); //$NON-NLS-1$

        if (item.getItemType() == ItemType.FOLDER)
        {
            return;
        }
        else if (item.getItemType() == ItemType.FILE)
        {
            String folderName = ServerPath.makeRelative(ServerPath.getParent(item.getServerItem()), serverPath);
            String fileName = ServerPath.getFileName(item.getServerItem());
            String fileRelativePath = ServerPath.makeRelative(item.getServerItem(), serverPath);

            progressMonitor.setDetail(fileName);

            addToTreeHierarchy(treeHierarchy, folderName);

            Map<CommitTreePath, CommitTreeEntry> parentTree =
                treeHierarchy.get(new CommitTreePath(folderName, OBJ_TREE));

            if (parentTree == null)
            {
                throw new RuntimeException(Messages.formatString("CreateCommitTask.CouldNotLocateParentTreeFormat", //$NON-NLS-1$
                    folderName));
            }

            File tempFile = File.createTempFile(GitTFConstants.GIT_TF_NAME, null, tempDir);
            InputStream tempInputStream = null;

            try
            {
                ObjectId blobID =
                    previousChangesetCommitReader.getFileObjectId(fileRelativePath, item.getChangeSetID());

                if (blobID == null || ObjectId.equals(blobID, ObjectId.zeroId()))
                {
                    versionControlClient.downloadFile(item, tempFile.getAbsolutePath());

                    if (tempFile.exists())
                    {
                        tempInputStream = new FileInputStream(tempFile);
                        blobID = repositoryInserter.insert(OBJ_BLOB, tempFile.length(), tempInputStream);
                    }
                    else
                    {
                        blobID = ObjectId.zeroId();
                    }
                }

                FileMode fileMode;

                /* handle executable files */
                if (item.getPropertyValues() != null
                    && PropertyConstants.EXECUTABLE_ENABLED_VALUE.equals(PropertyUtils.selectMatching(
                        item.getPropertyValues(),
                        PropertyConstants.EXECUTABLE_KEY)))
                {
                    fileMode = FileMode.EXECUTABLE_FILE;
                }
                else
                {
                    fileMode = FileMode.REGULAR_FILE;
                }

                parentTree.put(new CommitTreePath(fileName, OBJ_BLOB), new CommitTreeEntry(fileMode, blobID));
            }
            finally
            {
                if (tempInputStream != null)
                {
                    tempInputStream.close();
                }

                tempFile.delete();
            }
        }
        else
        {
            log.warn(MessageFormat.format("unknown item type {0}", //$NON-NLS-1$
                Integer.toString(item.getItemType().getValue())));
        }
    }

    private void addToTreeHierarchy(
        final Map<CommitTreePath, Map<CommitTreePath, CommitTreeEntry>> treeHierarchy,
        final String folderPath)
        throws Exception
    {
        final CommitTreePath folderKey = new CommitTreePath(folderPath, OBJ_TREE);

        if (treeHierarchy.containsKey(folderKey))
        {
            return;
        }

        treeHierarchy.put(folderKey, new TreeMap<CommitTreePath, CommitTreeEntry>());

        if (folderPath.length() == 0)
        {
            return;
        }

        int separatorIdx = folderPath.lastIndexOf(RepositoryPath.PREFERRED_SEPARATOR_CHARACTER);
        String folderParent = (separatorIdx > 0) ? folderPath.substring(0, separatorIdx) : ""; //$NON-NLS-1$

        addToTreeHierarchy(treeHierarchy, folderParent);
    }

    private ObjectId createTrees(
        final ObjectInserter repositoryInserter,
        final Map<CommitTreePath, Map<CommitTreePath, CommitTreeEntry>> treeHierarchy)
        throws IOException
    {
        Check.notNull(repositoryInserter, "repositoryInserter"); //$NON-NLS-1$
        Check.notNull(treeHierarchy, "treeHierarchy"); //$NON-NLS-1$

        /*
         * if their at at least one file in the directory then we link the
         * hierarchies together - otherwise this is an empty folder thus we need
         * to create an empty tree
         */
        if (treeHierarchy.size() > 0)
        {
            /* Link up trees with their parents */
            for (Entry<CommitTreePath, Map<CommitTreePath, CommitTreeEntry>> treeEntry : treeHierarchy.entrySet())
            {
                String repoRelativeName = treeEntry.getKey().getName();
                Map<CommitTreePath, CommitTreeEntry> thisTree = treeEntry.getValue();

                /* Ignore the repository root directory */
                if (repoRelativeName.length() == 0)
                {
                    continue;
                }

                String folderName =
                    repoRelativeName.indexOf(RepositoryPath.PREFERRED_SEPARATOR_CHARACTER) >= 0
                        ? RepositoryPath.getParent(repoRelativeName) : ""; //$NON-NLS-1$

                String fileName = RepositoryPath.getFileName(repoRelativeName);

                Map<CommitTreePath, CommitTreeEntry> parentTree =
                    treeHierarchy.get(new CommitTreePath(folderName, OBJ_TREE));

                if (parentTree == null)
                {
                    throw new RuntimeException(Messages.formatString("CreateCommitTask.CouldNotLocateParentTreeFormat", //$NON-NLS-1$
                        folderName));
                }

                ObjectId thisTreeID = createTree(repositoryInserter, thisTree);

                if (!ObjectId.zeroId().equals(thisTreeID))
                {
                    parentTree.put(new CommitTreePath(fileName, OBJ_TREE), new CommitTreeEntry(
                        FileMode.TREE,
                        thisTreeID));
                }
            }

            /* Add the root tree */
            Map<CommitTreePath, CommitTreeEntry> rootTree = treeHierarchy.get(new CommitTreePath("", OBJ_TREE)); //$NON-NLS-1$

            if (rootTree == null)
            {
                throw new RuntimeException(Messages.getString("CreateCommitTask.CouldNotLocateRootTree")); //$NON-NLS-1$
            }

            return createTree(repositoryInserter, rootTree);
        }
        else
        {
            return createEmptyTree(repositoryInserter);
        }
    }

    private ObjectId createTree(final ObjectInserter repositoryInserter, final Map<CommitTreePath, CommitTreeEntry> tree)
        throws IOException
    {
        if (tree.isEmpty())
        {
            return createEmptyTree(repositoryInserter);
        }

        TreeFormatter treeFormatter = new TreeFormatter();

        for (Entry<CommitTreePath, CommitTreeEntry> entry : tree.entrySet())
        {
            String name = entry.getKey().getName();
            FileMode mode = entry.getValue().getFileMode();
            ObjectId objectId = entry.getValue().getObjectID();

            treeFormatter.append(name, mode, objectId);
        }

        return treeFormatter.insertTo(repositoryInserter);
    }

    private ObjectId createEmptyTree(ObjectInserter repositoryInserter)
        throws IOException
    {
        TreeFormatter treeFormatter = new TreeFormatter();
        return treeFormatter.insertTo(repositoryInserter);
    }

    private ObjectId createCommit(Changeset changeset, ObjectInserter repositoryInserter, ObjectId rootTree)
        throws IOException
    {
        Check.notNull(changeset, "changeset"); //$NON-NLS-1$
        Check.notNull(repositoryInserter, "repositoryInserter"); //$NON-NLS-1$
        Check.notNull(rootTree, "rootTree"); //$NON-NLS-1$

        String authorName = changeset.getOwnerDisplayName();
        String authorEmail = changeset.getOwner();
        String committerName = changeset.getCommitterDisplayName();
        String committerEmail = changeset.getCommitter();

        /* TODO: look up email addresses (TFS 11+) in identity service */
        Calendar changesetDate = changeset.getDate();

        String commitMessage = changeset.getComment();

        /*
         * Store the commit.
         */
        CommitBuilder commit = new CommitBuilder();
        commit.setAuthor(new PersonIdent(authorName, authorEmail, changesetDate.getTime(), TimeZone.getDefault()));
        commit.setCommitter(new PersonIdent(
            committerName,
            committerEmail,
            changesetDate.getTime(),
            TimeZone.getDefault()));
        commit.setMessage(commitMessage);
        commit.setTreeId(rootTree);

        if (parentCommitID != null)
        {
            commit.setParentId(parentCommitID);
        }

        return repositoryInserter.insert(commit);
    }

    private class ChangesetCommitItemReader
    {
        private boolean initialized = false;

        private final int changesetID;
        private final ObjectId commitId;

        private RevTree commitRevTree;
        private ObjectReader objectReader;

        private Map<String, Integer> changesetItems;

        public ChangesetCommitItemReader(int changesetId, ObjectId commitId)
        {
            this.changesetID = changesetId;
            this.commitId = commitId;
        }

        public ObjectId getFileObjectId(String filepath, int requestedVersion)
        {
            if (!initialized)
            {
                initialize();
            }

            if (commitRevTree == null || objectReader == null)
            {
                return null;
            }

            TreeWalk file;
            try
            {
                if (commitContainsFileAtVersion(ServerPath.combine(serverPath, filepath), requestedVersion))
                {
                    file = TreeWalk.forPath(objectReader, filepath, commitRevTree);

                    if (file == null)
                    {
                        return null;
                    }

                    return file.getObjectId(0);
                }
            }
            catch (Exception e)
            {
                // if we cannot read the object then we do not need to optimize
                // the call
            }

            return null;
        }

        private void initialize()
        {
            Check.isTrue(initialized == false, "initialized == false"); //$NON-NLS-1$

            initialized = true;

            if (commitId != null)
            {
                try
                {
                    final RevWalk walker = new RevWalk(repository);
                    final RevCommit revCommit = walker.parseCommit(commitId);
                    commitRevTree = revCommit.getTree();
                    objectReader = repository.newObjectReader();
                }
                catch (Exception e)
                {
                    // if we cannot read the object then we do not need to
                    // optimize the call
                    commitRevTree = null;
                    objectReader = null;

                    return;
                }

                Item[] items =
                    versionControlClient.getItems(serverPath, new ChangesetVersionSpec(changesetID), RecursionType.FULL);

                changesetItems = new HashMap<String, Integer>(items.length);
                for (Item item : items)
                {
                    changesetItems.put(item.getServerItem().toLowerCase(), item.getChangeSetID());
                }
            }
        }

        private boolean commitContainsFileAtVersion(String filePath, int requestedVersion)
        {
            String filePathKey = filePath.toLowerCase();

            if (changesetItems.containsKey(filePathKey))
            {
                int changesetVersionInChangeset = changesetItems.get(filePathKey);
                return changesetVersionInChangeset == requestedVersion;
            }

            return false;
        }
    }
}
