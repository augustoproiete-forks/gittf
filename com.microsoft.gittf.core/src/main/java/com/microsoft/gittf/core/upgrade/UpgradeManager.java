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

package com.microsoft.gittf.core.upgrade;

import java.io.File;

import org.eclipse.jgit.lib.Repository;

import com.microsoft.gittf.core.GitTFConstants;
import com.microsoft.gittf.core.Messages;
import com.microsoft.gittf.core.config.ChangesetCommitMap;
import com.microsoft.gittf.core.config.GitTFConfiguration;
import com.microsoft.tfs.util.FileHelpers;

public class UpgradeManager
{
    public static void upgradeIfNeccessary(Repository repository)
        throws Exception
    {
        GitTFConfiguration currentConfiguration = GitTFConfiguration.loadFrom(repository);
        if (currentConfiguration == null)
        {
            return;
        }

        int existingFormat = currentConfiguration.getFileFormatVersion();
        if (existingFormat == GitTFConstants.GIT_TF_CURRENT_FORMAT_VERSION)
        {
            return;
        }

        if (existingFormat == 0)
        {
            upgradeFromV0ToV1(repository, currentConfiguration);
        }
    }

    private static void upgradeFromV0ToV1(Repository repository, GitTFConfiguration currentConfiguration)
        throws Exception
    {
        // 1. Delete old temp git-tf folder sense we will create a config with
        // the same name. Newly created temp git-tf folder will be named "tf".
        File currentTempFileLocation = new File(repository.getDirectory(), GitTFConstants.GIT_TF_NAME);
        if (currentTempFileLocation.exists() && currentTempFileLocation.isDirectory())
        {
            boolean cleanupTempDir = FileHelpers.deleteDirectory(currentTempFileLocation);
            if (!cleanupTempDir)
            {
                throw new Exception(
                    Messages.formatString(
                        "UpgradeManager.upgradeFromV0ToV1.CannotCleanUpTempDirectoryFormat", currentTempFileLocation.getAbsolutePath())); //$NON-NLS-1$
            }
        }

        // 2. Move the existing "changesets" and "commits" sections to the new
        // "git-tf" config file
        File newConfigFileLocation = new File(repository.getDirectory(), GitTFConstants.GIT_TF_NAME);
        if (!newConfigFileLocation.exists())
        {
            ChangesetCommitMap.upgradeExistingConfigurationIfNeeded(newConfigFileLocation, repository);
        }

        GitTFConfiguration newConfiguration =
            new GitTFConfiguration(
                currentConfiguration.getServerURI(),
                currentConfiguration.getServerPath(),
                currentConfiguration.getUsername(),
                currentConfiguration.getPassword(),
                currentConfiguration.getDeep(),
                1);

        newConfiguration.saveTo(repository);
    }
}
