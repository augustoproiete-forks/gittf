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

package com.microsoft.gittf.core.config;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;

import com.microsoft.gittf.core.GitTFConstants;
import com.microsoft.gittf.core.util.Check;
import com.microsoft.gittf.core.util.CommitUtil;

public class ChangesetCommitMap
{
    private final Repository repository;
    private final FileBasedConfig configFile;

    public ChangesetCommitMap(final Repository repository)
    {
        Check.notNull(repository, "repository"); //$NON-NLS-1$

        this.repository = repository;
        this.configFile =
            new FileBasedConfig(new File(repository.getDirectory(), GitTFConstants.GIT_TF_NAME), FS.DETECTED);
    }

    public void setChangesetCommit(int changesetID, ObjectId commitID)
        throws IOException
    {
        Check.isTrue(changesetID >= 0, "changesetID >= 0"); //$NON-NLS-1$
        Check.notNull(commitID, "commitID"); //$NON-NLS-1$

        ensureConfigUptoDate();

        cleanupPreviousEntries(changesetID);

        configFile.setString(
            ConfigurationConstants.CONFIGURATION_SECTION,
            ConfigurationConstants.COMMIT_SUBSECTION,
            MessageFormat.format(ConfigurationConstants.COMMIT_CHANGESET_FORMAT, Integer.toString(changesetID)),
            commitID.getName());

        configFile.setInt(
            ConfigurationConstants.CONFIGURATION_SECTION,
            ConfigurationConstants.CHANGESET_SUBSECTION,
            MessageFormat.format(ConfigurationConstants.CHANGESET_COMMIT_FORMAT, commitID.getName()),
            changesetID);

        /* Update the high water mark automatically */
        if (changesetID > getLastBridgedChangesetID(false))
        {
            configFile.setInt(
                ConfigurationConstants.CONFIGURATION_SECTION,
                ConfigurationConstants.CHANGESET_SUBSECTION,
                ConfigurationConstants.CHANGESET_HIGHWATER,
                changesetID);
        }

        configFile.save();

        CommitUtil.tagCommit(repository, commitID, changesetID);
    }

    public int getChangesetID(ObjectId commitID)
    {
        Check.notNull(commitID, "commitID"); //$NON-NLS-1$

        ensureConfigUptoDate();

        return configFile.getInt(
            ConfigurationConstants.CONFIGURATION_SECTION,
            ConfigurationConstants.CHANGESET_SUBSECTION,
            MessageFormat.format(ConfigurationConstants.CHANGESET_COMMIT_FORMAT, commitID.getName()),
            -1);
    }

    public ObjectId getCommitID(int changesetID, boolean validate)
    {
        Check.isTrue(changesetID >= 0, "changesetID >= 0"); //$NON-NLS-1$

        ensureConfigUptoDate();

        String commitHash =
            configFile.getString(
                ConfigurationConstants.CONFIGURATION_SECTION,
                ConfigurationConstants.COMMIT_SUBSECTION,
                MessageFormat.format(ConfigurationConstants.COMMIT_CHANGESET_FORMAT, Integer.toString(changesetID)));

        if (commitHash == null)
        {
            return null;
        }

        ObjectId changesetCommitId = ObjectId.fromString(commitHash);

        if (!validate)
        {
            return changesetCommitId;
        }

        ObjectReader objectReader = null;
        try
        {
            objectReader = repository.newObjectReader();
            if (changesetCommitId != null && !ObjectId.zeroId().equals(changesetCommitId))
            {
                try
                {
                    if (objectReader.has(changesetCommitId))
                    {
                        return changesetCommitId;
                    }
                }
                catch (IOException exception)
                {
                    return null;
                }
            }

            return null;
        }
        finally
        {
            if (objectReader != null)
            {
                objectReader.release();
            }
        }
    }

    public int getLastBridgedChangesetID(boolean validate)
    {
        ensureConfigUptoDate();

        int changeset =
            configFile.getInt(
                ConfigurationConstants.CONFIGURATION_SECTION,
                ConfigurationConstants.CHANGESET_SUBSECTION,
                ConfigurationConstants.CHANGESET_HIGHWATER,
                -1);

        if (changeset < 0)
        {
            return changeset;
        }

        if (!validate)
        {
            return changeset;
        }

        boolean lastValidDownloadedChangesetCommitFound = false;

        while (!lastValidDownloadedChangesetCommitFound)
        {
            if (changeset < 0)
            {
                return changeset;
            }

            ObjectId changesetCommitId = getCommitID(changeset, true);

            if (changesetCommitId != null && !ObjectId.zeroId().equals(changesetCommitId))
            {
                return changeset;
            }

            changeset = getPreviousBridgedChangeset(changeset, false);
        }

        return changeset;
    }

    public int getPreviousBridgedChangeset(int changesetID, boolean validate)
    {
        ensureConfigUptoDate();

        Set<String> downloadedChangesetEntries =
            configFile.getNames(ConfigurationConstants.CONFIGURATION_SECTION, ConfigurationConstants.COMMIT_SUBSECTION);

        Set<Integer> sortedDownloadedChangesetEntries = new TreeSet<Integer>(Collections.reverseOrder());

        for (String downloadedChangesetEntry : downloadedChangesetEntries)
        {
            String changesetNumberString =
                downloadedChangesetEntry.substring(MessageFormat.format(
                    ConfigurationConstants.COMMIT_CHANGESET_FORMAT,
                    "").length()); //$NON-NLS-1$

            int changesetIDEntry = Integer.parseInt(changesetNumberString);

            sortedDownloadedChangesetEntries.add(changesetIDEntry);
        }

        Iterator<Integer> changesetIterator = sortedDownloadedChangesetEntries.iterator();
        while (changesetIterator.hasNext())
        {
            int currentChangeset = changesetIterator.next();

            if (currentChangeset >= changesetID)
            {
                continue;
            }

            if (!validate)
            {
                return currentChangeset;
            }
            else
            {
                ObjectId commitId = getCommitID(currentChangeset, true);
                if (commitId != null && !ObjectId.zeroId().equals(commitId))
                {
                    return currentChangeset;
                }
            }
        }

        return -1;
    }

    private void cleanupPreviousEntries(int changesetID)
    {
        String commitHash =
            configFile.getString(
                ConfigurationConstants.CONFIGURATION_SECTION,
                ConfigurationConstants.COMMIT_SUBSECTION,
                MessageFormat.format(ConfigurationConstants.COMMIT_CHANGESET_FORMAT, Integer.toString(changesetID)));

        if (commitHash == null || commitHash.length() == 0)
        {
            return;
        }

        configFile.unset(
            ConfigurationConstants.CONFIGURATION_SECTION,
            ConfigurationConstants.COMMIT_SUBSECTION,
            MessageFormat.format(ConfigurationConstants.COMMIT_CHANGESET_FORMAT, Integer.toString(changesetID)));

        configFile.unset(
            ConfigurationConstants.CONFIGURATION_SECTION,
            ConfigurationConstants.CHANGESET_SUBSECTION,
            MessageFormat.format(ConfigurationConstants.CHANGESET_COMMIT_FORMAT, commitHash));
    }

    private void ensureConfigUptoDate()
    {
        try
        {
            if (configFile != null && configFile.isOutdated())
            {
                configFile.load();
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    public static void upgradeExistingConfigurationIfNeeded(File newConfigLocation, Repository repository)
    {
        if (!newConfigLocation.exists())
        {
            FileBasedConfig configFile = new FileBasedConfig(newConfigLocation, FS.DETECTED);

            GitTFConfiguration repositoryConfiguration = GitTFConfiguration.loadFrom(repository);
            if (repositoryConfiguration != null)
            {
                copyConfigurationEntries(repository.getConfig(), configFile);
            }
        }
    }

    private static void copyConfigurationEntries(StoredConfig source, FileBasedConfig target)
    {
        Set<String> downloadedChangesetEntries =
            source.getNames(ConfigurationConstants.CONFIGURATION_SECTION, ConfigurationConstants.COMMIT_SUBSECTION);

        for (String changesetEntry : downloadedChangesetEntries)
        {
            String commitHash =
                source.getString(
                    ConfigurationConstants.CONFIGURATION_SECTION,
                    ConfigurationConstants.COMMIT_SUBSECTION,
                    changesetEntry);

            target.setString(
                ConfigurationConstants.CONFIGURATION_SECTION,
                ConfigurationConstants.COMMIT_SUBSECTION,
                changesetEntry,
                commitHash);
        }

        Set<String> createdCommitEntries =
            source.getNames(ConfigurationConstants.CONFIGURATION_SECTION, ConfigurationConstants.CHANGESET_SUBSECTION);

        for (String commitEntry : createdCommitEntries)
        {
            int changesetId =
                source.getInt(
                    ConfigurationConstants.CONFIGURATION_SECTION,
                    ConfigurationConstants.CHANGESET_SUBSECTION,
                    commitEntry,
                    -1);

            target.setInt(
                ConfigurationConstants.CONFIGURATION_SECTION,
                ConfigurationConstants.CHANGESET_SUBSECTION,
                commitEntry,
                changesetId);
        }

        source.unsetSection(ConfigurationConstants.CONFIGURATION_SECTION, ConfigurationConstants.CHANGESET_SUBSECTION);
        source.unsetSection(ConfigurationConstants.CONFIGURATION_SECTION, ConfigurationConstants.COMMIT_SUBSECTION);

        try
        {
            target.save();
            source.save();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }
}
